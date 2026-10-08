package jdelta.junit;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * test 경계를 {@code jdelta.runtime.Recorder}에 알린다 (ARCHITECTURE.md §8.3). ServiceLoader로 등록된다.
 *
 * <p>agent 없이 classpath에만 있으면 아무것도 하지 않는다. Recorder 호출은 {@link RecorderBridge}에 모아
 * agent가 없을 때 그 class가 load되지 않게 한다.
 */
public final class TraceListener implements TestExecutionListener {
    private static final boolean ACTIVE = recorderConfigured();

    @Override
    public void executionStarted(TestIdentifier identifier) {
        if (!ACTIVE) return;
        String id = testId(identifier);
        if (id == null) return;
        if (identifier.isTest()) RecorderBridge.beginTest(id);
        else if (isClass(identifier)) RecorderBridge.beginClass(id);
    }

    @Override
    public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
        if (!ACTIVE) return;
        String id = testId(identifier);
        if (id == null) return;
        if (identifier.isTest()) RecorderBridge.endTest(id, result.getStatus().name());
        else if (isClass(identifier)) RecorderBridge.endClass(id);
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        if (ACTIVE) RecorderBridge.write();
    }

    private static boolean isClass(TestIdentifier identifier) {
        return identifier.isContainer() && identifier.getSource().orElse(null) instanceof ClassSource;
    }

    /**
     * NodeId의 test 형식(§2.1): {@code <engine>:<class>} 또는 {@code <engine>:<class>#<method>(<parameter types>)}.
     * parameterized/dynamic test의 invocation은 소유 method 하나로 모인다.
     */
    static String testId(TestIdentifier identifier) {
        String engine = identifier.getUniqueIdObject().getEngineId().orElse("unknown");
        TestSource source = identifier.getSource().orElse(null);
        if (source instanceof MethodSource m) {
            return engine + ":" + m.getClassName() + "#" + m.getMethodName() + "(" + m.getMethodParameterTypes() + ")";
        }
        if (source instanceof ClassSource c) return engine + ":" + c.getClassName();
        return null;
    }

    private static boolean recorderConfigured() {
        try {
            Class<?> recorder = Class.forName("jdelta.runtime.Recorder", false, null); // bootstrap에만 있어야 한다
            return (Boolean) recorder.getMethod("isConfigured").invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }
}
