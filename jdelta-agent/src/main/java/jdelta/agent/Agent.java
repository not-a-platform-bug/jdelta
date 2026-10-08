package jdelta.agent;

import jdelta.runtime.Recorder;
import jdelta.runtime.TraceSettings;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarFile;

/**
 * {@code -javaagent:jdelta-agent.jar=config=<file>} (ARCHITECTURE.md §8.1).
 *
 * <p>runtime({@code jdelta.runtime})이 bootstrap classloader에서 load되도록 중첩된 runtime jar를 bootstrap search에 더한다.
 * 그래야 application class와 test classloader의 JUnit listener가 같은 {@link Recorder}를 본다.
 */
public final class Agent {
    private Agent() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) throws Exception {
        AgentConfig config = AgentConfig.parse(agentArgs);
        if (!config.enabled()) return;

        instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(extractRuntime().toFile()));

        Recorder.configure(new TraceSettings(config.output(), config.runId(), forkId(), config.commit(), config.classpathFingerprint()));
        instrumentation.addTransformer(new ProbeTransformer(config.dirs()), false);
    }

    /**
     * agent jar 안의 {@code jdelta-runtime.jar}를 임시 파일로 꺼낸다.
     * runtime만 bootstrap에 올리고 agent class와 shaded ASM은 system classloader에 남긴다.
     */
    private static Path extractRuntime() throws IOException {
        try (InputStream in = Agent.class.getResourceAsStream("/jdelta-runtime.jar")) {
            if (in == null) throw new IOException("jdelta-runtime.jar is missing from the agent jar");
            Path file = Files.createTempFile("jdelta-runtime-", ".jar");
            file.toFile().deleteOnExit();
            Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            return file;
        }
    }

    /** Gradle test worker는 {@code org.gradle.test.worker}를 설정한다. 그 밖에는 process id를 쓴다. */
    private static String forkId() {
        String worker = System.getProperty("org.gradle.test.worker");
        return worker != null ? "gradle-test-worker-" + worker : "pid-" + ProcessHandle.current().pid();
    }
}
