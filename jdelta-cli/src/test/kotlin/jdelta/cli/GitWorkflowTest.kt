package jdelta.cli

import org.assertj.core.api.Assertions.assertThat
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.Test

/** 임시 git repository에서 snapshot -> diff 흐름을 확인한다 (ARCHITECTURE.md §4.4, §4.5). */
class GitWorkflowTest {
    private val repo: Path = Files.createTempDirectory("jdelta-repo").toRealPath()
    private val javac = Path.of(System.getProperty("java.home"), "bin", "javac")

    /** Gradle 대신 javac로 Gradle 관례 위치에 compile한다. */
    private val buildCommand =
        "rm -rf app/build && mkdir -p app/build/classes/java/main && \"$javac\" --release 17 -d app/build/classes/java/main \$(find app/src/main/java -name '*.java')"

    private val price = "app/src/main/java/com/acme/Price.java"

    private fun write(path: String, content: String) {
        repo.resolve(path).also { it.parent.createDirectories() }.writeText(content.trimIndent() + "\n")
    }

    private fun git(vararg args: String): String {
        val result = Processes.run(listOf("git", "-c", "user.name=jdelta", "-c", "user.email=jdelta@example.com", "-c", "commit.gpgsign=false") + args, repo)
        check(result.exitCode == 0) { "git ${args.toList()} failed: ${result.stderr}" }
        return result.stdout.trim()
    }

    private fun jdelta(vararg args: String): Triple<Int, String, String> {
        val out = StringWriter()
        val err = StringWriter()
        val code = run(arrayOf(*args, "--project-dir", repo.toString()), out, err)
        return Triple(code, out.toString(), err.toString())
    }

    private fun build() = check(Processes.run(listOf("sh", "-c", buildCommand), repo).exitCode == 0)

    private fun initRepo() {
        git("init", "-q", "-b", "main")
        write(".gitignore", "build/\n.jdelta/")
        write("settings.gradle.kts", "rootProject.name = \"demo\"\ninclude(\"app\")")
        write("app/build.gradle.kts", "plugins { java }")
        write(price, "package com.acme;\npublic class Price { public long total(int qty) { return qty * 100L; } }")
        git("add", ".")
        git("commit", "-q", "-m", "init")
    }

    private fun branchWith(content: String) {
        git("checkout", "-q", "-b", "feature")
        write(price, content)
        build()
    }

    @Test
    fun `snapshot on main then diff the feature branch against the merge-base`() {
        initRepo()
        build()
        val (snapshotCode, snapshotOut, _) = jdelta("snapshot")
        assertThat(snapshotCode).isEqualTo(ExitCode.OK)
        assertThat(snapshotOut).contains(".jdelta/snapshots/")

        git("checkout", "-q", "-b", "feature")
        write(price, "package com.acme;\npublic class Price { public long total(int qty) { return qty * 110L; } }")
        git("commit", "-q", "-am", "raise price")
        git("checkout", "-q", "main")
        write("README.md", "main moved on")
        git("add", ".")
        git("commit", "-q", "-m", "docs")
        git("checkout", "-q", "feature")
        build()

        val (code, out, err) = jdelta("diff", "main..HEAD")
        assertThat(code).describedAs(err).isEqualTo(ExitCode.OK)
        assertThat(out).contains("merge-base").contains("METHOD_BODY_CHANGED").contains("com.acme.Price#total(int)")
        assertThat(out).contains("baseline snapshot: .jdelta/snapshots/")
    }

    @Test
    fun `stale build output is refused`() {
        initRepo()
        build()
        jdelta("snapshot")
        branchWith("package com.acme;\npublic class Price { public long total(int qty) { return qty * 110L; } }")
        // build 이후 source만 다시 수정
        write(price, "package com.acme;\npublic class Price { public long total(int qty) { return qty * 120L; } }")
        Files.setLastModifiedTime(repo.resolve("app/build/classes/java/main/com/acme/Price.class"), FileTime.fromMillis(System.currentTimeMillis() - 60_000))

        val (code, _, err) = jdelta("diff", "main")
        assertThat(code).isEqualTo(ExitCode.STALE_OUTPUT)
        assertThat(err).contains(price).contains("--build")

        val (rebuilt, out, _) = jdelta("diff", "main", "--build", "--build-command", buildCommand)
        assertThat(rebuilt).isEqualTo(ExitCode.OK)
        assertThat(out).contains("METHOD_BODY_CHANGED")
    }

