package jdelta.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@DisableCachingByDefault(because = "writes a small file; caching gives no benefit")
public abstract class ModuleModelTask extends DefaultTask {
    @Input
    public abstract Property<String> getModuleJson();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void write() throws IOException {
        var file = getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(file.getParent());
        Files.writeString(file, getModuleJson().get(), StandardCharsets.UTF_8);
    }
}
