package com.example.apigee;

import com.apigee.flow.execution.ExecutionContext;
import com.apigee.flow.execution.ExecutionResult;
import com.apigee.flow.message.MessageContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OpenApiValidatorCallout.
 */
class OpenApiValidatorCalloutTest {

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

    private MessageContext messageContext;
    private ExecutionContext executionContext;

    @BeforeEach
    void setUp() {
        messageContext = new MessageContext();
        executionContext = new ExecutionContext();
        OpenApiValidatorCallout.clearCache();
    }

    private OpenApiValidatorCallout createCallout(Map<String, String> properties) {
        return new OpenApiValidatorCallout(properties);
    }

    private Map<String, String> createProperties(String specfile) {
        Map<String, String> props = new HashMap<>();
        props.put("specfile", specfile);
        return props;
    }

    // ========================================================================
    // REQUEST VALIDATION TESTS
    // ========================================================================

    @Test
    void testValidGetRequest() {
        Map<String, String> props = createProperties(SAMPLE_SPEC);
        props.put("validation-type", "request");
        OpenApiValidatorCallout callout = createCallout(props);

        messageContext.setVariable("request.verb", "GET");
        messageContext.setVariable("proxy.pathsuffix", "/users");

        ExecutionResult result = callout.execute(messageContext, executionContext);

        assertEquals(ExecutionResult.SUCCESS, result);
        assertEquals("false", messageContext.getVariable("openapi.validation.failed"));
        assertEquals("request", messageContext.getVariable("openapi.validation.type"));
    }

    @Test
    void testValidPostRequest() {
        Map<String, String> props = createProperties(SAMPLE_SPEC);
        props.put("validation-type", "request");
        props.put("validation-level", "strict");
        OpenApiValidatorCallout callout = createCallout(props);

        messageContext.setVariable("request.verb", "POST");
        messageContext.setVariable("proxy.pathsuffix", "/users");
        messageContext.setVariable("request.header.content-type", "application/json");
        messageContext.setVariable("request.content", "{\"name\": \"John Doe\", \"email\": \"john@example.com\"}");

        ExecutionResult result = callout.execute(messageContext, executionContext);

        assertEquals(ExecutionResult.SUCCESS, result);
        assertEquals("false", messageContext.getVariable("openapi.validation.failed"));
    }

    @Test
    void testInvalidPostRequestMissingRequiredField() {
        Map<String, String> props = createProperties(SAMPLE_SPEC);
        props.put("validation-type", "request");
        props.put("validation-level", "strict");
        OpenApiValidatorCallout callout = createCallout(props);

        messageContext.setVariable("request.verb", "POST");
        messageContext.setVariable("proxy.pathsuffix", "/users");
        messageContext.setVariable("request.header.content-type", "application/json");
        messageContext.setVariable("request.content", "{\"name\": \"John Doe\"}");

        ExecutionResult result = callout.execute(messageContext, executionContext);

        assertEquals(ExecutionResult.ABORT, result);
        assertEquals("true", messageContext.getVariable("openapi.validation.failed"));
        String error = messageContext.getVariable("openapi.validation.error");
        assertNotNull(error);
        assertTrue(error.contains("email"));
    }

    @Test
    void testInvalidPostRequestAdditionalPropertiesStrict() {
        Map<String, String> props = createProperties(SAMPLE_SPEC);
        props.put("validation-type", "request");
        props.put("validation-level", "strict");
        OpenApiValidatorCallout callout = createCallout(props);

        messageContext.setVariable("request.verb", "POST");
        messageContext.setVariable("proxy.pathsuffix", "/users");
        messageContext.setVariable("request.header.content-type", "application/json");
        messageContext.setVariable("request.content",
            "{\"name\": \"John Doe\", \"email\": \"john@example.com\", \"age\": 30}");

        ExecutionResult result = callout.execute(messageContext, executionContext);

        assertEquals(ExecutionResult.ABORT, result);
        assertEquals("true", messageContext.getVariable("openapi.validation.failed"));
    }

    @Test
    void testAdditionalPropertiesAllowedInLenientMode() {
        Map<String, String> props = createProperties(SAMPLE_SPEC);
        props.put("validation-type", "request");
        props.put("validation-level", "lenient");
        OpenApiValidatorCallout callout = createCallout(props);

        messageContext.setVariable("request.verb", "POST");
        messageContext.setVariable("proxy.pathsuffix", "/users");
        messageContext.setVariable("request.header.content-type", "application/json");
        messageContext.setVariable("request.content",
            "{\"name\": \"John Doe\", \"email\": \"john@example.com\", \"age\": 30}");

        ExecutionResult result = callout.execute(messageContext, executionContext);

        assertEquals(ExecutionResult.SUCCESS, result);
        assertEquals("false", messageContext.getVariable("openapi.validation.failed"));
    }

