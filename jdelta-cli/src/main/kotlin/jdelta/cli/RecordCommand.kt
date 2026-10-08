package jdelta.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import jdelta.engine.Workspace
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.name

/**
 * `jdelta record -- ./gradlew test`: test를 agent와 함께 실행하고 trace store를 갱신한다 (ARCHITECTURE.md §8, §10).
 *
 * Gradle command면 init script로 plugin을 주입하고 `-Pjdelta.record=true`로 모든 Test task에 agent를 붙인다.
 * 그 밖의 command(`JAVA_TOOL_OPTIONS`를 쓰는 generic mode)는 아직 지원하지 않는다.
 */
internal class RecordCommand : CoreCliktCommand(name = "record") {
    override fun help(context: Context): String = """
        Run a Gradle test command with the jdelta agent and merge the traces into .jdelta/traces/store.json.

        Example: jdelta record -- ./gradlew test
    """.trimIndent()

    private val projectDir by option("--project-dir", metavar = "DIR", help = "directory to run the command in (default: current directory)").default(".")
    private val command by argument("COMMAND", help = "the Gradle test command, after --").multiple(required = true)

    override fun run() {
        val workDir = Path(projectDir).absolute().normalize()
        val executable = Path(command.first()).name
        if (executable !in GRADLE_COMMANDS) {
            throw JDeltaFailure("only Gradle commands are supported in v0.1 (got '${command.first()}'); use: jdelta record -- ./gradlew test", ExitCode.USAGE)
        }
        val root = try {
            Git(workDir).topLevel()
        } catch (e: JDeltaFailure) {
            workDir
        }
        val commit = try {
            Git(root).resolve("HEAD")
        } catch (e: JDeltaFailure) {
            null
        }
        val pluginJar = GradleModelExport.pluginJar() ?: missing("Gradle plugin")
        val agentJar = Distribution.agentJar() ?: missing("agent")
        val junitJar = Distribution.junitJar() ?: missing("JUnit listener")
        val workspace = Workspace(root)
        val runId = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-') + "-" + UUID.randomUUID().toString().take(4)

        val args = command + listOfNotNull(
            "--init-script", GradleModelExport.initScript(root, pluginJar).toString(),
            "-Pjdelta.record=true",
            "-Pjdelta.runId=$runId",
            "-Pjdelta.agentJar=$agentJar",
            "-Pjdelta.junitJar=$junitJar",
            "-Pjdelta.traceOutput=${workspace.rawTraces}",
            commit?.let { "-Pjdelta.commit=$it" },
        )
        echo("running: ${args.joinToString(" ")}", err = true)
        val result = Processes.run(args, workDir, onOutput = { echo(it) })

        val merged = TraceMerge.merge(workspace, runId) { echo(it, err = true) }
        if (merged == 0) {
            throw JDeltaFailure("no trace was recorded (exit code ${result.exitCode}); did the command run any Test task?", if (result.exitCode != 0) result.exitCode else ExitCode.ERROR)
        }
        if (result.exitCode != 0) throw JDeltaFailure("test command failed with exit code ${result.exitCode}; traces of the executed tests were recorded", result.exitCode)
    }

    private fun missing(what: String): Nothing = throw JDeltaFailure("jdelta $what jar not found; reinstall the jdelta distribution")

    private companion object {
        val GRADLE_COMMANDS = setOf("gradlew", "gradlew.bat", "gradle", "gradle.bat")
    }
}

/** raw trace 한 run을 trace store에 병합한다. Gradle에서 직접 `-Pjdelta.record=true`로 실행한 경우에도 쓴다. */
internal object TraceMerge {
    /** 병합한 fork 수. */
    fun merge(workspace: Workspace, runId: String, log: (String) -> Unit): Int {
        val runDir = workspace.rawTraces.resolve(runId)
        val traces = jdelta.trace.RawTraceReader.readRun(runDir)
        if (traces.isEmpty()) return 0
        val merged = workspace.locked {
            val store = jdelta.trace.TraceStore.load(workspace.traceStore).merge(traces, Instant.now().toString())
            store.save(workspace.traceStore)
            runDir.toFile().deleteRecursively()
            store
        }
        val tests = traces.sumOf { t -> t.scopes.count { it.test.method != null } }
        log("recorded $tests test(s) from ${traces.size} fork(s); trace store now has ${merged.tests.size} observation(s)")
        if (traces.any { it.contaminated }) log("warning: tests overlapped (parallel execution); their traces are marked contaminated")
        return traces.size
    }
}

/** `jdelta merge-traces <runId>`: Gradle을 직접 `-Pjdelta.record=true`로 실행한 뒤 trace를 병합한다. */
internal class MergeTracesCommand : CoreCliktCommand(name = "merge-traces") {
    override fun help(context: Context): String = "Merge raw traces of a record run (.jdelta/traces/raw/<runId>) into the trace store."

    private val projectDir by option("--project-dir", metavar = "DIR", help = "directory inside the repository (default: current directory)").default(".")
    private val runId by argument("RUN_ID")

    override fun run() {
        val dir = Path(projectDir).absolute().normalize()
        val root = try {
            Git(dir).topLevel()
        } catch (e: JDeltaFailure) {
            dir
        }
        if (TraceMerge.merge(Workspace(root), runId) { echo(it, err = true) } == 0) throw JDeltaFailure("no raw traces for run $runId")
    }
}
