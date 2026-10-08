package jdelta.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * impacted-first 흐름 (ARCHITECTURE.md §9.2, §14 15단계).
 * record -> snapshot -> 변경 -> {@code test -Pjdelta.impactedFirst=true}.
 */
class ImpactedFirstTest {
    @TempDir
    Path project;

    private void write(String path, String content) throws IOException {
        Path file = project.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void git(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).describedAs(out).isZero();
    }

    private String cli(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("jdelta.cliClasspath"), "jdelta.cli.MainKt"));
        command.addAll(List.of(args));
        command.addAll(List.of("--project-dir", project.toString()));
        Process p = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).describedAs(out).isZero();
        return out;
    }

    private GradleRunner gradle(String... args) {
        return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath().withArguments(List.of(args)).forwardOutput();
    }

    private void sample() throws IOException {
        write("settings.gradle.kts", "rootProject.name = \"calc\"\n");
        write("build.gradle.kts", """
            plugins {
                java
                id("io.github.not-a-platform-bug.jdelta")
            }
            repositories { mavenCentral() }
            dependencies {
                testImplementation(platform("org.junit:junit-bom:6.1.1"))
                testImplementation("org.junit.jupiter:junit-jupiter")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
            }
            tasks.test { useJUnitPlatform() }
            """);
        write(".gitignore", "build/\n.gradle/\n.jdelta/\n");
        write("src/main/java/calc/Calc.java", """
            package calc;
            public class Calc {
                public int add(int a, int b) { return a + b; }
                public int mul(int a, int b) { return a * b; }
            }
            """);
        write("src/test/java/calc/CalcTest.java", """
            package calc;
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            class CalcTest {
                @Test void adds() { assertEquals(5, new Calc().add(2, 3)); }
                @Test void multiplies() { assertEquals(6, new Calc().mul(2, 3)); }
            }
            """);
        write("src/test/java/calc/OtherTest.java", """
            package calc;
            import org.junit.jupiter.api.Test;
            class OtherTest { @Test void unrelated() { } }
            """);
    }

    private List<String> resultFiles(String task) throws IOException {
        Path dir = project.resolve("build/test-results/" + task);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".xml")).sorted().toList();
        }
    }

    private String impactedFirst(boolean expectFailure) {
        GradleRunner runner = gradle("test", "--configuration-cache", "-Pjdelta.impactedFirst=true", "-Pjdelta.base=main",
            "-Pjdelta.cliClasspath=" + System.getProperty("jdelta.cliClasspath"));
        BuildResult result = expectFailure ? runner.buildAndFail() : runner.build();
        return result.getOutput();
    }

    @Test
    void impactedTestsRunFirstAndTheFullSuiteStillRunsWhenTheyFail() throws Exception {
        sample();
        git("init", "-q", "-b", "main");
        git("add", ".");
        git("commit", "-q", "-m", "base");

        // trace 기록과 baseline snapshot
        String raw = project.resolve(".jdelta/traces/raw").toString();
        gradle("test", "-Pjdelta.record=true", "-Pjdelta.runId=r1", "-Pjdelta.agentJar=" + System.getProperty("jdelta.agentJar"),
            "-Pjdelta.junitJar=" + System.getProperty("jdelta.junitJar"), "-Pjdelta.traceOutput=" + raw).build();
        assertThat(cli("merge-traces", "r1")).contains("recorded 3 test(s)");
        assertThat(cli("snapshot", "--model", "layout")).contains("snapshot written");

        // 1) 통과하는 변경: mul만 바뀌면 phase 1은 multiplies 하나다
        git("checkout", "-q", "-b", "feature");
        write("src/main/java/calc/Calc.java", Files.readString(project.resolve("src/main/java/calc/Calc.java")).replace("return a * b;", "int r = a * b; return r;"));
        String passing = impactedFirst(false);
        assertThat(passing).contains("jdelta: 1 impacted test(s) will run first");
        assertThat(Files.readString(project.resolve("build/test-results/jdeltaImpactedFirstTest/TEST-calc.CalcTest.xml")))
            .contains("multiplies").doesNotContain("adds");
        assertThat(passing).contains("phase 1 ran 1 impacted test(s), 0 failed; phase 2 ran 3 test(s), 0 failed");

        // 2) 깨지는 변경: phase 1이 실패해도 phase 2(OtherTest 포함)는 실행되고, 마지막에 build가 실패한다
        write("src/main/java/calc/Calc.java", Files.readString(project.resolve("src/main/java/calc/Calc.java")).replace("return a + b;", "return a - b;"));
        String failing = impactedFirst(true);
        assertThat(resultFiles("test")).contains("TEST-calc.OtherTest.xml", "TEST-calc.CalcTest.xml");
        assertThat(Files.readString(project.resolve("build/test-results/jdeltaImpactedFirstTest/TEST-calc.CalcTest.xml"))).contains("adds");
        assertThat(failing).contains("impacted tests failed: calc.CalcTest#adds()");

        List<String> runs = Files.readAllLines(project.resolve(".jdelta/metrics/runs.jsonl"));
        assertThat(runs).hasSize(2);
        assertThat(runs.get(1)).contains("\"phase1Failures\":1");
    }

    @Test
    void filterOfTestIds() {
        assertThat(ImpactedFirst.filterOf("junit-jupiter:com.acme.FooTest#works(int, java.lang.String)")).isEqualTo("com.acme.FooTest.works");
        assertThat(ImpactedFirst.filterOf("junit-jupiter:com.acme.FooTest")).isEqualTo("com.acme.FooTest");
    }

}
