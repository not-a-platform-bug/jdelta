package jdelta.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.CoreNoOpCliktCommand
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import kotlin.system.exitProcess

/** exit code (ARCHITECTURE.md §10) */
internal object ExitCode {
    const val OK = 0
    const val ERROR = 1
    const val USAGE = 2
    const val BASELINE_MISSING = 3
    const val STALE_OUTPUT = 4
}

internal class JDeltaCommand : CoreNoOpCliktCommand(name = "jdelta") {
    override fun help(context: com.github.ajalt.clikt.core.Context): String =
        "JVM Semantic Delta Engine: what changed semantically, what it can affect, and the minimal safe work."
}

internal fun command(out: Appendable = System.out, err: Appendable = System.err) =
    JDeltaCommand().subcommands(DiffCommand(), ImpactedTestsCommand(), SnapshotCommand(), RecordCommand(), MergeTracesCommand(), VerifyCommand()).context {
        echoMessage = { _, message, trailingNewline, toErr ->
            val target = if (toErr) err else out
            target.append(message?.toString() ?: "")
            if (trailingNewline) target.append('\n')
        }
    }

/** 테스트에서 process를 끝내지 않고 호출할 수 있도록 exit code를 돌려준다. */
internal fun run(args: Array<String>, out: Appendable = System.out, err: Appendable = System.err): Int {
    val cmd = command(out, err)
    return try {
        cmd.parse(args)
        ExitCode.OK
    } catch (e: PrintHelpMessage) {
        out.appendLine(cmd.getFormattedHelp(e) ?: "")
        ExitCode.OK
    } catch (e: PrintMessage) {
        out.appendLine(e.message ?: "")
        e.statusCode
    } catch (e: JDeltaFailure) {
        err.appendLine("jdelta: ${e.message}")
        e.exitCode
    } catch (e: UsageError) {
        err.appendLine(cmd.getFormattedHelp(e) ?: e.message ?: "usage error")
        ExitCode.USAGE
    } catch (e: CliktError) {
        err.appendLine(cmd.getFormattedHelp(e) ?: e.message ?: "error")
        if (e.statusCode == 0) ExitCode.OK else ExitCode.ERROR
    }
}

/** project model 출처. auto는 Gradle wrapper가 있으면 init script export, 없으면 directory 관례. */
internal fun com.github.ajalt.clikt.core.ParameterHolder.modelOption() =
    option("--model", help = "where the project model comes from: auto (Gradle export when a wrapper exists), gradle, layout")
        .choice("auto" to ModelSource.AUTO, "gradle" to ModelSource.GRADLE, "layout" to ModelSource.LAYOUT)
        .default(ModelSource.AUTO)

internal class JDeltaFailure(message: String, val exitCode: Int = ExitCode.ERROR) : RuntimeException(message)

public fun main(args: Array<String>) {
    exitProcess(run(args))
}
