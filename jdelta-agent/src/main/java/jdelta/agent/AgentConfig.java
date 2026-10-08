package jdelta.agent;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * agent 설정 (ARCHITECTURE.md §8.2). agent argument는 {@code config=<file>} 또는 {@code enabled=false}다.
 *
 * <pre>
 * runId=2026-10-08T10-15-30Z-7f3a
 * output=/repo/.jdelta/traces/raw
 * commit=e15cd1a...
 * classpathFingerprint=9c1e...
 * dir.0=/repo/app/build/classes/java/main
 * module.0=:app
 * </pre>
 */
record AgentConfig(boolean enabled, Path output, String runId, String commit, String classpathFingerprint, List<OutputDir> dirs) {
    record OutputDir(Path path, String module) {
    }

    static AgentConfig parse(String agentArgs) throws IOException {
        String configFile = null;
        boolean enabled = true;
        if (agentArgs != null) {
            for (String part : agentArgs.split(",")) {
                int eq = part.indexOf('=');
                if (eq < 0) continue;
                String key = part.substring(0, eq).trim();
                String value = part.substring(eq + 1).trim();
                if (key.equals("config")) configFile = value;
                if (key.equals("enabled")) enabled = Boolean.parseBoolean(value);
            }
        }
        if (!enabled || configFile == null) return new AgentConfig(false, null, null, null, null, List.of());

        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of(configFile), StandardCharsets.UTF_8)) {
            p.load(reader);
        }
        List<OutputDir> dirs = new ArrayList<>();
        for (int i = 0; p.containsKey("dir." + i); i++) {
            dirs.add(new OutputDir(normalize(Path.of(p.getProperty("dir." + i))), p.getProperty("module." + i, ":")));
        }
        return new AgentConfig(
            true,
            Path.of(p.getProperty("output")),
            p.getProperty("runId", "run"),
            p.getProperty("commit"),
            p.getProperty("classpathFingerprint"),
            List.copyOf(dirs));
    }

    static Path normalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
