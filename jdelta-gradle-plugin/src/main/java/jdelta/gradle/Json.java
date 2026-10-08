package jdelta.gradle;

import java.util.Collection;
import java.util.stream.Collectors;

/** dependency 없이 쓰는 최소 JSON writer. */
final class Json {
    private Json() {
    }

    static String string(String value) {
        StringBuilder sb = new StringBuilder("\"");
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

    static String strings(Collection<String> values) {
        return values.stream().map(Json::string).collect(Collectors.joining(", ", "[", "]"));
    }
}
