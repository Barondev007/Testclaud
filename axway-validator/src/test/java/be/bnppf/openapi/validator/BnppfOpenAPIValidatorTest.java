package be.bnppf.openapi.validator;

import com.vordel.mime.HeaderSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BnppfOpenAPIValidator.
 * Uses test stub HeaderSet class.
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
        BnppfOpenAPIValidator.clearCache();
    }

    private HeaderSet createHeaders(String contentType) {
        HeaderSet headers = new HeaderSet();
        headers.setHeader("Content-Type", contentType);
        return headers;
    }

    private HeaderSet createQueryParams(String... params) {
        HeaderSet queryParams = new HeaderSet();
        for (String param : params) {
            String[] parts = param.split("=", 2);
            if (parts.length == 2) {
                queryParams.addHeader(parts[0], parts[1]);
            }
        }
        return queryParams;
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
        assertSame(v1, v2);
    }

    @Test
    void testDifferentLevelsNotCachedTogether() {
        BnppfOpenAPIValidator strict = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);
        BnppfOpenAPIValidator lenient = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.LENIENT);
        assertNotSame(strict, lenient);
    }

    @Test
    void testClearCache() {
        BnppfOpenAPIValidator v1 = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC);
        assertTrue(BnppfOpenAPIValidator.getCacheSize() > 0);

        BnppfOpenAPIValidator.clearCache();
        assertEquals(0, BnppfOpenAPIValidator.getCacheSize());

        BnppfOpenAPIValidator v2 = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC);
        assertNotSame(v1, v2);
    }

    @Test
    void testValidRequestGetUsers() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        ValidationResult result = validator.validateRequest(null, "GET", "/users", null, null);

        assertFalse(result.isBlocked());
        assertEquals("request", result.getValidationType());
    }

    @Test
    void testValidRequestPostUser() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        String body = "{\"name\": \"John Doe\", \"email\": \"john@example.com\"}";
        HeaderSet headers = createHeaders("application/json");

        ValidationResult result = validator.validateRequest(body, "POST", "/users", null, headers);

        assertFalse(result.isBlocked());
        assertEquals("request", result.getValidationType());
    }

    @Test
    void testInvalidRequestMissingRequiredField() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        String body = "{\"name\": \"John Doe\"}";
        HeaderSet headers = createHeaders("application/json");

        ValidationResult result = validator.validateRequest(body, "POST", "/users", null, headers);

        assertTrue(result.isBlocked());
        assertFalse(result.isValid());
        assertTrue(result.getErrorCount() > 0);
    }

    @Test
    void testInvalidRequestAdditionalPropertiesStrict() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        String body = "{\"name\": \"John Doe\", \"email\": \"john@example.com\", \"age\": 30}";
        HeaderSet headers = createHeaders("application/json");

        ValidationResult result = validator.validateRequest(body, "POST", "/users", null, headers);

        assertTrue(result.isBlocked());
    }

    @Test
    void testAdditionalPropertiesAllowedInLenientMode() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.LENIENT);

        String body = "{\"name\": \"John Doe\", \"email\": \"john@example.com\", \"age\": 30}";
        HeaderSet headers = createHeaders("application/json");

        ValidationResult result = validator.validateRequest(body, "POST", "/users", null, headers);

        assertFalse(result.isBlocked());
    }

    @Test
    void testLightModeDoesNotBlock() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.LIGHT);

        String body = "{\"name\": \"John Doe\"}";
        HeaderSet headers = createHeaders("application/json");

        ValidationResult result = validator.validateRequest(body, "POST", "/users", null, headers);

        assertFalse(result.isBlocked());
        assertTrue(result.getAllMessages().size() > 0);
    }

    @Test
    void testValidResponseValidation() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        String body = "[{\"id\": 1, \"name\": \"John\", \"email\": \"john@example.com\"}]";
        HeaderSet headers = createHeaders("application/json");

        ValidationResult result = validator.validateResponse(body, "GET", "/users", 200, headers);

        assertFalse(result.isBlocked());
        assertEquals("response", result.getValidationType());
    }

    @Test
    void testInvalidResponseWrongStatusCode() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        String body = "{\"id\": 1, \"name\": \"John\", \"email\": \"john@example.com\"}";
        HeaderSet headers = createHeaders("application/json");

        ValidationResult result = validator.validateResponse(body, "GET", "/users/1", 500, headers);

        assertTrue(result.isBlocked() || result.getAllMessages().size() > 0);
    }

    @Test
    void testDebugMode() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);
        validator.setDebugEnabled(true);

        assertTrue(validator.isDebugEnabled());

        ValidationResult result = validator.validateRequest(null, "GET", "/users", null, null);

        assertNotNull(result.getDebugInfo());
    }

    @Test
    void testPathNotFoundValidation() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        ValidationResult result = validator.validateRequest(null, "GET", "/nonexistent/path", null, null);

        assertTrue(result.isBlocked());
        assertTrue(result.getErrorsAsString().contains("No API path found") ||
                   result.getAllMessagesAsString().contains("No API path found"));
    }

    @Test
    void testGetUsersWithQueryParams() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        HeaderSet queryParams = createQueryParams("limit=10");

        ValidationResult result = validator.validateRequest(null, "GET", "/users", queryParams, null);

        assertFalse(result.isBlocked());
    }

    @Test
    void testGetUserById() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        ValidationResult result = validator.validateRequest(null, "GET", "/users/123", null, null);

        assertFalse(result.isBlocked());
    }

    @Test
    void testHeadersWithMultipleValues() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        HeaderSet headers = new HeaderSet();
        headers.setHeader("Content-Type", "application/json");
        headers.addHeader("Accept", "application/json");
        headers.addHeader("Accept", "text/plain");

        String body = "{\"name\": \"John Doe\", \"email\": \"john@example.com\"}";

        ValidationResult result = validator.validateRequest(body, "POST", "/users", null, headers);

        assertFalse(result.isBlocked());
    }

    @Test
    void testQueryParamsWithMultipleValues() {
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(SAMPLE_SPEC, ValidationLevel.STRICT);

        HeaderSet queryParams = createQueryParams("limit=10", "offset=0");

        ValidationResult result = validator.validateRequest(null, "GET", "/users", queryParams, null);

        assertFalse(result.isBlocked());
    }
}
