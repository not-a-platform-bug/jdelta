package jdelta.cli

import jdelta.classfile.ProjectSnapshot
import jdelta.core.ProjectModel
import jdelta.engine.ChangedFile
import jdelta.engine.JDeltaEngine
import jdelta.engine.SnapshotRevision
import jdelta.engine.SnapshotStore
import jdelta.engine.SourceChanges
import jdelta.engine.StoredSnapshot
import jdelta.engine.Workspace
import jdelta.report.RevisionInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/** `main..HEAD` 형식. 비어 있는 쪽은 null. */
internal data class RevisionRange(val base: String?, val head: String?) {
    companion object {
        fun parse(spec: String?): RevisionRange {
            if (spec.isNullOrBlank()) return RevisionRange(null, null)
            val dots = spec.indexOf("..")
            if (dots < 0) return RevisionRange(spec, null)
            // `a...b`도 merge-base 기준이므로 `a..b`와 같게 다룬다
            val head = spec.substring(dots + 2).removePrefix(".")
            return RevisionRange(spec.substring(0, dots).ifEmpty { null }, head.ifEmpty { null })
        }
    }
}

/** baseline 해석 방법 (ARCHITECTURE.md §4.4). */
internal data class BaselineOptions(
    val exactBase: Boolean = false,
    val baselineDir: Path? = null,
    val buildBaseline: Boolean = false,
    val buildCommand: String? = null,
)

internal data class ResolvedDiff(
    val base: ProjectSnapshot,
    val head: ProjectSnapshot,
    val baseInfo: RevisionInfo,
    val headInfo: RevisionInfo,
    val changes: List<ChangedFile>,
    val headModel: ProjectModel,
    val notes: List<String>,
)

/**
 * git revision과 build output, snapshot 저장소를 묶는다.
 * engine은 git을 모르므로(§7) 이 조합은 CLI가 한다.
 */