    @Test
    fun `missing baseline explains the options, and --build-baseline builds it in a worktree`() {
        initRepo()
        branchWith("package com.acme;\npublic class Price { public long total(int qty) { return qty * 110L; } }")

        val (missing, _, err) = jdelta("diff", "main")
        assertThat(missing).isEqualTo(ExitCode.BASELINE_MISSING)
        assertThat(err).contains("--baseline-dir").contains("--build-baseline").contains("jdelta snapshot")

        val (code, out, buildLog) = jdelta("diff", "main", "--build-baseline", "--build-command", buildCommand)
        assertThat(code).describedAs(buildLog).isEqualTo(ExitCode.OK)
        assertThat(out).contains("METHOD_BODY_CHANGED").contains("built in a temporary worktree")
        // 다음 실행은 저장된 snapshot을 쓴다
        assertThat(repo.resolve(".jdelta/snapshots").listDirectoryEntries()).hasSize(1)
        assertThat(git("worktree", "list").lines()).hasSize(1)
    }

    @Test
    fun `baseline directory from snapshot --out`() {
        initRepo()
        build()
        val exported = Files.createTempDirectory("jdelta-artifact").resolve("snapshot")
        assertThat(jdelta("snapshot", "--out", exported.toString()).first).isEqualTo(ExitCode.OK)
        assertThat(exported.resolve("manifest.json").exists()).isTrue()

        branchWith("package com.acme;\npublic class Price { public long total(int qty) { return qty * 110L; } public long extra() { return 1; } }")
        val (code, out, _) = jdelta("diff", "main", "--baseline-dir", exported.toString(), "--format", "json")
        assertThat(code).isEqualTo(ExitCode.OK)
        assertThat(out).contains("\"METHOD_ADDED\"").contains("\"resolution\": \"merge-base\"")
    }

    @Test
    fun `comment-only source change and build script change are reported`() {
        initRepo()
        build()
        jdelta("snapshot")
        branchWith("package com.acme;\n// pricing rules\npublic class Price {\n  /** total */\n  public long total(int qty) { return qty * 100L; }\n}")
        write("app/build.gradle.kts", "plugins { java }\ntasks.withType<JavaCompile> { options.release.set(21) }")

        val (code, out, err) = jdelta("diff", "main")
        assertThat(code).describedAs(err).isEqualTo(ExitCode.OK)
        assertThat(out).contains("SOURCE_ONLY_CHANGED").contains("BUILD_CONFIGURATION_CHANGED")
        assertThat(out).doesNotContain("METHOD_BODY_CHANGED")
    }

    @Test
    fun `dirty working tree is not stored without --out`() {
        initRepo()
        build()
        write(price, "package com.acme;\npublic class Price { public long total(int qty) { return qty * 100L; } }\n// dirty")
        val (code, _, err) = jdelta("snapshot", "--skip-staleness-check")
        assertThat(code).isEqualTo(ExitCode.USAGE)
        assertThat(err).contains("--out")
    }

    @Test
    fun `revision range parsing`() {
        assertThat(RevisionRange.parse(null)).isEqualTo(RevisionRange(null, null))
        assertThat(RevisionRange.parse("main")).isEqualTo(RevisionRange("main", null))
        assertThat(RevisionRange.parse("main..HEAD")).isEqualTo(RevisionRange("main", "HEAD"))
        assertThat(RevisionRange.parse("main...feature")).isEqualTo(RevisionRange("main", "feature"))
        assertThat(RevisionRange.parse("..HEAD")).isEqualTo(RevisionRange(null, "HEAD"))
    }
}
