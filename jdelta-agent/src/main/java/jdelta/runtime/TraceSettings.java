package jdelta.runtime;

import java.nio.file.Path;

/** 한 test JVM(fork)의 raw trace 출력 설정. */
public record TraceSettings(Path outputDir, String runId, String forkId, String commit, String classpathFingerprint) {
    /** {@code <outputDir>/<runId>/<forkId>.json} */
    public Path outputFile() {
        return outputDir.resolve(runId).resolve(forkId.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
    }
}
