package jdelta.gradle;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code ./gradlew test -Pjdelta.impactedFirst=true} (ARCHITECTURE.md §9.2).
 *
 * <pre>
 * jdeltaImpactedTests (root)
 *    └─> :m:jdeltaImpactedFirstTest   (module별, impacted가 없으면 skip, ignoreFailures)
 *           └─> :m:test               (전체 suite)
 *                  └─> jdeltaVerify   (finalizedBy, phase 1 실패면 여기서 build 실패)
 * </pre>
 *
 * engine은 CLI를 별도 JVM({@code javaexec})으로 실행해서 쓴다. Gradle이 내장한 Kotlin stdlib와 섞이지 않는다 (D13).
 * CLI classpath는 {@code -Pjdelta.home=<배포본>} 또는 {@code -Pjdelta.cliClasspath=<경로 목록>}으로 받는다.
 */
final class ImpactedFirst {
    static final String ENABLED = "jdelta.impactedFirst";
    static final String IMPACTED_TASK = "jdeltaImpactedTests";
    static final String VERIFY_TASK = "jdeltaVerify";
    static final String PHASE1_TASK = "jdeltaImpactedFirstTest";

    private ImpactedFirst() {
    }

    static boolean enabled(Project project) {
        return "true".equals(project.getProviders().gradleProperty(ENABLED).getOrNull());
    }

    static void configureRoot(Project root, TaskProvider<ExportModelTask> export) {
        FileCollection cli = cliClasspath(root);
        Provider<RegularFile> impactedFile = root.getLayout().getBuildDirectory().file("jdelta/impacted-tests.json");
        String base = root.getProviders().gradleProperty("jdelta.base").getOrElse("");

        TaskProvider<ImpactedTestsTask> impacted = root.getTasks().register(IMPACTED_TASK, ImpactedTestsTask.class, task -> {
            task.setGroup("jdelta");
            task.setDescription("Computes the tests impacted by the changes since the base revision.");
            task.getCliClasspath().from(cli);
            task.getBase().set(base);
            task.getRootDir().set(root.getRootDir().getAbsolutePath());
            task.getModelFile().set(export.flatMap(ExportModelTask::getOutputFile));
            task.getOutputFile().set(impactedFile);
            task.dependsOn(export);
            // 분석 전에 모든 module의 class output이 최신이어야 한다(staleness 검사)
            root.allprojects(p -> task.dependsOn(p.getTasks().matching(t -> t.getName().equals("classes") || t.getName().equals("testClasses"))));
        });
        TaskProvider<VerifyTask> verify = root.getTasks().register(VERIFY_TASK, VerifyTask.class, task -> {
            task.setGroup("jdelta");
            task.setDescription("Compares impacted-first results with the full suite and records misses.");
            task.getCliClasspath().from(cli);
            task.getRootDir().set(root.getRootDir().getAbsolutePath());
            task.getImpactedFile().set(impactedFile);
        });

        root.allprojects(p -> p.getPlugins().withType(JavaPlugin.class, plugin -> configureModule(p, impacted, verify, impactedFile)));
    }

    private static void configureModule(Project project, TaskProvider<ImpactedTestsTask> impacted, TaskProvider<VerifyTask> verify, Provider<RegularFile> impactedFile) {
        boolean failFast = "true".equals(project.getProviders().gradleProperty("jdelta.failFast").getOrNull());
        TaskProvider<Test> test = project.getTasks().named(JavaPlugin.TEST_TASK_NAME, Test.class);
        TaskProvider<Test> phase1 = project.getTasks().register(PHASE1_TASK, Test.class, task -> {
            task.setGroup("verification");
            task.setDescription("Runs the impacted tests of this module before the full suite.");
            task.setTestClassesDirs(test.get().getTestClassesDirs());
            task.setClasspath(test.get().getClasspath());
            task.useJUnitPlatform();
            task.dependsOn(impacted);
            task.getInputs().file(impactedFile).withPropertyName("impactedTests");
            // phase 1이 실패해도 phase 2(전체 suite)는 계속 실행한다. 판정은 jdeltaVerify가 한다.
            task.setIgnoreFailures(!failFast);
            task.getFilter().setFailOnNoMatchingTests(false);
            File file = impactedFile.get().getAsFile();
            task.onlyIf("impacted tests exist", new HasImpactedTests(file));
            task.doFirst(new ApplyFilter(file));
        });
        test.configure(t -> {
            t.dependsOn(phase1);
            t.finalizedBy(verify);
        });
        verify.configure(v -> {
            // report 위치는 task output provider로 넘기면 configuration cache에 저장되지 않는다. 순서는 finalizedBy/mustRunAfter가 정한다.
            v.getPhase1Results().from(phase1.get().getReports().getJunitXml().getOutputLocation().get().getAsFile());
            v.getPhase2Results().from(test.get().getReports().getJunitXml().getOutputLocation().get().getAsFile());
            v.mustRunAfter(test, phase1);
        });
    }

    private static FileCollection cliClasspath(Project root) {
        String classpath = root.getProviders().gradleProperty("jdelta.cliClasspath").getOrNull();
        if (classpath != null) {
            List<File> files = new ArrayList<>();
            for (String entry : classpath.split(File.pathSeparator)) {
                if (!entry.isBlank()) files.add(new File(entry));
            }
            return root.files(files);
        }
        String home = root.getProviders().gradleProperty("jdelta.home").getOrNull();
        if (home == null) {
            throw new IllegalStateException("-P" + ENABLED + "=true needs -Pjdelta.home=<jdelta distribution> (or -Pjdelta.cliClasspath)");
        }
        return root.fileTree(new File(home, "lib"), tree -> tree.include("*.jar"));
    }

    /** CLI가 쓴 {@code impacted-tests --format json}에서 test id를 읽는다. dependency 없이 정규식으로 충분하다. */
    static List<String> impactedTests(File file) {
        List<String> tests = new ArrayList<>();
        if (!file.isFile()) return tests;
        try {
            Matcher m = TEST.matcher(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            while (m.find()) tests.add(m.group(1).replace("\\\\", "\\").replace("\\\"", "\""));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return tests;
    }

    private static final Pattern TEST = Pattern.compile("\"test\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** {@code junit-jupiter:com.acme.FooTest#works(int)} -> Gradle filter {@code com.acme.FooTest.works}. */
    static String filterOf(String testId) {
        String rest = testId.substring(testId.indexOf(':') + 1);
        int hash = rest.indexOf('#');
        if (hash < 0) return rest;
        String method = rest.substring(hash + 1);
        int paren = method.indexOf('(');
        return rest.substring(0, hash) + "." + (paren >= 0 ? method.substring(0, paren) : method);
    }

    static final class HasImpactedTests implements Spec<Task>, Serializable {
        private final File file;

        HasImpactedTests(File file) {
            this.file = file;
        }

        @Override
        public boolean isSatisfiedBy(Task task) {
            return !impactedTests(file).isEmpty();
        }
    }

    /** filter는 configuration 단계에 알 수 없으므로 실행 직전에 넣는다 (§9.2). */
    static final class ApplyFilter implements Action<Task>, Serializable {
        private final File file;

        ApplyFilter(File file) {
            this.file = file;
        }

        @Override
        public void execute(Task task) {
            Test test = (Test) task;
            for (String id : impactedTests(file)) test.getFilter().includeTestsMatching(filterOf(id));
        }
    }
}
