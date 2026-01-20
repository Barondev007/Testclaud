package be.bnppf.openapi.validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BnppfOpenAPIValidator.
 * Uses standard Java types (Map) instead of Axway Vordel types for portability.
 */
class BnppfOpenAPIValidatorTest {

    private static final String SAMPLE_SPEC = "openapi: 3.0.0\n" +
            "info:\n" +
            "  title: Test API\n" +
            "  version: 1.0.0\n" +
            "paths:\n" +
            "  /users:\n" +
            "    get:\n" +
            "      summary: List users\n" +
            "      parameters:\n" +
            "        - name: limit\n" +
            "          in: query\n" +
            "          schema:\n" +
            "            type: integer\n" +
            "      responses:\n" +
            "        '200':\n" +
            "          description: OK\n" +
            "          content:\n" +
            "            application/json:\n" +
            "              schema:\n" +
            "                type: array\n" +
            "                items:\n" +
            "                  $ref: '#/components/schemas/User'\n" +
            "    post:\n" +
            "      summary: Create user\n" +
            "      requestBody:\n" +
            "        required: true\n" +
            "        content:\n" +
            "          application/json:\n" +
            "            schema:\n" +
            "              $ref: '#/components/schemas/User'\n" +
            "      responses:\n" +
            "        '201':\n" +
            "          description: Created\n" +
            "  /users/{id}:\n" +
            "    get:\n" +
            "      summary: Get user by ID\n" +
            "      parameters:\n" +
            "        - name: id\n" +
            "          in: path\n" +
            "          required: true\n" +
            "          schema:\n" +
            "            type: integer\n" +
            "      responses:\n" +
            "        '200':\n" +
            "          description: OK\n" +
            "          content:\n" +
            "            application/json:\n" +
            "              schema:\n" +
            "                $ref: '#/components/schemas/User'\n" +
            "        '404':\n" +
            "          description: Not Found\n" +
            "components:\n" +
            "  schemas:\n" +
            "    User:\n" +
            "      type: object\n" +
            "      required:\n" +
            "        - name\n" +
            "        - email\n" +
            "      properties:\n" +
            "        id:\n" +
            "          type: integer\n" +
            "        name:\n" +
            "          type: string\n" +
            "        email:\n" +
            "          type: string\n" +
            "          format: email\n" +
            "      additionalProperties: false\n";

    @BeforeEach
    void setUp() {
        // Clear cache before each test to ensure clean state
        BnppfOpenAPIValidator.clearCache();
    }

    /**
     * Helper method to create headers map with Content-Type
     */
    private Map<String, List<String>> createHeaders(String contentType) {
        Map<String, List<String>> headers = new HashMap<>();
        List<String> values = new ArrayList<>();
        values.add(contentType);
        headers.put("Content-Type", values);
        return headers;
    }

    /**
     * Helper method to create query params map
     */
    private Map<String, List<String>> createQueryParams(String key, String value) {
        Map<String, List<String>> params = new HashMap<>();
        List<String> values = new ArrayList<>();
        values.add(value);
        params.put(key, values);
        return params;
    }

