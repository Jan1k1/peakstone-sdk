package app.peakstone.license;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for the Peakstone wire format. The reader accepts any well-formed JSON object
 * (so a server that later adds fields does not break old plugins) but rejects duplicate keys and
 * excessive nesting. Values come back as {@code Map}, {@code List}, {@code String}, {@code Long},
 * {@code Double}, {@code Boolean} or {@code null}. The writer handles flat objects of strings,
 * integers, booleans and nulls.
 */
final class Json {
    private static final int MAX_DEPTH = 16;

    private Json() {}

    /**
     * Parses a JSON document whose top-level value is an object.
     *
     * @throws IllegalArgumentException if the text is not valid JSON
     */
    static Map<String, Object> parseObject(String text) {
        return new Reader(text).document();
    }

    /** Writes a flat JSON object. Supported values: String, Long, Integer, Boolean, null. */
    static String object(Map<String, ?> fields) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> e : fields.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            quote(sb, e.getKey());
            sb.append(':');
            switch (e.getValue()) {
                case null -> sb.append("null");
                case String s -> quote(sb, s);
                case Long n -> sb.append(n.longValue());
                case Integer n -> sb.append(n.intValue());
                case Boolean b -> sb.append(b.booleanValue());
                default -> throw new IllegalArgumentException("unsupported JSON value type");
            }
        }
        return sb.append('}').toString();
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static final class Reader {
        private final String s;
        private int i;

        Reader(String s) {
            this.s = s;
        }

        Map<String, Object> document() {
            ws();
            if (peek() != '{') {
                throw error("expected a JSON object");
            }
            Map<String, Object> result = object(1);
            ws();
            if (i != s.length()) {
                throw error("unexpected content after the JSON object");
            }
            return result;
        }

        private Map<String, Object> object(int depth) {
            checkDepth(depth);
            i++; // '{'
            Map<String, Object> map = new LinkedHashMap<>();
            ws();
            if (peek() == '}') {
                i++;
                return map;
            }
            while (true) {
                ws();
                if (peek() != '"') {
                    throw error("expected a string key");
                }
                String key = string();
                ws();
                expect(':');
                ws();
                Object value = value(depth);
                if (map.containsKey(key)) {
                    throw error("duplicate key");
                }
                map.put(key, value);
                ws();
                int c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> array(int depth) {
            checkDepth(depth);
            i++; // '['
            List<Object> list = new ArrayList<>();
            ws();
            if (peek() == ']') {
                i++;
                return list;
            }
            while (true) {
                ws();
                list.add(value(depth));
                ws();
                int c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private Object value(int depth) {
            return switch (peek()) {
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private String string() {
            i++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw error("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 0x20) {
                    throw error("control character in string");
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) {
                    throw error("unterminated escape");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw error("truncated \\u escape");
                        }
                        int code = 0;
                        for (int k = 0; k < 4; k++) {
                            int digit = Character.digit(s.charAt(i + k), 16);
                            if (digit < 0) {
                                throw error("invalid \\u escape");
                            }
                            code = code * 16 + digit;
                        }
                        i += 4;
                        sb.append((char) code);
                    }
                    default -> throw error("invalid escape");
                }
            }
        }

        private Object number() {
            int start = i;
            if (peek() == '-') {
                i++;
            }
            if (peek() == '0') {
                i++;
            } else if (isDigit(peek())) {
                digits();
            } else {
                throw error("invalid value");
            }
            boolean integral = true;
            if (peek() == '.') {
                integral = false;
                i++;
                if (!isDigit(peek())) {
                    throw error("invalid number");
                }
                digits();
            }
            if (peek() == 'e' || peek() == 'E') {
                integral = false;
                i++;
                if (peek() == '+' || peek() == '-') {
                    i++;
                }
                if (!isDigit(peek())) {
                    throw error("invalid number");
                }
                digits();
            }
            String text = s.substring(start, i);
            if (integral) {
                try {
                    return Long.valueOf(text);
                } catch (NumberFormatException overflow) {
                    return Double.valueOf(text);
                }
            }
            return Double.valueOf(text);
        }

        private void digits() {
            while (isDigit(peek())) {
                i++;
            }
        }

        private Object literal(String word, Object value) {
            if (!s.startsWith(word, i)) {
                throw error("invalid value");
            }
            i += word.length();
            return value;
        }

        private void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                i++;
            }
        }

        /** The current character, or -1 at the end of the input. */
        private int peek() {
            return i < s.length() ? s.charAt(i) : -1;
        }

        private int next() {
            if (i >= s.length()) {
                throw error("unexpected end of input");
            }
            return s.charAt(i++);
        }

        private void expect(char c) {
            if (next() != c) {
                i--;
                throw error("expected '" + c + "'");
            }
        }

        private void checkDepth(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nesting too deep");
            }
        }

        private static boolean isDigit(int c) {
            return c >= '0' && c <= '9';
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at offset " + i);
        }
    }
}
