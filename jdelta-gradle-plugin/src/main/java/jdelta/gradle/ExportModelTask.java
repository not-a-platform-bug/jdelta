package jdelta.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** module별 JSON을 모아 `.jdelta/model/project.json`을 쓴다. */
@DisableCachingByDefault(because = "the output lives outside the build directory and is cheap to produce")
public abstract class ExportModelTask extends DefaultTask {
    public static final int FORMAT_VERSION = 1;

    @InputFiles
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract ConfigurableFileCollection getModuleFiles();

    @Input
    public abstract Property<String> getRootDir();

    @Input
    public abstract Property<String> getGradleVersion();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void export() throws IOException {
        List<File> files = new ArrayList<>(getModuleFiles().getFiles());
        files.sort(Comparator.comparing(File::getPath));
        List<String> modules = new ArrayList<>();
        for (File file : files) {
            modules.add(Files.readString(file.toPath(), StandardCharsets.UTF_8).strip());
        }
        String json = "{\n"
            + "  \"formatVersion\": " + FORMAT_VERSION + ",\n"
            + "  \"gradleVersion\": " + Json.string(getGradleVersion().get()) + ",\n"
            + "  \"rootDir\": " + Json.string(getRootDir().get()) + ",\n"
            + "  \"modules\": [\n    " + String.join(",\n    ", modules) + "\n  ]\n"
            + "}\n";
        var target = getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(target.getParent());
        Files.writeString(target, json, StandardCharsets.UTF_8);
    }
}