    @Test
    void testGetInstanceCreatesValidator() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC);
        assertNotNull(validator);
        assertEquals(ValidationLevel.STRICT, validator.getValidationLevel());
    }

    @ParameterizedTest
    @EnumSource(ValidationLevel.class)
    void testGetInstanceWithDifferentLevels(ValidationLevel level) {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, level);
        assertNotNull(validator);
        assertEquals(level, validator.getValidationLevel());
    }

    @Test
    void testValidatorCaching() {
        BnppfOpenAPIValidator v1 = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);
        BnppfOpenAPIValidator v2 = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        // Should be the same cached instance
        assertSame(v1, v2);
    }

    @Test
    void testDifferentLevelsNotCachedTogether() {
        BnppfOpenAPIValidator strict = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);
        BnppfOpenAPIValidator lenient = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.LENIENT);

        // Should be different instances
        assertNotSame(strict, lenient);
    }

    @Test
    void testClearCache() {
        BnppfOpenAPIValidator v1 = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC);
        assertTrue(BnppfOpenAPIValidator.getCacheSize() > 0);

        BnppfOpenAPIValidator.clearCache();
        assertEquals(0, BnppfOpenAPIValidator.getCacheSize());

        BnppfOpenAPIValidator v2 = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC);
        // After clearing cache, should get a new instance
        assertNotSame(v1, v2);
    }

    @Test
    void testValidRequestGetUsers() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        ValidationResult result = validator.validateRequest(
                null,           // no body for GET
                "GET",
                "/users",
                null,           // no query params
                null            // no headers
        );

        assertFalse(result.isBlocked());
        assertEquals("request", result.getValidationType());
    }

    @Test
    void testValidRequestPostUser() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        String body = "{\"name\": \"John Doe\", \"email\": \"john@example.com\"}";
        Map<String, List<String>> headers = createHeaders("application/json");

        ValidationResult result = validator.validateRequest(
                body,
                "POST",
                "/users",
                null,
                headers
        );

        assertFalse(result.isBlocked());
        assertEquals("request", result.getValidationType());
    }

    @Test
    void testInvalidRequestMissingRequiredField() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        // Missing required 'email' field
        String body = "{\"name\": \"John Doe\"}";
        Map<String, List<String>> headers = createHeaders("application/json");

        ValidationResult result = validator.validateRequest(
                body,
                "POST",
                "/users",
                null,
                headers
        );

        assertTrue(result.isBlocked());
        assertFalse(result.isValid());
        assertTrue(result.getErrorCount() > 0);
    }

    @Test
    void testInvalidRequestAdditionalPropertiesStrict() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        // Body with additional property 'age' not in schema
        String body = "{\"name\": \"John Doe\", \"email\": \"john@example.com\", \"age\": 30}";
        Map<String, List<String>> headers = createHeaders("application/json");

        ValidationResult result = validator.validateRequest(
                body,
                "POST",
                "/users",
                null,
                headers
        );

        // STRICT mode should reject additional properties
        assertTrue(result.isBlocked());
    }

    @Test
    void testAdditionalPropertiesAllowedInLenientMode() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.LENIENT);

        // Body with additional property 'age' not in schema
        String body = "{\"name\": \"John Doe\", \"email\": \"john@example.com\", \"age\": 30}";
        Map<String, List<String>> headers = createHeaders("application/json");

        ValidationResult result = validator.validateRequest(
                body,
                "POST",
                "/users",
                null,
                headers
        );

        // LENIENT mode should allow additional properties
        assertFalse(result.isBlocked());
    }

    @Test
    void testLightModeDoesNotBlock() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.LIGHT);

        // Missing required 'email' field - would normally block
        String body = "{\"name\": \"John Doe\"}";
        Map<String, List<String>> headers = createHeaders("application/json");

        ValidationResult result = validator.validateRequest(
                body,
                "POST",
                "/users",
                null,
                headers
        );

        // LIGHT mode should NOT block even with errors
        assertFalse(result.isBlocked());
        // But validation should still report issues
        assertTrue(result.getAllMessages().size() > 0);
    }

    @Test
    void testValidResponseValidation() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        String body = "[{\"id\": 1, \"name\": \"John\", \"email\": \"john@example.com\"}]";
        Map<String, List<String>> headers = createHeaders("application/json");

        ValidationResult result = validator.validateResponse(
                body,
                "GET",
                "/users",
                200,
                headers
        );

        assertFalse(result.isBlocked());
        assertEquals("response", result.getValidationType());
    }

    @Test
    void testInvalidResponseWrongStatusCode() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        String body = "{\"id\": 1, \"name\": \"John\", \"email\": \"john@example.com\"}";
        Map<String, List<String>> headers = createHeaders("application/json");

        ValidationResult result = validator.validateResponse(
                body,
                "GET",
                "/users/1",
                500,    // 500 is not defined in the spec
                headers
        );

        // Undefined response code should be flagged
        assertTrue(result.isBlocked() || result.getAllMessages().size() > 0);
    }

    @Test
    void testIsValidRequestSimple() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        boolean valid = validator.isValidRequest(
                null,
                "GET",
                "/users",
                null,
                null
        );

        assertTrue(valid);
    }

    @Test
    void testIsValidResponseSimple() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        Map<String, List<String>> headers = createHeaders("application/json");

        boolean valid = validator.isValidResponse(
                "[{\"id\": 1, \"name\": \"John\", \"email\": \"john@example.com\"}]",
                "GET",
                "/users",
                200,
                headers
        );

        assertTrue(valid);
    }

    @Test
    void testDebugMode() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);
        validator.setDebugEnabled(true);

        assertTrue(validator.isDebugEnabled());

        ValidationResult result = validator.validateRequest(
                null,
                "GET",
                "/users",
                null,
                null
        );

        // Debug info should be populated when debug is enabled
        assertNotNull(result.getDebugInfo());
    }

    @Test
    void testSetPayloadLogMaxLength() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC);

        validator.setPayloadLogMaxLength(100);
        assertEquals(100, validator.getPayloadLogMaxLength());

        validator.setPayloadLogMaxLength(50);
        assertEquals(50, validator.getPayloadLogMaxLength());
    }

    @Test
    void testSetDecodeQueryParams() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC);

        assertTrue(validator.isDecodeQueryParams()); // default is true

        validator.setDecodeQueryParams(false);
        assertFalse(validator.isDecodeQueryParams());
    }

    @Test
    void testPathNotFoundValidation() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        ValidationResult result = validator.validateRequest(
                null,
                "GET",
                "/nonexistent/path",
                null,
                null
        );

        assertTrue(result.isBlocked());
        assertTrue(result.getErrorsAsString().contains("No API path found") ||
                   result.getAllMessagesAsString().contains("No API path found"));
    }

    @Test
    void testGetUsersWithQueryParams() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        Map<String, List<String>> queryParams = createQueryParams("limit", "10");

        ValidationResult result = validator.validateRequest(
                null,
                "GET",
                "/users",
                queryParams,
                null
        );

        assertFalse(result.isBlocked());
    }

    @Test
    void testGetUserById() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        ValidationResult result = validator.validateRequest(
                null,
                "GET",
                "/users/123",
                null,
                null
        );

        assertFalse(result.isBlocked());
    }
}
