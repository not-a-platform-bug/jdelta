package jdelta.gradle;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.testing.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * project 하나의 model을 JSON으로 만든다. core의 {@code ModuleModel}과 같은 구조다 (ARCHITECTURE.md §4.1).
 *
 * <p>dependency는 resolve하지 않고 선언만 읽는다. resolve는 느리고, configuration 단계에서 하면 configuration cache를 무겁게 만든다.
 */
final class ModuleModel {
    private ModuleModel() {
    }

    /** 선언 configuration 이름(source set 기준 접미사) -> dependency kind. */
    private static final Map<String, String> MAIN_KINDS = new LinkedHashMap<>();

    static {
        MAIN_KINDS.put("api", "API");
        MAIN_KINDS.put("compileOnlyApi", "API");
        MAIN_KINDS.put("implementation", "IMPLEMENTATION");
        MAIN_KINDS.put("compileOnly", "COMPILE_ONLY");
        MAIN_KINDS.put("runtimeOnly", "RUNTIME_ONLY");
    }

    private static final List<String> PROCESSOR_CONFIGURATIONS = List.of("annotationProcessor", "kapt", "ksp");

    static String toJson(Project project) {
        SourceSetContainer sourceSets = project.getExtensions().findByType(SourceSetContainer.class);
        Set<File> testClassesDirs = testClassesDirs(project);

        List<String> sourceSetJson = new ArrayList<>();
        Set<String> dependencies = new LinkedHashSet<>();
        if (sourceSets != null) {
            for (SourceSet sourceSet : sourceSets) {
                Set<File> classesDirs = sourceSet.getOutput().getClassesDirs().getFiles();
                boolean main = sourceSet.getName().equals(SourceSet.MAIN_SOURCE_SET_NAME);
                boolean test = !main && (classesDirs.stream().anyMatch(testClassesDirs::contains) || sourceSet.getName().toLowerCase().contains("test"));
                sourceSetJson.add("{"
                    + "\"name\": " + Json.string(sourceSet.getName())
                    + ", \"test\": " + test
                    + ", \"sourceDirs\": " + Json.strings(paths(sourceDirs(sourceSet)))
                    + ", \"classesDirs\": " + Json.strings(paths(classesDirs))
                    + ", \"resourcesDirs\": " + Json.strings(paths(resourcesDirs(sourceSet)))
                    + "}");
                for (Map.Entry<String, String> entry : MAIN_KINDS.entrySet()) {
                    String configuration = main ? entry.getKey() : sourceSet.getName() + capitalize(entry.getKey());
                    String kind = main ? entry.getValue() : (test ? "TEST" : "IMPLEMENTATION");
                    for (String target : projectDependencies(project, configuration)) {
                        dependencies.add("{\"target\": " + Json.string(target) + ", \"kind\": " + Json.string(kind) + "}");
                    }
                }
            }
        }

        return "{"
            + "\"path\": " + Json.string(project.getPath())
            + ", \"projectDir\": " + Json.string(project.getProjectDir().getAbsolutePath())
            + ", \"languages\": " + Json.strings(languages(project))
            + ", \"sourceSets\": [" + String.join(", ", sourceSetJson) + "]"
            + ", \"dependencies\": [" + String.join(", ", dependencies) + "]"
            + ", \"annotationProcessors\": " + Json.strings(processors(project))
            + "}";
    }

    private static Set<String> languages(Project project) {
        Set<String> languages = new TreeSet<>();
        if (project.getPlugins().hasPlugin("java")) languages.add("JAVA");
        if (project.getPlugins().hasPlugin("org.jetbrains.kotlin.jvm")) languages.add("KOTLIN");
        return languages;
    }

    /** Test task가 실행하는 classes directory. 이 directory를 만드는 source set이 test source set이다. */
    private static Set<File> testClassesDirs(Project project) {
        Set<File> dirs = new LinkedHashSet<>();
        project.getTasks().withType(Test.class).forEach(test -> dirs.addAll(test.getTestClassesDirs().getFiles()));
        return dirs;
    }

    private static List<File> sourceDirs(SourceSet sourceSet) {
        List<File> dirs = new ArrayList<>(sourceSet.getJava().getSrcDirs());
        // Kotlin plugin은 source set마다 "kotlin" SourceDirectorySet extension을 붙인다
        Object kotlin = sourceSet.getExtensions().findByName("kotlin");
        if (kotlin instanceof SourceDirectorySet set) {
            for (File dir : set.getSrcDirs()) {
                if (!dirs.contains(dir)) dirs.add(dir);
            }
        }
        return dirs;
    }

    private static List<File> resourcesDirs(SourceSet sourceSet) {
        File dir = sourceSet.getOutput().getResourcesDir();
        return dir == null ? List.of() : List.of(dir);
    }

    private static List<String> projectDependencies(Project project, String configurationName) {
        Configuration configuration = project.getConfigurations().findByName(configurationName);
        if (configuration == null) return List.of();
        List<String> targets = new ArrayList<>();
        for (Dependency dependency : configuration.getDependencies()) {
            if (dependency instanceof ProjectDependency projectDependency) {
                targets.add(projectPath(projectDependency));
            }
        }
        return targets;
    }

    private static Set<String> processors(Project project) {
        Set<String> coordinates = new TreeSet<>();
        for (String name : PROCESSOR_CONFIGURATIONS) {
            Configuration configuration = project.getConfigurations().findByName(name);
            if (configuration == null) continue;
            for (Dependency dependency : configuration.getDependencies()) {
                if (dependency instanceof ExternalModuleDependency) {
                    coordinates.add(dependency.getGroup() + ":" + dependency.getName() + (dependency.getVersion() == null ? "" : ":" + dependency.getVersion()));
                } else if (dependency instanceof ProjectDependency projectDependency) {
                    coordinates.add(projectPath(projectDependency));
                }
            }
        }
        return coordinates;
    }

    /** Gradle 8.11+는 {@code getPath()}, 그 전에는 {@code getDependencyProject().getPath()}. */
    private static String projectPath(ProjectDependency dependency) {
        try {
            return dependency.getPath();
        } catch (NoSuchMethodError e) {
            try {
                Method legacy = ProjectDependency.class.getMethod("getDependencyProject");
                return ((Project) legacy.invoke(dependency)).getPath();
            } catch (ReflectiveOperationException reflective) {
                throw new IllegalStateException("cannot read project dependency path", reflective);
            }
        }
    }

    private static List<String> paths(Iterable<File> files) {
        List<String> paths = new ArrayList<>();
        files.forEach(f -> paths.add(f.getAbsolutePath()));
        return paths;
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
