package jdelta.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** sample multi-module project에서 api/implementation 구분이 export되는지 확인한다 (ARCHITECTURE.md §14 12단계). */
class ModelExportTest {
    @TempDir
    Path project;

    private void write(String path, String content) throws IOException {
        Path file = project.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** web -implementation-> service -api-> core, web의 test는 core를 직접 쓴다. */
    private void sampleProject(String pluginsLine) throws IOException {
        write("settings.gradle.kts", "rootProject.name = \"sample\"\ninclude(\"core\", \"service\", \"web\")\n");
        write("build.gradle.kts", pluginsLine.isEmpty() ? "" : "plugins { " + pluginsLine + " }\n");
        write("core/build.gradle.kts", "plugins { `java-library`" + (pluginsLine.isEmpty() ? "" : "; " + pluginsLine) + " }\n");
        write("service/build.gradle.kts", "plugins { `java-library`" + (pluginsLine.isEmpty() ? "" : "; " + pluginsLine) + " }\n"
            + "dependencies { api(project(\":core\")) }\n");
        write("web/build.gradle.kts", "plugins { java" + (pluginsLine.isEmpty() ? "" : "; " + pluginsLine) + " }\n"
            + "dependencies {\n"
            + "  implementation(project(\":service\"))\n"
            + "  testImplementation(project(\":core\"))\n"
            + "  annotationProcessor(\"org.mapstruct:mapstruct-processor:1.6.3\")\n"
            + "}\n");
        write("web/src/main/java/com/acme/Web.java", "package com.acme; public class Web {}\n");
    }

    private BuildResult run(boolean pluginClasspath, String... args) {
        GradleRunner runner = GradleRunner.create().withProjectDir(project.toFile()).withArguments(List.of(args)).forwardOutput();
        return (pluginClasspath ? runner.withPluginClasspath() : runner).build();
    }

    private String module(String json, String path) {
        return json.lines().filter(l -> l.contains("\"path\": \"" + path + "\"")).findFirst().orElseThrow();
    }

    @Test
    void exportsModulesSourceSetsAndDeclaredDependencies() throws IOException {
        sampleProject("id(\"io.github.not-a-platform-bug.jdelta\")");
        BuildResult result = run(true, "jdeltaExportModel", "--configuration-cache");
        assertThat(result.task(":jdeltaExportModel").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);

        String json = Files.readString(project.resolve(".jdelta/model/project.json"));
        assertThat(json).contains("\"formatVersion\": 1").contains("\"gradleVersion\"");
        assertThat(module(json, ":service")).contains("{\"target\": \":core\", \"kind\": \"API\"}");
        String web = module(json, ":web");
        assertThat(web)
            .contains("{\"target\": \":service\", \"kind\": \"IMPLEMENTATION\"}")
            .contains("{\"target\": \":core\", \"kind\": \"TEST\"}")
            .contains("\"annotationProcessors\": [\"org.mapstruct:mapstruct-processor:1.6.3\"]")
            .contains("\"languages\": [\"JAVA\"]");
        assertThat(web).containsPattern("\"name\": \"main\", \"test\": false, \"sourceDirs\": \\[\"[^\"]*web/src/main/java\"\\], \"classesDirs\": \\[\"[^\"]*web/build/classes/java/main\"\\]");
        assertThat(web).contains("\"name\": \"test\", \"test\": true");
        assertThat(module(json, ":")).contains("\"sourceSets\": []");

        // configuration cache 재사용
        BuildResult again = run(true, "jdeltaExportModel", "--configuration-cache");
        assertThat(again.getOutput()).contains("Reusing configuration cache");
    }

    @Test
    void initScriptInjectsThePluginWithoutBuildChanges() throws IOException {
        sampleProject("");
        String jar = System.getProperty("jdelta.gradlePluginJar").replace("\\", "/");
        write("jdelta.init.gradle", "initscript { dependencies { classpath files('" + jar + "') } }\n"
            + "allprojects { apply plugin: jdelta.gradle.JDeltaPlugin }\n");
        run(false, "-q", "--init-script", project.resolve("jdelta.init.gradle").toString(), "jdeltaExportModel", "--configuration-cache");

        String json = Files.readString(project.resolve(".jdelta/model/project.json"));
        assertThat(module(json, ":service")).contains("{\"target\": \":core\", \"kind\": \"API\"}");
        assertThat(module(json, ":web")).contains("{\"target\": \":service\", \"kind\": \"IMPLEMENTATION\"}");
    }
}
