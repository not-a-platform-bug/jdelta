package jdelta.gradle;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.testing.Test;
import org.gradle.process.CommandLineArgumentProvider;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code -Pjdelta.record=true}일 때 모든 {@link Test} task에 trace 수집을 붙인다 (ARCHITECTURE.md §9.1).
 *
 * <ul>
 *   <li>{@code -javaagent:<jdelta-agent.jar>=config=<file>}</li>
 *   <li>{@code jdelta-junit} jar를 test source set의 runtimeOnly에 추가({@link #addListener})</li>
 *   <li>Jupiter 병렬 실행 비활성화</li>
 *   <li>record run ID를 task input으로 추가. 그렇지 않으면 test가 UP-TO-DATE/FROM-CACHE로 건너뛰어 trace가 남지 않는다.</li>
 * </ul>
 */
final class RecordMode {
    static final String ENABLED = "jdelta.record";

    private RecordMode() {
    }

    /**
     * listener jar를 test source set의 runtimeOnly에 더한다. Test task의 classpath를 직접 바꾸면
     * jvm-test-suite가 lazy하게 정하는 classpath convention을 덮어써서 JUnit Platform launcher가 빠진다.
     */
    static void addListener(Project project) {
        String junitJar = required(project, "jdelta.junitJar");
        project.getPlugins().withType(org.gradle.api.plugins.JavaPlugin.class, plugin -> {
            SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
            sourceSets.configureEach(sourceSet -> {
                if (sourceSet.getName().equals(SourceSet.MAIN_SOURCE_SET_NAME)) return;
                project.getDependencies().add(sourceSet.getRuntimeOnlyConfigurationName(), project.files(junitJar));
            });
        });
    }

    static boolean enabled(Project project) {
        return "true".equals(project.getProviders().gradleProperty(ENABLED).getOrNull());
    }

    static void configure(Project project, Test test) {
        String agentJar = required(project, "jdelta.agentJar");
        String runId = required(project, "jdelta.runId");
        String output = required(project, "jdelta.traceOutput");
        String commit = project.getProviders().gradleProperty("jdelta.commit").getOrElse("");

        File config = project.getLayout().getBuildDirectory().file("jdelta/agent-" + test.getName() + ".properties").get().getAsFile();
        String content = "runId=" + escape(runId) + "\n"
            + "output=" + escape(output) + "\n"
            + (commit.isEmpty() ? "" : "commit=" + escape(commit) + "\n")
            + outputDirs(project.getRootProject());

        test.getInputs().property("jdelta.runId", runId);
        test.systemProperty("junit.jupiter.execution.parallel.enabled", "false");
        test.getJvmArgumentProviders().add(new AgentArgument(agentJar, config.getAbsolutePath()));
        test.doFirst(new WriteConfig(config, content));
    }

    /** 모든 project의 class output과 jar를 module에 매핑한다. 다른 module의 class는 보통 jar로 test classpath에 들어온다. */
    private static String outputDirs(Project root) {
        List<String> lines = new ArrayList<>();
        for (Project p : root.getAllprojects()) {
            List<File> dirs = new ArrayList<>();
            SourceSetContainer sourceSets = p.getExtensions().findByType(SourceSetContainer.class);
            if (sourceSets != null) {
                for (SourceSet sourceSet : sourceSets) dirs.addAll(sourceSet.getOutput().getClassesDirs().getFiles());
            }
            p.getTasks().withType(Jar.class).forEach(jar -> {
                if (jar.getName().equals("jar")) dirs.add(jar.getArchiveFile().get().getAsFile());
            });
            for (File dir : dirs) {
                int i = lines.size();
                lines.add("dir." + i + "=" + escape(dir.getAbsolutePath()) + "\nmodule." + i + "=" + escape(p.getPath()));
            }
        }
        return String.join("\n", lines) + "\n";
    }

    private static String required(Project project, String name) {
        String value = project.getProviders().gradleProperty(name).getOrNull();
        if (value == null || value.isBlank()) throw new IllegalStateException("-P" + name + " is required when -P" + ENABLED + "=true (use 'jdelta record')");
        return value;
    }

    /** {@link java.util.Properties} 형식의 escape. Windows 경로의 backslash를 지킨다. */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace(":", "\\:").replace("=", "\\=");
    }

    static final class AgentArgument implements CommandLineArgumentProvider, Serializable {
        private final String agentJar;
        private final String config;

        AgentArgument(String agentJar, String config) {
            this.agentJar = agentJar;
            this.config = config;
        }

        @Input
        public String getAgentJar() {
            return agentJar;
        }

        @Override
        public Iterable<String> asArguments() {
            return List.of("-javaagent:" + agentJar + "=config=" + config);
        }
    }

    /** configuration cache에 저장될 수 있도록 Project를 잡지 않는 action. */
    static final class WriteConfig implements Action<Task>, Serializable {
        private final File file;
        private final String content;

        WriteConfig(File file, String content) {
            this.file = file;
            this.content = content;
        }

        @Override
        public void execute(Task task) {
            try {
                Files.createDirectories(file.toPath().getParent());
                Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
