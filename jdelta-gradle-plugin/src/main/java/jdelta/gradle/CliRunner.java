package jdelta.gradle;

import org.gradle.api.file.FileCollection;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** jdelta CLI를 별도 JVM으로 실행한다 (D13). */
final class CliRunner {
    record Result(int exitCode, String stdout, String stderr) {
    }

    private CliRunner() {
    }

    static Result run(ExecOperations exec, FileCollection classpath, List<String> args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ExecResult result = exec.javaexec(spec -> {
            spec.classpath(classpath);
            spec.getMainClass().set("jdelta.cli.MainKt");
            spec.args(args);
            spec.setStandardOutput(out);
            spec.setErrorOutput(err);
            spec.setIgnoreExitValue(true);
        });
        return new Result(result.getExitValue(), out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
