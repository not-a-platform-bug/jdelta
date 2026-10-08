package jdelta.cli

import jdelta.engine.ChangedFile
import java.nio.file.Path
import kotlin.io.path.Path

/** `git` CLI 호출 (ARCHITECTURE.md D11). 사용자의 git과 동작이 완전히 같다. */
internal class Git(private val workDir: Path) {
    fun topLevel(): Path = Path(run("rev-parse", "--show-toplevel").trim())

    /** commit을 가리키지 않으면 null. */
    fun resolve(rev: String): String? = tryRun("rev-parse", "--verify", "--quiet", "$rev^{commit}")?.trim()?.ifEmpty { null }

    fun mergeBase(a: String, b: String): String = run("merge-base", a, b).trim()

    /** 추적 중인 파일의 수정과 무시되지 않은 untracked 파일이 있으면 dirty다. */
    fun isDirty(): Boolean = run("status", "--porcelain", "--untracked-files=normal", "--", ".", EXCLUDE_WORKSPACE).isNotBlank()

    /** [base] commit과 working tree 사이의 변경 파일. untracked 파일은 ADDED다. */
    fun changedFiles(base: String): List<ChangedFile> {
        val tracked = run("diff", "--name-status", "--no-renames", base, "--", ".", EXCLUDE_WORKSPACE).lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val status = line.substringBefore('\t')
                val path = line.substringAfter('\t')
                ChangedFile(
                    path,
                    when (status.first()) {
                        'A' -> ChangedFile.Status.ADDED
                        'D' -> ChangedFile.Status.DELETED
                        else -> ChangedFile.Status.MODIFIED
                    },
                )
            }
        val untracked = run("ls-files", "--others", "--exclude-standard", "--", ".", EXCLUDE_WORKSPACE).lineSequence()
            .filter { it.isNotBlank() }
            .map { ChangedFile(it, ChangedFile.Status.ADDED) }
        return (tracked + untracked).distinctBy { it.path }.toList()
    }

    /** `origin/HEAD`, `main`, `master` 순서로 존재하는 첫 revision. */
    fun defaultBase(): String? = listOf("origin/HEAD", "main", "master").firstOrNull { resolve(it) != null }

    fun worktreeAdd(path: Path, sha: String) {
        run("worktree", "add", "--detach", path.toString(), sha)
    }

    fun worktreeRemove(path: Path) {
        tryRun("worktree", "remove", "--force", path.toString())
        tryRun("worktree", "prune")
    }

    private fun run(vararg args: String): String {
        val result = exec(*args)
        if (result.exitCode != 0) throw JDeltaFailure("git ${args.joinToString(" ")} failed: ${result.stderr.ifBlank { result.stdout }.trim()}")
        return result.stdout
    }

    private fun tryRun(vararg args: String): String? = exec(*args).takeIf { it.exitCode == 0 }?.stdout

    private fun exec(vararg args: String): ProcessResult = Processes.run(listOf("git", "-C", workDir.toString()) + args, workDir)

    private companion object {
        /** jdelta 자신의 작업 디렉터리는 변경으로 보지 않는다. */
        const val EXCLUDE_WORKSPACE = ":(exclude).jdelta"
    }
}
