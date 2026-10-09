package app.peakstone.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTest {

    @Test
    void parsesAFlatPayload() {
        Map<String, Object> m = Json.parseObject(
                """
                {"v":1,"valid":true,"status":"active","reason":null,"plan":"Network",
                 "issuedAt":1767225600,"expiresAt":1767484800,"periodEnd":1769904000}
                """);

        assertEquals(1L, m.get("v"));
        assertEquals(true, m.get("valid"));
        assertEquals("active", m.get("status"));
        assertTrue(m.containsKey("reason"));
        assertEquals(null, m.get("reason"));
        assertEquals(1769904000L, m.get("periodEnd"));
    }

    @Test
    void parsesStringEscapes() {
        Map<String, Object> m = Json.parseObject(
                "{\"s\":\"q\\\" b\\\\ s\\/ \\b\\f\\n\\r\\t \\u00e9 \\u20AC \\ud83d\\ude00\"}");

        assertEquals("q\" b\\ s/ \b\f\n\r\t é € \uD83D\uDE00", m.get("s"));
    }

    @Test
    void parsesNumbers() {
        Map<String, Object> m = Json.parseObject(
                "{\"a\":0,\"b\":-7,\"c\":9223372036854775807,\"d\":9223372036854775808,\"e\":1.5,\"f\":-2e3,\"g\":1E+2,\"h\":0.25}");

        assertEquals(0L, m.get("a"));
        assertEquals(-7L, m.get("b"));
        assertEquals(Long.MAX_VALUE, m.get("c"));
        assertEquals(9.223372036854775808E18, m.get("d"));
        assertEquals(1.5, m.get("e"));
        assertEquals(-2000.0, m.get("f"));
        assertEquals(100.0, m.get("g"));
        assertEquals(0.25, m.get("h"));
    }

    @Test
    void parsesNestedValuesSoNewServerFieldsAreHarmless() {
        Map<String, Object> m = Json.parseObject("{\"a\":{\"b\":[1,{\"c\":null},[]]},\"d\":{},\"e\":[ ]}");

        Map<?, ?> a = assertInstanceOf(Map.class, m.get("a"));
        List<?> b = assertInstanceOf(List.class, a.get("b"));
        assertEquals(3, b.size());
        assertEquals(Map.of(), m.get("d"));
        assertEquals(List.of(), m.get("e"));
    }

    @Test
    void toleratesWhitespaceAndEmptyObject() {
        assertEquals(Map.of(), Json.parseObject(" \t\r\n{ }\n "));
        assertEquals(Map.of("a", 1L), Json.parseObject("\n{\n\"a\"\t:\r1\n}\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        " ",
        "null",
        "[]",
        "\"x\"",
        "1",
        "{",
        "}",
        "{\"a\"}",
        "{\"a\":}",
        "{\"a\":1,}",
        "{,\"a\":1}",
        "{\"a\":1 \"b\":2}",
        "{a:1}",
        "{'a':1}",
        "{\"a\":1} x",
        "{\"a\":1}{\"b\":2}",
        "{\"a\":\"unterminated}",
        "{\"a\":\"bad \\x escape\"}",
        "{\"a\":\"bad \\u12 escape\"}",
        "{\"a\":\"bad \\uZZZZ escape\"}",
        "{\"a\":\"trailing backslash\\",
        "{\"a\":\"tab\there\"}",
        "{\"a\":\"newline\nhere\"}",
        "{\"a\":01}",
        "{\"a\":1.}",
        "{\"a\":.5}",
        "{\"a\":-}",
        "{\"a\":+1}",
        "{\"a\":1e}",
        "{\"a\":0x10}",
        "{\"a\":NaN}",
        "{\"a\":tru}",
        "{\"a\":True}",
        "{\"a\":nul}",
        "{\"a\":1,\"a\":2}",
        "{\"a\":null,\"a\":1}"
    })
    void rejectsInvalidJson(String text) {
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject(text));
    }

    @Test
    void rejectsExcessiveNesting() {
        String ok = "{\"a\":".repeat(15) + "1" + "}".repeat(15);
        String tooDeep = "{\"a\":".repeat(20) + "1" + "}".repeat(20);
        String deepArrays = "{\"a\":" + "[".repeat(50) + "]".repeat(50) + "}";

        Json.parseObject(ok);
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject(tooDeep));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject(deepArrays));
    }

    @Test
    void errorMessagesDoNotEchoTheInput() {
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class, () -> Json.parseObject("{\"key\":\"PS-7K3M-9QXA-2HDF-W8ZN\" oops}"));

        assertTrue(e.getMessage().contains("offset"), e.getMessage());
        assertTrue(!e.getMessage().contains("9QXA"), e.getMessage());
    }

    // ---- writer -----------------------------------------------------------------------------

    @Test
    void writesAFlatObject() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "text");
        m.put("n", 42L);
        m.put("i", 7);
        m.put("t", true);
        m.put("f", false);
        m.put("z", null);

        assertEquals("{\"s\":\"text\",\"n\":42,\"i\":7,\"t\":true,\"f\":false,\"z\":null}", Json.object(m));
        assertEquals("{}", Json.object(Map.of()));
    }

    @Test
    void escapesWhatMustBeEscaped() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a\"b", "q\" b\\ \b\f\n\r\t \u0001 \u001f é € \uD83D\uDE00 /");

        assertEquals(
                "{\"a\\\"b\":\"q\\\" b\\\\ \\b\\f\\n\\r\\t \\u0001 \\u001f é € \uD83D\uDE00 /\"}", Json.object(m));
    }

    @Test
    void whatIsWrittenCanBeReadBack() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", "PS-7K3M-9QXA-2HDF-W8ZN");
        m.put("weird", "line1\nline2\t\"quoted\" \\ back \u0000 nul € \uD83D\uDE00");
        m.put("n", -123456789012L);
        m.put("b", true);
        m.put("z", null);

        assertEquals(m, Json.parseObject(Json.object(m)));
    }

    @Test
    void rejectsValuesItCannotWrite() {
        assertThrows(IllegalArgumentException.class, () -> Json.object(Map.of("a", 1.5)));
        assertThrows(IllegalArgumentException.class, () -> Json.object(Map.of("a", List.of())));
    }
}
