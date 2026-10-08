package jdelta.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.work.DisableCachingByDefault;

import javax.inject.Inject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** phase 1/2 결과를 {@code jdelta verify}로 넘긴다. phase 1이 실패했으면 build를 실패시킨다. */
@DisableCachingByDefault(because = "summarizes test results and appends metrics")
public abstract class VerifyTask extends DefaultTask {
    @Classpath
    public abstract ConfigurableFileCollection getCliClasspath();

    @Input
    public abstract Property<String> getRootDir();

    @Internal
    public abstract RegularFileProperty getImpactedFile();

    @Internal
    public abstract ConfigurableFileCollection getPhase1Results();

    @Internal
    public abstract ConfigurableFileCollection getPhase2Results();

    @Inject
    protected abstract ExecOperations getExecOperations();

    public VerifyTask() {
        getOutputs().upToDateWhen(task -> false);
    }

    @TaskAction
    public void verify() {
        List<String> args = new ArrayList<>(List.of("verify", "--project-dir", getRootDir().get(), "--impacted", getImpactedFile().get().getAsFile().getAbsolutePath()));
        for (File dir : getPhase1Results().getFiles()) args.addAll(List.of("--phase1", dir.getAbsolutePath()));
        for (File dir : getPhase2Results().getFiles()) args.addAll(List.of("--phase2", dir.getAbsolutePath()));
        CliRunner.Result result = CliRunner.run(getExecOperations(), getCliClasspath(), args);
        if (!result.stdout().isBlank()) getLogger().lifecycle(result.stdout().strip());
        if (result.exitCode() != 0) throw new GradleException(result.stderr().strip().isEmpty() ? "jdelta verify failed" : result.stderr().strip());
    }
}
