package be.bnppf.openapi.validator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.junit.jupiter.api.Assertions.*;

class UtilsTest {

    @Test
    void testTraceLevelEnumValues() {
        assertEquals(4, Utils.TraceLevel.values().length);
        assertNotNull(Utils.TraceLevel.DEBUG);
        assertNotNull(Utils.TraceLevel.INFO);
        assertNotNull(Utils.TraceLevel.WARN);
        assertNotNull(Utils.TraceLevel.ERROR);
    }

    @Test
    void testGetContentStartNull() {
        assertEquals("null", Utils.getContentStart(null, 10, false));
    }

    @Test
    void testGetContentStartEmpty() {
        assertEquals("(empty)", Utils.getContentStart("", 10, false));
    }

    @Test
    void testGetContentStartShortContent() {
        String content = "Hello";
        assertEquals("Hello", Utils.getContentStart(content, 10, false));
    }

    @Test
    void testGetContentStartLongContent() {
        String content = "Hello World This Is A Long String";
        assertEquals("Hello Worl...", Utils.getContentStart(content, 10, false));
    }

    @Test
    void testGetContentStartWithEscape() {
        String content = "Hello\nWorld\tTest\r";
        String result = Utils.getContentStart(content, 100, true);
        assertEquals("Hello\\nWorld\\tTest\\r", result);
    }

    @Test
    void testGetContentStartWithEscapeAndTruncate() {
        String content = "Line1\nLine2\nLine3\nLine4";
        String result = Utils.getContentStart(content, 10, true);
        assertEquals("Line1\\nLin...", result);
    }

    @ParameterizedTest
    @CsvSource({
        "123, 0, 123",
        "456, 100, 456",
        "-1, 0, -1",
        "0, 100, 0"
    })
    void testParseIntValid(String value, int defaultVal, int expected) {
        assertEquals(expected, Utils.parseInt(value, defaultVal));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void testParseIntNullOrEmpty(String value) {
        assertEquals(42, Utils.parseInt(value, 42));
    }

    @ParameterizedTest
    @CsvSource({
        "abc, 10",
        "12.34, 20",
        "12abc, 30"
    })
    void testParseIntInvalid(String value, int defaultVal) {
        assertEquals(defaultVal, Utils.parseInt(value, defaultVal));
    }

    @Test
    void testParseIntWithWhitespace() {
        assertEquals(123, Utils.parseInt("  123  ", 0));
    }

    @Test
    void testIsEmptyNull() {
        assertTrue(Utils.isEmpty(null));
    }

    @Test
    void testIsEmptyEmpty() {
        assertTrue(Utils.isEmpty(""));
    }

    @Test
    void testIsEmptyWhitespace() {
        assertTrue(Utils.isEmpty("   "));
    }

    @Test
    void testIsEmptyWithContent() {
        assertFalse(Utils.isEmpty("hello"));
        assertFalse(Utils.isEmpty("  hello  "));
    }

    @Test
    void testIsNotEmptyNull() {
        assertFalse(Utils.isNotEmpty(null));
    }

    @Test
    void testIsNotEmptyEmpty() {
        assertFalse(Utils.isNotEmpty(""));
    }

    @Test
    void testIsNotEmptyWhitespace() {
        assertFalse(Utils.isNotEmpty("   "));
    }

    @Test
    void testIsNotEmptyWithContent() {
        assertTrue(Utils.isNotEmpty("hello"));
        assertTrue(Utils.isNotEmpty("  hello  "));
    }

    @Test
    void testTraceMessageDoesNotThrow() {
        // Just verify tracing doesn't throw exceptions
        assertDoesNotThrow(() -> Utils.traceMessage("Test message", Utils.TraceLevel.DEBUG));
        assertDoesNotThrow(() -> Utils.traceMessage("Test message", Utils.TraceLevel.INFO));
        assertDoesNotThrow(() -> Utils.traceMessage("Test message", Utils.TraceLevel.WARN));
        assertDoesNotThrow(() -> Utils.traceMessage("Test message", Utils.TraceLevel.ERROR));
    }

    @Test
    void testTraceMessageWithExceptionDoesNotThrow() {
        Exception e = new RuntimeException("Test exception");
        assertDoesNotThrow(() -> Utils.traceMessage("Test message", e, Utils.TraceLevel.ERROR));
        assertDoesNotThrow(() -> Utils.traceMessage("Test message", e, Utils.TraceLevel.DEBUG));
    }

    @Test
    void testGetHeaderValuesNull() {
        assertTrue(Utils.getHeaderValues(null, "Content-Type").isEmpty());
    }
}