    @Test
    void testLightModeDoesNotBlock() {
        Map<String, String> props = createProperties(SAMPLE_SPEC);
        props.put("validation-type", "request");
        props.put("validation-level", "light");
        OpenApiValidatorCallout callout = createCallout(props);

        messageContext.setVariable("request.verb", "POST");
        messageContext.setVariable("proxy.pathsuffix", "/users");
        messageContext.setVariable("request.header.content-type", "application/json");
        messageContext.setVariable("request.content", "{\"name\": \"John Doe\"}"); // Missing email

        ExecutionResult result = callout.execute(messageContext, executionContext);

        // Light mode should NOT block
        assertEquals(ExecutionResult.SUCCESS, result);
        assertEquals("true", messageContext.getVariable("openapi.validation.failed"));
        assertNotNull(messageContext.getVariable("openapi.validation.errors.all"));
    }

    @Test
    void testRequestWithQueryParams() {
        Map<String, String> props = createProperties(SAMPLE_SPEC);
        props.put("validation-type", "request");
        OpenApiValidatorCallout callout = createCallout(props);

        messageContext.setVariable("request.verb", "GET");
        messageContext.setVariable("proxy.pathsuffix", "/users");
        messageContext.setVariable("request.querystring", "limit=10");

        ExecutionResult result = callout.execute(messageContext, executionContext);

        assertEquals(ExecutionResult.SUCCESS, result);
        assertEquals("false", messageContext.getVariable("openapi.validation.failed"));
    }

    // ========================================================================
    // RESPONSE VALIDATION TESTS
    // ========================================================================

    @Test
    void testValidResponse() {
        Map<String, String> props = createProperties(SAMPLE_SPEC);
        props.put("validation-type", "response");
        props.put("validation-level", "strict");
        OpenApiValidatorCallout callout = createCallout(props);

        messageContext.setVariable("request.verb", "GET");
        messageContext.setVariable("proxy.pathsuffix", "/users");
        messageContext.setVariable("response.status.code", "200");
        messageContext.setVariable("response.header.content-type", "application/json");
        messageContext.setVariable("response.content",
            "[{\"id\": 1, \"name\": \"John\", \"email\": \"john@example.com\"}]");

        ExecutionResult result = callout.execute(messageContext, executionContext);

        assertEquals(ExecutionResult.SUCCESS, result);
        assertEquals("false", messageContext.getVariable("openapi.validation.failed"));
        assertEquals("response", messageContext.getVariable("openapi.validation.type"));
    }

    @Test
    void testInvalidResponseMissingRequiredField() {
        Map<String, String> props = createProperties(SAMPLE_SPEC);
        props.put("validation-type", "response");
        props.put("validation-level", "strict");
        OpenApiValidatorCallout callout = createCallout(props);

        messageContext.setVariable("request.verb", "GET");
        messageContext.setVariable("proxy.pathsuffix", "/users");
        messageContext.setVariable("response.status.code", "200");
        messageContext.setVariable("response.header.content-type", "application/json");
        messageContext.setVariable("response.content",
            "[{\"id\": 1, \"name\": \"John\"}]"); // Missing email

        ExecutionResult result = callout.execute(messageContext, executionContext);

        assertEquals(ExecutionResult.ABORT, result);
        assertEquals("true", messageContext.getVariable("openapi.validation.failed"));
    }

    @Test
    void testResponseValidationLenientMode() {
        Map<String, String> props = createProperties(SAMPLE_SPEC);
        props.put("validation-type", "response");
        props.put("validation-level", "lenient");
        OpenApiValidatorCallout callout = createCallout(props);

        messageContext.setVariable("request.verb", "GET");
        messageContext.setVariable("proxy.pathsuffix", "/users");
        messageContext.setVariable("response.status.code", "200");
        messageContext.setVariable("response.header.content-type", "application/json");
        messageContext.setVariable("response.content",
            "[{\"id\": 1, \"name\": \"John\", \"email\": \"john@example.com\", \"extra\": \"field\"}]");

        ExecutionResult result = callout.execute(messageContext, executionContext);

        assertEquals(ExecutionResult.SUCCESS, result);
        assertEquals("false", messageContext.getVariable("openapi.validation.failed"));
    }

    @Test
    void testResponseValidationLightMode() {
        Map<String, String> props = createProperties(SAMPLE_SPEC);
        props.put("validation-type", "response");
        props.put("validation-level", "light");
        OpenApiValidatorCallout callout = createCallout(props);

        messageContext.setVariable("request.verb", "GET");
        messageContext.setVariable("proxy.pathsuffix", "/users");
        messageContext.setVariable("response.status.code", "200");
        messageContext.setVariable("response.header.content-type", "application/json");
        messageContext.setVariable("response.content",
            "[{\"id\": 1, \"name\": \"John\"}]"); // Missing email

        ExecutionResult result = callout.execute(messageContext, executionContext);

        // Light mode should NOT block
        assertEquals(ExecutionResult.SUCCESS, result);
        assertEquals("true", messageContext.getVariable("openapi.validation.failed"));
    }

