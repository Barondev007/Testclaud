package be.bnppf.openapi.validator;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ValidationResultTest {

    @Test
    void testDefaultConstructor() {
        ValidationResult result = new ValidationResult();

        assertTrue(result.isValid());
        assertFalse(result.isBlocked());
        assertTrue(result.getErrors().isEmpty());
        assertTrue(result.getWarnings().isEmpty());
        assertTrue(result.getAllMessages().isEmpty());
        assertNull(result.getDebugInfo());
        assertNull(result.getValidationType());
        assertNull(result.getValidationLevel());
    }

    @Test
    void testSettersAndGetters() {
        ValidationResult result = new ValidationResult();

        result.setValid(false);
        assertFalse(result.isValid());

        result.setBlocked(true);
        assertTrue(result.isBlocked());

        result.setValidationType("request");
        assertEquals("request", result.getValidationType());

        result.setValidationLevel(ValidationLevel.LENIENT);
        assertEquals(ValidationLevel.LENIENT, result.getValidationLevel());

        result.setDebugInfo("debug info");
        assertEquals("debug info", result.getDebugInfo());

        result.setErrors(Arrays.asList("error1", "error2"));
        assertEquals(2, result.getErrors().size());

        result.setWarnings(Arrays.asList("warning1"));
        assertEquals(1, result.getWarnings().size());

        result.setAllMessages(Arrays.asList("msg1", "msg2", "msg3"));
        assertEquals(3, result.getAllMessages().size());
    }

    @Test
    void testGetErrorCount() {
        ValidationResult result = new ValidationResult();
        assertEquals(0, result.getErrorCount());

        result.setErrors(Arrays.asList("error1", "error2", "error3"));
        assertEquals(3, result.getErrorCount());
    }

    @Test
    void testGetErrorsAsString() {
        ValidationResult result = new ValidationResult();
        assertEquals("", result.getErrorsAsString());

        result.setErrors(Arrays.asList("error1", "error2"));
        assertEquals("error1; error2", result.getErrorsAsString());
    }

    @Test
    void testGetAllMessagesAsString() {
        ValidationResult result = new ValidationResult();
        assertEquals("", result.getAllMessagesAsString());

        result.setAllMessages(Arrays.asList("msg1", "msg2", "msg3"));
        assertEquals("msg1; msg2; msg3", result.getAllMessagesAsString());
    }

    @Test
    void testToString() {
        ValidationResult result = new ValidationResult();
        result.setValid(true);
        result.setBlocked(false);
        result.setValidationType("request");
        result.setValidationLevel(ValidationLevel.STRICT);
        result.setErrors(Arrays.asList("error1"));

        String str = result.toString();
        assertTrue(str.contains("valid=true"));
        assertTrue(str.contains("blocked=false"));
        assertTrue(str.contains("validationType='request'"));
        assertTrue(str.contains("level=STRICT"));
        assertTrue(str.contains("errorCount=1"));
    }

    @Test
    void testValidResultForRequest() {
        ValidationResult result = new ValidationResult();
        result.setValid(true);
        result.setBlocked(false);
        result.setValidationType("request");
        result.setValidationLevel(ValidationLevel.STRICT);

        assertTrue(result.isValid());
        assertFalse(result.isBlocked());
        assertEquals("request", result.getValidationType());
        assertEquals(0, result.getErrorCount());
    }

    @Test
    void testInvalidResultForResponse() {
        ValidationResult result = new ValidationResult();
        result.setValid(false);
        result.setBlocked(true);
        result.setValidationType("response");
        result.setValidationLevel(ValidationLevel.STRICT);
        result.setErrors(Arrays.asList("[validation.error] Missing required field"));

        assertFalse(result.isValid());
        assertTrue(result.isBlocked());
        assertEquals("response", result.getValidationType());
        assertEquals(1, result.getErrorCount());
        assertTrue(result.getErrorsAsString().contains("Missing required field"));
    }

    @Test
    void testLightModeNotBlocked() {
        // In LIGHT mode, even with errors, blocked should be false
        ValidationResult result = new ValidationResult();
        result.setValid(false);
        result.setBlocked(false); // LIGHT mode doesn't block
        result.setValidationLevel(ValidationLevel.LIGHT);
        result.setErrors(Arrays.asList("error1", "error2"));

        assertFalse(result.isValid());
        assertFalse(result.isBlocked()); // Not blocked in LIGHT mode
        assertEquals(2, result.getErrorCount());
    }
}
