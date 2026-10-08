package jdelta.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * test JVM 안의 method 실행 기록기 (ARCHITECTURE.md §8.2, §8.3).
 *
 * <p>bootstrap classloader에 올라가므로 instrument된 application class와 JUnit listener가 같은 인스턴스를 본다.
 * {@link #hit(int)}는 hot path다. 처음 실행된 method만 lock을 잡고, 그 뒤로는 배열 읽기 하나로 끝난다.
 * 배열은 고정 크기 chunk로 나누어 늘린다. 늘릴 때 기존 chunk를 복사하지 않으므로 다른 thread의 기록이 사라지지 않는다.
 */
public final class Recorder {
    private static final int SHIFT = 12;
    private static final int CHUNK = 1 << SHIFT;
    private static final int MASK = CHUNK - 1;

    private static volatile boolean[][] chunks = new boolean[0][];
    private static final List<String> methods = new ArrayList<>();

    private static int[] touched = new int[1024];
    private static int touchedSize;

    private static final Scope ambient = new Scope("AMBIENT", null);
    private static final Map<String, Scope> scopes = new LinkedHashMap<>();
    private static final Deque<Scope> classScopes = new ArrayDeque<>();
    private static final List<Scope> activeTests = new ArrayList<>();
    private static boolean contaminated;

    private static TraceSettings settings;
    private static boolean written;

    private Recorder() {
    }

    /** agent가 premain에서 한 번 부른다. */
    public static synchronized void configure(TraceSettings traceSettings) {
        settings = traceSettings;
        Runtime.getRuntime().addShutdownHook(new Thread(Recorder::writeQuietly, "jdelta-trace-writer"));
    }

    public static synchronized boolean isConfigured() {
        return settings != null;
    }

    /** transform 시점에 method를 등록하고 index를 받는다. class가 실행되기 전이므로 hit보다 먼저다. */
    public static synchronized int register(String canonicalMethodId) {
        int index = methods.size();
        methods.add(canonicalMethodId);
        int chunk = index >>> SHIFT;
        if (chunk >= chunks.length) {
            boolean[][] grown = new boolean[chunk + 1][];
            System.arraycopy(chunks, 0, grown, 0, chunks.length);
            for (int i = chunks.length; i < grown.length; i++) grown[i] = new boolean[CHUNK];
            chunks = grown;
        }
        return index;
    }

    /** instrument된 모든 method의 첫 명령. */
    public static void hit(int index) {
        boolean[] chunk = chunks[index >>> SHIFT];
        int i = index & MASK;
        if (!chunk[i]) {
            chunk[i] = true;
            touched(index);
        }
    }

    private static synchronized void touched(int index) {
        if (touchedSize == touched.length) {
            int[] grown = new int[touched.length * 2];
            System.arraycopy(touched, 0, grown, 0, touchedSize);
            touched = grown;
        }
        touched[touchedSize++] = index;
    }

    /** 지금까지의 hit을 현재 scope에 귀속하고 지운다. 실행 중인 test가 여럿이면(병렬) 모두에 귀속한다 (§8.4). */
    private static void flush() {
        List<Scope> targets = !activeTests.isEmpty() ? activeTests : List.of(classScopes.isEmpty() ? ambient : classScopes.peek());
        boolean[][] current = chunks;
        for (int t = 0; t < touchedSize; t++) {
            int index = touched[t];
            for (Scope scope : targets) scope.hits.set(index);
            current[index >>> SHIFT][index & MASK] = false;
        }
        touchedSize = 0;
    }

    /** test class container 시작. 첫 test 전까지와 test 사이의 실행(@BeforeAll, context 로딩)은 CLASS_SETUP이다. */
    public static synchronized void beginClass(String testId) {
        flush();
        classScopes.push(scope("CLASS_SETUP", testId));
    }

    public static synchronized void endClass(String testId) {
        flush();
        classScopes.removeIf(s -> testId.equals(s.test));
    }

    public static synchronized void beginTest(String testId) {
        flush();
        if (!activeTests.isEmpty()) contaminated = true;
        Scope scope = scope("TEST", testId);
        scope.startedNanos = System.nanoTime();
        activeTests.add(scope);
    }

    /** [status]는 JUnit의 SUCCESSFUL, FAILED, ABORTED. 같은 test(예: parameterized invocation)는 합친다. */
    public static synchronized void endTest(String testId, String status) {
        flush();
        Scope scope = scopes.get("TEST|" + testId);
        if (scope == null) return;
        activeTests.remove(scope);
        scope.durationNanos += System.nanoTime() - scope.startedNanos;
        scope.status = worst(scope.status, status);
    }

    private static Scope scope(String kind, String testId) {
        return scopes.computeIfAbsent(kind + "|" + testId, k -> new Scope(kind, testId));
    }

    private static String worst(String a, String b) {
        if (a == null) return b;
        if ("FAILED".equals(a) || "FAILED".equals(b)) return "FAILED";
        if ("ABORTED".equals(a) || "ABORTED".equals(b)) return "ABORTED";
        return a;
    }

    /** listener가 test plan 종료 시 부른다. 실패하면 shutdown hook이 다시 시도한다. */
    public static synchronized void write() throws IOException {
        if (settings == null || written) return;
        flush();
        Path target = settings.outputFile();
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, toJson(), StandardCharsets.UTF_8);
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        written = true;
    }

    private static void writeQuietly() {
        try {
            write();
        } catch (IOException | RuntimeException e) {
            System.err.println("jdelta: failed to write trace: " + e);
        }
    }

    /** raw trace 형식 (§8.5). agent와 jdelta-trace 사이의 유일한 계약이다. */
    static synchronized String toJson() {
        BitSet testHits = new BitSet();
        BitSet setupHits = (BitSet) ambient.hits.clone();
        for (Scope s : scopes.values()) {
            if (s.kind.equals("TEST")) testHits.or(s.hits);
            else setupHits.or(s.hits);
        }
        BitSet oneTime = (BitSet) setupHits.clone();
        oneTime.andNot(testHits);
        for (int i = 0; i < methods.size(); i++) {
            if (methods.get(i).contains("#<clinit>(")) oneTime.set(i);
        }

        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\n");
        sb.append("  \"formatVersion\": 1,\n");
        sb.append("  \"runId\": ").append(Json.string(settings.runId())).append(",\n");
        sb.append("  \"forkId\": ").append(Json.string(settings.forkId())).append(",\n");
        sb.append("  \"commit\": ").append(Json.string(settings.commit())).append(",\n");
        sb.append("  \"classpathFingerprint\": ").append(Json.string(settings.classpathFingerprint())).append(",\n");
        sb.append("  \"contaminated\": ").append(contaminated).append(",\n");
        sb.append("  \"methods\": [");
        for (int i = 0; i < methods.size(); i++) {
            sb.append(i == 0 ? "\n    " : ",\n    ").append(Json.string(methods.get(i)));
        }
        sb.append(methods.isEmpty() ? "],\n" : "\n  ],\n");
        sb.append("  \"scopes\": [");
        boolean first = true;
        for (Scope s : scopes.values()) {
            sb.append(first ? "\n    " : ",\n    ");
            first = false;
            sb.append("{\"kind\": ").append(Json.string(s.kind)).append(", \"test\": ").append(Json.string(s.test))
                .append(", \"hits\": ").append(Json.ints(s.hits));
            if (s.kind.equals("TEST")) {
                sb.append(", \"durationMs\": ").append(s.durationNanos / 1_000_000)
                    .append(", \"status\": ").append(Json.string(s.status == null ? "UNKNOWN" : s.status));
            }
            sb.append('}');
        }
        sb.append(first ? "],\n" : "\n  ],\n");
        sb.append("  \"ambient\": ").append(Json.ints(ambient.hits)).append(",\n");
        sb.append("  \"oneTimeInit\": ").append(Json.ints(oneTime)).append('\n');
        sb.append("}\n");
        return sb.toString();
    }

    /** test 전용: 상태를 처음으로 되돌린다. */
    static synchronized void reset(TraceSettings traceSettings) {
        chunks = new boolean[0][];
        methods.clear();
        touchedSize = 0;
        ambient.hits.clear();
        scopes.clear();
        classScopes.clear();
        activeTests.clear();
        contaminated = false;
        settings = traceSettings;
        written = false;
    }

    private static final class Scope {
        final String kind;
        final String test;
        final BitSet hits = new BitSet();
        long startedNanos;
        long durationNanos;
        String status;

        Scope(String kind, String test) {
            this.kind = kind;
            this.test = test;
        }
    }
}
