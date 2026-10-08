package jdelta.runtime;

import java.util.BitSet;

/** dependency 없이 쓰는 최소 JSON writer. */
final class Json {
    private Json() {
    }

    static String string(String value) {
        if (value == null) return "null";
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    static String ints(BitSet bits) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(i);
        }
        return sb.append(']').toString();
    }
}
