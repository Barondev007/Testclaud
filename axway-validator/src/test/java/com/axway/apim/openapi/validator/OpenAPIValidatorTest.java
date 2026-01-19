package com.axway.apim.openapi.validator;

import com.vordel.mime.HeaderSet;
import com.vordel.mime.QueryStringHeaderSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class OpenAPIValidatorTest {

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
        OpenAPIValidator.clearCache();
    }

    @Test
    void testGetInstanceCreatesValidator() {
        OpenAPIValidator validator = OpenAPIValidator.getInstance(SAMPLE_SPEC);
        assertNotNull(validator);
        assertEquals(ValidationLevel.STRICT, validator.getValidationLevel());
    }

    @ParameterizedTest
    @EnumSource(ValidationLevel.class)
    void testGetInstanceWithDifferentLevels(ValidationLevel level) {
        OpenAPIValidator validator = OpenAPIValidator.getInstance(SAMPLE_SPEC, level);
        assertNotNull(validator);
        assertEquals(level, validator.getValidationLevel());
    }

    @Test
    void testValidatorCaching() {
        OpenAPIValidator v1 = OpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);
        OpenAPIValidator v2 = OpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        // Should be the same cached instance
        assertSame(v1, v2);
    }

    @Test
    void testDifferentLevelsNotCachedTogether() {
        OpenAPIValidator strict = OpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);
        OpenAPIValidator lenient = OpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.LENIENT);

        // Should be different instances
        assertNotSame(strict, lenient);
    }

    @Test
    void testClearCache() {
        OpenAPIValidator v1 = OpenAPIValidator.getInstance(SAMPLE_SPEC);
        assertTrue(OpenAPIValidator.getCacheSize() > 0);

        OpenAPIValidator.clearCache();
        assertEquals(0, OpenAPIValidator.getCacheSize());

        OpenAPIValidator v2 = OpenAPIValidator.getInstance(SAMPLE_SPEC);
        // After clearing cache, should get a new instance
        assertNotSame(v1, v2);
    }

    @Test
    void testValidRequestGetUsers() {
        OpenAPIValidator validator = OpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

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
        OpenAPIValidator validator = OpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        String body = "{\"name\": \"John Doe\", \"email\": \"john@example.com\"}";

        HeaderSet headers = new HeaderSet();
        headers.setHeader("Content-Type", "application/json");

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
        OpenAPIValidator validator = OpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        // Missing required 'email' field
        String body = "{\"name\": \"John Doe\"}";

        HeaderSet headers = new HeaderSet();
        headers.setHeader("Content-Type", "application/json");

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
        OpenAPIValidator validator = OpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        // Body with additional property 'age' not in schema
        String body = "{\"name\": \"John Doe\", \"email\": \"john@example.com\", \"age\": 30}";

        HeaderSet headers = new HeaderSet();
        headers.setHeader("Content-Type", "application/json");

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
        OpenAPIValidator validator = OpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.LENIENT);

        // Body with additional property 'age' not in schema
        String body = "{\"name\": \"John Doe\", \"email\": \"john@example.com\", \"age\": 30}";

        HeaderSet headers = new HeaderSet();
        headers.setHeader("Content-Type", "application/json");

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
        OpenAPIValidator validator = OpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.LIGHT);

        // Missing required 'email' field - would normally block
        String body = "{\"name\": \"John Doe\"}";

        HeaderSet headers = new HeaderSet();
        headers.setHeader("Content-Type", "application/json");

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
        OpenAPIValidator validator = OpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        String body = "[{\"id\": 1, \"name\": \"John\", \"email\": \"john@example.com\"}]";

        HeaderSet headers = new HeaderSet();
        headers.setHeader("Content-Type", "application/json");

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
        OpenAPIValidator validator = OpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        String body = "{\"id\": 1, \"name\": \"John\", \"email\": \"john@example.com\"}";

        HeaderSet headers = new HeaderSet();
        headers.setHeader("Content-Type", "application/json");

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
        OpenAPIValidator validator = OpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

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
        OpenAPIValidator validator = OpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        HeaderSet headers = new HeaderSet();
        headers.setHeader("Content-Type", "application/json");

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
        OpenAPIValidator validator = OpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);
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
        OpenAPIValidator validator = OpenAPIValidator.getInstance(SAMPLE_SPEC);

        validator.setPayloadLogMaxLength(100);
        assertEquals(100, validator.getPayloadLogMaxLength());

        validator.setPayloadLogMaxLength(50);
        assertEquals(50, validator.getPayloadLogMaxLength());
    }

    @Test
    void testSetDecodeQueryParams() {
        OpenAPIValidator validator = OpenAPIValidator.getInstance(SAMPLE_SPEC);

        assertTrue(validator.isDecodeQueryParams()); // default is true

        validator.setDecodeQueryParams(false);
        assertFalse(validator.isDecodeQueryParams());
    }

    @Test
    void testPathNotFoundValidation() {
        OpenAPIValidator validator = OpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

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
        OpenAPIValidator validator = OpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        QueryStringHeaderSet queryParams = new QueryStringHeaderSet("limit=10");

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
        OpenAPIValidator validator = OpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

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
