package jdelta.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import kotlin.io.path.Path

/**
 * `jdelta snapshot`: 현재 build output을 snapshot으로 저장한다 (ARCHITECTURE.md §4.3).
 * 권장 CI 흐름은 main build마다 `jdelta snapshot --out <dir>`을 artifact로 보관하고 PR에서 `--baseline-dir`로 쓰는 것이다.
 */
internal class SnapshotCommand : CoreCliktCommand(name = "snapshot") {
    override fun help(context: Context): String = """
        Store the current build output as the snapshot of HEAD in .jdelta/snapshots/<sha>.
        With --out, write a self-contained snapshot directory instead (usable as --baseline-dir).
    """.trimIndent()

    private val projectDir by option("--project-dir", metavar = "DIR", help = "directory inside the git repository (default: current directory)").default(".")
    private val out by option("--out", metavar = "DIR", help = "write the snapshot to DIR (must be empty or absent)")
    private val build by option("--build", help = "build the project first").flag()
    private val buildCommand by option("--build-command", metavar = "CMD", help = "command for --build (default: ./gradlew -q classes testClasses)")
    private val skipStaleness by option("--skip-staleness-check", help = "store even if build output is older than changed sources").flag()
    private val modelSource by modelOption()

    override fun run() {
        val workflow = GitWorkflow(Path(projectDir), modelSource, log = { echo(it, err = true) })
        if (build) workflow.build(buildCommand)
        val dir = workflow.snapshot(out?.let { Path(it) }, checkStaleness = !skipStaleness)
        echo("snapshot written to $dir")
    }
}