    // ========================================================================
    // ERROR HANDLING TESTS
    // ========================================================================

    @Test
    void testMissingSpecfile() {
        Map<String, String> props = new HashMap<>();
        OpenApiValidatorCallout callout = createCallout(props);

        ExecutionResult result = callout.execute(messageContext, executionContext);

        assertEquals(ExecutionResult.ABORT, result);
        assertEquals("true", messageContext.getVariable("openapi.validation.failed"));
        String error = messageContext.getVariable("openapi.validation.error");
        assertNotNull(error);
        assertTrue(error.contains("spec not provided"));
    }

    @Test
    void testInvalidSpecFormat() {
        Map<String, String> props = createProperties("this is not a valid spec");
        OpenApiValidatorCallout callout = createCallout(props);

        ExecutionResult result = callout.execute(messageContext, executionContext);

        assertEquals(ExecutionResult.ABORT, result);
        assertEquals("true", messageContext.getVariable("openapi.validation.failed"));
    }

    @Test
    void testPathNotFound() {
        Map<String, String> props = createProperties(SAMPLE_SPEC);
        props.put("validation-level", "strict");
        OpenApiValidatorCallout callout = createCallout(props);

        messageContext.setVariable("request.verb", "GET");
        messageContext.setVariable("proxy.pathsuffix", "/nonexistent");

        ExecutionResult result = callout.execute(messageContext, executionContext);

        assertEquals(ExecutionResult.ABORT, result);
        assertEquals("true", messageContext.getVariable("openapi.validation.failed"));
    }

    // ========================================================================
    // VARIABLE REFERENCE TESTS
    // ========================================================================

    @Test
    void testSpecFromVariable() {
        Map<String, String> props = new HashMap<>();
        props.put("specfile", "{myspec}");
        OpenApiValidatorCallout callout = createCallout(props);

        messageContext.setVariable("myspec", SAMPLE_SPEC);
        messageContext.setVariable("request.verb", "GET");
        messageContext.setVariable("proxy.pathsuffix", "/users");

        ExecutionResult result = callout.execute(messageContext, executionContext);

        assertEquals(ExecutionResult.SUCCESS, result);
        assertEquals("false", messageContext.getVariable("openapi.validation.failed"));
    }

    // ========================================================================
    // CACHE TESTS
    // ========================================================================

    @Test
    void testValidatorCaching() {
        Map<String, String> props = createProperties(SAMPLE_SPEC);
        OpenApiValidatorCallout callout1 = createCallout(props);
        OpenApiValidatorCallout callout2 = createCallout(props);

        messageContext.setVariable("request.verb", "GET");
        messageContext.setVariable("proxy.pathsuffix", "/users");

        // First execution
        callout1.execute(messageContext, executionContext);
        int cacheSize1 = OpenApiValidatorCallout.getCacheSize();

        // Second execution with same spec
        callout2.execute(messageContext, executionContext);
        int cacheSize2 = OpenApiValidatorCallout.getCacheSize();

        // Cache size should remain the same (validator reused)
        assertEquals(cacheSize1, cacheSize2);
        assertTrue(cacheSize1 > 0);
    }

    @Test
    void testClearCache() {
        Map<String, String> props = createProperties(SAMPLE_SPEC);
        OpenApiValidatorCallout callout = createCallout(props);

        messageContext.setVariable("request.verb", "GET");
        messageContext.setVariable("proxy.pathsuffix", "/users");

        callout.execute(messageContext, executionContext);
        assertTrue(OpenApiValidatorCallout.getCacheSize() > 0);

        OpenApiValidatorCallout.clearCache();
        assertEquals(0, OpenApiValidatorCallout.getCacheSize());
    }

    // ========================================================================
    // DEFAULT VALUES TESTS
    // ========================================================================

    @Test
    void testDefaultValidationType() {
        Map<String, String> props = createProperties(SAMPLE_SPEC);
        // No validation-type set
        OpenApiValidatorCallout callout = createCallout(props);

        messageContext.setVariable("request.verb", "GET");
        messageContext.setVariable("proxy.pathsuffix", "/users");

        callout.execute(messageContext, executionContext);

        // Should default to "request"
        assertEquals("request", messageContext.getVariable("openapi.validation.type"));
    }

    @Test
    void testDefaultValidationLevel() {
        Map<String, String> props = createProperties(SAMPLE_SPEC);
        // No validation-level set
        OpenApiValidatorCallout callout = createCallout(props);

        messageContext.setVariable("request.verb", "POST");
        messageContext.setVariable("proxy.pathsuffix", "/users");
        messageContext.setVariable("request.header.content-type", "application/json");
        messageContext.setVariable("request.content",
            "{\"name\": \"John Doe\", \"email\": \"john@example.com\", \"age\": 30}");

        ExecutionResult result = callout.execute(messageContext, executionContext);

        // Default is strict, so additional properties should cause ABORT
        assertEquals(ExecutionResult.ABORT, result);
    }
}