internal class GitWorkflow(
    projectDir: Path,
    private val modelSource: ModelSource = ModelSource.AUTO,
    /** 이미 export된 model 파일. Gradle build 안에서 호출될 때 다시 Gradle을 띄우지 않기 위해 쓴다. */
    private val modelFile: Path? = null,
    private val log: (String) -> Unit,
) {
    val root: Path = Git(projectDir).topLevel()
    private val git = Git(root)
    private val workspace = Workspace(root)
    private val engine = JDeltaEngine()
    private var model: ProjectModel? = null

    /** report에 남길 model 출처. */
    var modelNote: String = ""
        private set

    fun currentModel(): ProjectModel = model ?: modelFor(root).also { model = it }

    /** 기본 build는 Gradle model export와 한 번의 Gradle 호출로 묶는다. */
    fun build(command: String?) {
        if (command == null && useGradle(root)) {
            model = GradleModelExport.export(root, BUILD_TASKS, log)
            modelNote = MODEL_FROM_GRADLE
            return
        }
        BuildRunner.run(root, command, log)
    }

    private fun useGradle(dir: Path): Boolean = when (modelSource) {
        ModelSource.LAYOUT -> false
        ModelSource.AUTO -> GradleModelExport.available(dir)
        ModelSource.GRADLE -> GradleModelExport.available(dir).also {
            if (!it) throw JDeltaFailure("--model gradle needs a Gradle wrapper in $dir and the jdelta Gradle plugin jar", ExitCode.USAGE)
        }
    }

    private fun modelFor(dir: Path): ProjectModel {
        if (modelFile != null && dir == root) {
            modelNote = "project model read from $modelFile"
            return jdelta.engine.ProjectModelFile.read(modelFile)
        }
        if (!useGradle(dir)) {
            modelNote = "project model inferred from the Gradle directory layout; module dependencies are unknown"
            return GradleLayout.discover(dir)
        }
        modelNote = MODEL_FROM_GRADLE
        return if (GradleModelExport.isFresh(dir)) GradleModelExport.read(dir) else GradleModelExport.export(dir, emptyList(), log)
    }

    fun resolve(range: RevisionRange, options: BaselineOptions, checkStaleness: Boolean): ResolvedDiff {
        val notes = ArrayList<String>()
        val headRev = range.head ?: "HEAD"
        val headSha = git.resolve(headRev) ?: throw JDeltaFailure("unknown revision: $headRev", ExitCode.USAGE)
        val baseRev = range.base ?: git.defaultBase()
            ?: throw JDeltaFailure("no base revision given and none of origin/HEAD, main, master exists", ExitCode.USAGE)
        val baseTip = git.resolve(baseRev) ?: throw JDeltaFailure("unknown revision: $baseRev", ExitCode.USAGE)
        val baseSha = if (options.exactBase) baseTip else git.mergeBase(baseTip, headSha)
        val baseInfo = RevisionInfo(baseRev, baseSha, if (options.exactBase) "exact" else "merge-base")

        val worktreeHead = range.head == null || range.head == "HEAD"
        val head: ProjectSnapshot
        val headInfo: RevisionInfo
        val headModel: ProjectModel
        val changes: List<ChangedFile>
        if (worktreeHead) {
            headModel = currentModel()
            if (headModel.modules.all { m -> m.sourceSets.all { it.classesDirs.isEmpty() } }) {
                throw JDeltaFailure("no build output found under $root (expected <module>/build/classes/...); build first or pass --build", ExitCode.STALE_OUTPUT)
            }
            head = engine.snapshot(headModel)
            changes = git.changedFiles(baseSha)
            if (checkStaleness) {
                val stale = SourceChanges(root, headModel, head).stale(changes)
                if (stale.isNotEmpty()) {
                    val list = stale.take(10).joinToString("\n") { "  ${it.path}: ${it.reason}" } + if (stale.size > 10) "\n  ... ${stale.size - 10} more" else ""
                    throw JDeltaFailure(
                        "build output is stale for ${stale.size} changed source file(s):\n$list\n" +
                            "rebuild, or pass --build to run the build before analysis (--skip-staleness-check to ignore)",
                        ExitCode.STALE_OUTPUT,
                    )
                }
            }
            val dirty = git.isDirty()
            headInfo = RevisionInfo("HEAD", headSha, "working tree", dirty)
            if (!dirty && checkStaleness && headSha != baseSha) saveQuietly(headSha, headModel, "HEAD")?.let { notes += it }
        } else {
            val stored = workspace.snapshots.load(headSha)
                ?: throw JDeltaFailure("no snapshot for head $headRev ($headSha); only the working tree or stored snapshots can be the head", ExitCode.BASELINE_MISSING)
            head = engine.snapshot(stored)
            headModel = stored.model
            changes = emptyList()
            headInfo = RevisionInfo(headRev, headSha, "snapshot")
        }

        val (baseline, source) = baseline(baseSha, baseRev, options)
        notes += "baseline snapshot: $source"
        val baselineSha = baseline.revision.sha
        if (baselineSha != null && baselineSha != baseSha) {
            notes += "baseline snapshot was taken at ${baselineSha.take(7)}, not at ${baseSha.take(7)}"
        }
        if (baseline.revision.dirty) notes += "baseline snapshot was taken from a dirty working tree"
        return ResolvedDiff(engine.snapshot(baseline), head, baseInfo, headInfo, changes, headModel, notes)
    }

    /** 현재 build output을 snapshot으로 저장한다. working tree가 dirty면 [out] 없이는 저장하지 않는다. */
    fun snapshot(out: Path?, checkStaleness: Boolean): Path {
        val model = currentModel()
        val headSha = git.resolve("HEAD") ?: throw JDeltaFailure("repository has no commits yet")
        if (checkStaleness) {
            val stale = SourceChanges(root, model, engine.snapshot(model)).stale(git.changedFiles(headSha))
            if (stale.isNotEmpty()) {
                throw JDeltaFailure("build output is stale for: ${stale.joinToString { it.path }}; rebuild or pass --build", ExitCode.STALE_OUTPUT)
            }
        }
        val dirty = git.isDirty()
        val revision = SnapshotRevision(headSha, "HEAD", dirty)
        if (out != null) {
            SnapshotStore.write(out, model, revision)
            return out
        }
        if (dirty) {
            throw JDeltaFailure("working tree has uncommitted changes; snapshots are stored per commit. Commit first or pass --out <dir>", ExitCode.USAGE)
        }
        return workspace.locked { workspace.snapshots.save(headSha, model, revision) }.dir
    }

    private fun baseline(sha: String, label: String, options: BaselineOptions): Pair<StoredSnapshot, String> {
        options.baselineDir?.let { dir ->
            if (!dir.isDirectory()) throw JDeltaFailure("baseline directory does not exist: $dir", ExitCode.USAGE)
            return try {
                SnapshotStore.read(dir) to "--baseline-dir $dir"
            } catch (e: IllegalArgumentException) {
                throw JDeltaFailure(e.message ?: "invalid baseline directory", ExitCode.USAGE)
            }
        }
        workspace.snapshots.load(sha)?.let { return it to ".jdelta/snapshots/${sha.take(7)}" }
        if (options.buildBaseline) return buildBaseline(sha, label, options.buildCommand) to "built in a temporary worktree at ${sha.take(7)}"
        throw JDeltaFailure(
            """
            no baseline snapshot for $label (${sha.take(7)}). Choose one:
              1. check out ${sha.take(7)}, build, and run 'jdelta snapshot' (or keep snapshots from CI)
              2. pass --baseline-dir <dir> with a snapshot made by 'jdelta snapshot --out <dir>'
              3. pass --build-baseline to build ${sha.take(7)} in a temporary git worktree
            """.trimIndent(),
            ExitCode.BASELINE_MISSING,
        )
    }

    /** `git worktree`에 base commit을 꺼내 build하고 snapshot을 저장한다. 명시적 opt-in일 때만 한다 (D9). */
    private fun buildBaseline(sha: String, label: String, command: String?): StoredSnapshot {
        val tmp = Files.createTempDirectory("jdelta-baseline-")
        Files.delete(tmp)
        log("building baseline $label (${sha.take(7)}) in $tmp")
        git.worktreeAdd(tmp, sha)
        try {
            val model = if (command == null && useGradle(tmp)) {
                GradleModelExport.export(tmp, BUILD_TASKS, log)
            } else {
                BuildRunner.run(tmp, command, log)
                modelFor(tmp)
            }
            return workspace.locked { workspace.snapshots.save(sha, model, SnapshotRevision(sha, label, dirty = false)) }
        } finally {
            git.worktreeRemove(tmp)
            if (tmp.exists()) tmp.toFile().deleteRecursively()
        }
    }

    private fun saveQuietly(sha: String, model: ProjectModel, label: String): String? {
        if (workspace.snapshots.contains(sha)) return null
        workspace.locked { workspace.snapshots.save(sha, model, SnapshotRevision(sha, label, dirty = false)) }
        return "stored snapshot of ${sha.take(7)} for later runs"
    }
}

internal enum class ModelSource { AUTO, GRADLE, LAYOUT }

private val BUILD_TASKS = listOf("classes", "testClasses")
private const val MODEL_FROM_GRADLE = "project model exported from Gradle (jdeltaExportModel)"

internal object BuildRunner {
    /** 기본 build: wrapper가 있으면 wrapper로 `classes testClasses`. */
    fun run(dir: Path, command: String?, log: (String) -> Unit) {
        val cmd = when {
            command != null -> shell(command)
            dir.resolve(if (isWindows) "gradlew.bat" else "gradlew").exists() ->
                listOf(dir.resolve(if (isWindows) "gradlew.bat" else "gradlew").toString(), "-q", "classes", "testClasses")
            else -> listOf("gradle", "-q", "classes", "testClasses")
        }
        log("running: ${cmd.joinToString(" ")}")
        val result = Processes.run(cmd, dir, onOutput = log)
        if (result.exitCode != 0) throw JDeltaFailure("build failed with exit code ${result.exitCode}: ${cmd.joinToString(" ")}")
    }

    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    private fun shell(command: String) = if (isWindows) listOf("cmd", "/c", command) else listOf("sh", "-c", command)
}
