package jdelta.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

/**
 * project model export (ARCHITECTURE.md §4.2).
 *
 * <p>모든 project에 {@value #MODULE_TASK}를, root project에 {@value #EXPORT_TASK}를 등록한다.
 * module task는 configuration 단계에서 계산한 JSON 문자열을 input으로 받아 파일로 쓰기만 하므로
 * execution 단계에서 {@link Project}를 참조하지 않는다(configuration cache 호환).
 */
public class JDeltaPlugin implements Plugin<Project> {
    public static final String MODULE_TASK = "jdeltaModuleModel";
    public static final String EXPORT_TASK = "jdeltaExportModel";

    @Override
    public void apply(Project project) {
        project.getTasks().register(MODULE_TASK, ModuleModelTask.class, task -> {
            task.setDescription("Writes this project's jdelta module model.");
            // task가 실현되는 시점(task graph 계산)에는 모든 build script 평가가 끝나 있다
            task.getModuleJson().set(ModuleModel.toJson(project));
            task.getOutputFile().set(project.getLayout().getBuildDirectory().file("jdelta/module.json"));
        });

        if (RecordMode.enabled(project)) {
            RecordMode.addListener(project);
            project.getTasks().withType(org.gradle.api.tasks.testing.Test.class).configureEach(test -> RecordMode.configure(project, test));
        }

        if (project != project.getRootProject()) return;
        TaskProvider<ExportModelTask> export = project.getTasks().register(EXPORT_TASK, ExportModelTask.class, task -> {
            task.setGroup("jdelta");
            task.setDescription("Exports the project model to .jdelta/model/project.json for the jdelta CLI.");
            task.getRootDir().set(project.getRootDir().getAbsolutePath());
            task.getGradleVersion().set(project.getGradle().getGradleVersion());
            task.getOutputFile().set(project.getLayout().getProjectDirectory().file(".jdelta/model/project.json"));
        });
        project.allprojects(p -> p.getPlugins().withType(JDeltaPlugin.class, plugin ->
            export.configure(task -> task.getModuleFiles().from(
                p.getTasks().named(MODULE_TASK, ModuleModelTask.class).flatMap(ModuleModelTask::getOutputFile)))));

        if (ImpactedFirst.enabled(project)) ImpactedFirst.configureRoot(project, export);
    }
}
