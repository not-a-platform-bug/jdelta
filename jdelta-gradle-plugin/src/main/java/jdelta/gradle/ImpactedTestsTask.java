package jdelta.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** {@code jdelta impacted-tests --format json}을 실행해 {@code build/jdelta/impacted-tests.json}을 쓴다. */
@DisableCachingByDefault(because = "depends on git state and the trace store outside the build")
public abstract class ImpactedTestsTask extends DefaultTask {
    @Classpath
    public abstract ConfigurableFileCollection getCliClasspath();

    @Input
    public abstract Property<String> getBase();

    @Input
    public abstract Property<String> getRootDir();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getModelFile();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @Inject
    protected abstract ExecOperations getExecOperations();

    public ImpactedTestsTask() {
        // git working tree와 trace store가 input이므로 매번 계산한다
        getOutputs().upToDateWhen(task -> false);
    }

    @TaskAction
    public void compute() throws IOException {
        List<String> args = new ArrayList<>(List.of("impacted-tests"));
        if (!getBase().get().isEmpty()) args.add(getBase().get());
        args.addAll(List.of("--format", "json", "--project-dir", getRootDir().get(), "--model-file", getModelFile().get().getAsFile().getAbsolutePath()));
        CliRunner.Result result = CliRunner.run(getExecOperations(), getCliClasspath(), args);
        var target = getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(target.getParent());
        if (result.exitCode() == 0) {
            Files.writeString(target, result.stdout(), StandardCharsets.UTF_8);
            getLogger().lifecycle("jdelta: {} impacted test(s) will run first", ImpactedFirst.impactedTests(target.toFile()).size());
        } else {
            // baseline이 없거나 build output이 낡았으면 impacted-first를 건너뛰고 전체 suite만 실행한다
            Files.writeString(target, "{\"impactedTests\": [], \"unavailable\": " + Json.string(result.stderr().strip()) + "}\n", StandardCharsets.UTF_8);
            getLogger().warn("jdelta: impacted tests unavailable (exit {}); running the full suite only.\n{}", result.exitCode(), result.stderr().strip());
        }
    }
}
