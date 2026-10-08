package jdelta.cli

import java.io.IOException
import java.nio.file.Path
import kotlin.concurrent.thread

internal data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

internal object Processes {
    /** stdout과 stderr를 따로 모은다. 한쪽 buffer가 차서 멈추지 않도록 stderr는 별도 thread가 읽는다. */
    fun run(command: List<String>, workDir: Path, onOutput: ((String) -> Unit)? = null): ProcessResult {
        val process = try {
            ProcessBuilder(command).directory(workDir.toFile()).start()
        } catch (e: IOException) {
            throw JDeltaFailure("cannot run '${command.first()}': ${e.message}")
        }
        process.outputStream.close()
        val stderr = StringBuilder()
        val errReader = thread(isDaemon = true, name = "jdelta-stderr") {
            process.errorStream.bufferedReader().forEachLine { line ->
                synchronized(stderr) { stderr.appendLine(line) }
                onOutput?.invoke(line)
            }
        }
        val stdout = StringBuilder()
        process.inputStream.bufferedReader().forEachLine { line ->
            stdout.appendLine(line)
            onOutput?.invoke(line)
        }
        val code = process.waitFor()
        errReader.join()
        return ProcessResult(code, stdout.toString(), synchronized(stderr) { stderr.toString() })
    }
}
