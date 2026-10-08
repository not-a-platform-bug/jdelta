package jdelta.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RecorderTest {
    @BeforeEach
    void reset() {
        Recorder.reset(new TraceSettings(Path.of("unused"), "run", "fork", null, null));
    }

    @Test
    void hitsAreAttributedToTheActiveScopeAndClearedBetweenScopes() {
        int a = Recorder.register(":app|A#a()V");
        int b = Recorder.register(":app|A#b()V");
        Recorder.hit(a); // ambient
        Recorder.beginClass("junit-jupiter:ATest");
        Recorder.hit(b); // class setup
        Recorder.beginTest("junit-jupiter:ATest#one()");
        Recorder.hit(a);
        Recorder.hit(a);
        Recorder.endTest("junit-jupiter:ATest#one()", "SUCCESSFUL");
        Recorder.beginTest("junit-jupiter:ATest#two()");
        Recorder.hit(b);
        Recorder.endTest("junit-jupiter:ATest#two()", "FAILED");
        Recorder.endClass("junit-jupiter:ATest");

        String json = Recorder.toJson();
        assertThat(json).contains("\"contaminated\": false");
        assertThat(json).contains("{\"kind\": \"CLASS_SETUP\", \"test\": \"junit-jupiter:ATest\", \"hits\": [1]}");
        assertThat(json).contains("\"test\": \"junit-jupiter:ATest#one()\", \"hits\": [0]");
        assertThat(json).contains("\"test\": \"junit-jupiter:ATest#two()\", \"hits\": [1]").contains("\"status\": \"FAILED\"");
        assertThat(json).contains("\"ambient\": [0]");
        // b는 class setup에서도 보였지만 test two에서도 실행되었으므로 one-time이 아니다
        assertThat(json).contains("\"oneTimeInit\": []");
    }

    @Test
    void overlappingTestsAreContaminatedAndShareHits() {
        int a = Recorder.register(":app|A#a()V");
        Recorder.beginTest("junit-jupiter:ATest#one()");
        Recorder.beginTest("junit-jupiter:ATest#two()");
        Recorder.hit(a);
        Recorder.endTest("junit-jupiter:ATest#one()", "SUCCESSFUL");
        Recorder.endTest("junit-jupiter:ATest#two()", "SUCCESSFUL");

        String json = Recorder.toJson();
        assertThat(json).contains("\"contaminated\": true");
        assertThat(json).contains("\"test\": \"junit-jupiter:ATest#one()\", \"hits\": [0]");
        assertThat(json).contains("\"test\": \"junit-jupiter:ATest#two()\", \"hits\": [0]");
    }

    @Test
    void tableGrowsAcrossChunksWithoutLosingHits() {
        int last = 0;
        for (int i = 0; i < 10_000; i++) last = Recorder.register(":app|A#m" + i + "()V");
        Recorder.beginTest("junit-jupiter:ATest#one()");
        Recorder.hit(0);
        Recorder.hit(last);
        Recorder.endTest("junit-jupiter:ATest#one()", "SUCCESSFUL");
        assertThat(Recorder.toJson()).contains("\"hits\": [0, 9999]");
    }

    @Test
    void clinitIsAlwaysOneTimeInitialization() {
        int clinit = Recorder.register(":app|A#<clinit>()V");
        Recorder.beginTest("junit-jupiter:ATest#one()");
        Recorder.hit(clinit);
        Recorder.endTest("junit-jupiter:ATest#one()", "SUCCESSFUL");
        assertThat(Recorder.toJson()).contains("\"oneTimeInit\": [" + clinit + "]");
    }
}
