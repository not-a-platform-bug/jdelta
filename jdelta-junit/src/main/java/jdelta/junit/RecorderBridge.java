package jdelta.junit;

import jdelta.runtime.Recorder;

/** agent가 있을 때만 load된다. */
final class RecorderBridge {
    private RecorderBridge() {
    }

    static void beginClass(String id) {
        Recorder.beginClass(id);
    }

    static void endClass(String id) {
        Recorder.endClass(id);
    }

    static void beginTest(String id) {
        Recorder.beginTest(id);
    }

    static void endTest(String id, String status) {
        Recorder.endTest(id, status);
    }

    static void write() {
        try {
            Recorder.write();
        } catch (java.io.IOException e) {
            System.err.println("jdelta: failed to write trace: " + e);
        }
    }
}
