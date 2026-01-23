package com.example.apigee;

import com.apigee.flow.execution.ExecutionContext;
import com.apigee.flow.execution.ExecutionResult;
import com.apigee.flow.execution.spi.Execution;
import com.apigee.flow.message.MessageContext;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.LevelResolverFactory;
import com.atlassian.oai.validator.report.ValidationReport;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Apigee Java Callout for OpenAPI Request/Response Validation
 *
 * Features:
 * - Spec content from property, variable, or resource file
 * - Thread-safe validator caching (one validator per unique spec + validation level)
 * - Configurable validation levels
 * - Support for both request and response validation
 *
 * Spec Source (one of these is required):
 * - specfile : The OpenAPI spec content as a String (YAML or JSON), or variable reference {varName}
 * - spec-resource : Path to spec file in proxy resources (e.g., "openapi/petstore.yaml")
 *                   File should be placed in: apiproxy/resources/openapi/petstore.yaml
 *
 * Optional Properties:
 * - validation-type : Type of validation (default: "request")
 *     - "request"  : Validate incoming request
 *     - "response" : Validate outgoing response
 * - validation-level : Validation level (default: "strict")
 *     - "light"    : All errors stored in variable, flow NOT blocked
 *     - "lenient"  : Additional properties ignored, other errors block flow
 *     - "strict"   : All errors block the flow
 *
 * Output Variables:
 * - openapi.validation.failed : "true" or "false"
 * - openapi.validation.error : Error message(s) if validation failed
 * - openapi.validation.errors.count : Number of validation errors
 * - openapi.validation.errors.all : All error messages (for light mode)
 * - openapi.validation.type : "request" or "response"
 */
public class OpenApiValidatorCallout implements Execution {

    // ========================================================================
    // VALIDATION TYPES
    // ========================================================================

    private static final String TYPE_REQUEST = "request";
    private static final String TYPE_RESPONSE = "response";

    // ========================================================================
    // VALIDATION LEVELS
    // ========================================================================

    private static final String LEVEL_LIGHT = "light";
    private static final String LEVEL_LENIENT = "lenient";
    private static final String LEVEL_STRICT = "strict";

    // ========================================================================
    // VALIDATOR CACHE
    // ========================================================================

    private static final ConcurrentHashMap<String, OpenApiInteractionValidator> VALIDATOR_CACHE =
            new ConcurrentHashMap<>();

    private final Map<String, String> properties;

    public OpenApiValidatorCallout(Map<String, String> properties) {
        this.properties = properties;
    }

    // ========================================================================
    // MAIN EXECUTION
    // ========================================================================

    @Override
    public ExecutionResult execute(MessageContext messageContext, ExecutionContext executionContext) {
        try {
            // Get spec content - try specfile first, then spec-resource
            String specContent = resolveProperty("specfile", messageContext);
            String specSource = "specfile";

            // If specfile is not set, try to load from resource file
            if (specContent == null || specContent.isEmpty()) {
                String resourcePath = resolveProperty("spec-resource", messageContext);
                if (resourcePath != null && !resourcePath.isEmpty()) {
                    specContent = loadResourceFile(resourcePath);
                    specSource = "spec-resource:" + resourcePath;
                }
            }

            // Debug variables
            messageContext.setVariable("openapi.debug.spec.source", specSource);
            messageContext.setVariable("openapi.debug.specfile.raw", properties.get("specfile"));
            messageContext.setVariable("openapi.debug.spec-resource", properties.get("spec-resource"));
            messageContext.setVariable("openapi.debug.specfile.resolved",
                specContent != null ? specContent.substring(0, Math.min(200, specContent.length())) : "NULL");
            messageContext.setVariable("openapi.debug.specfile.length",
                specContent != null ? String.valueOf(specContent.length()) : "0");

            if (specContent == null || specContent.isEmpty()) {
                setError(messageContext, "OpenAPI spec not provided. Set 'specfile' or 'spec-resource' property.");
                return ExecutionResult.ABORT;
            }

            specContent = specContent.trim();

            // Validate spec format
            if (!specContent.startsWith("openapi") &&
                !specContent.startsWith("swagger") &&
                !specContent.startsWith("{") &&
                !specContent.startsWith("\"openapi") &&
                !specContent.startsWith("'openapi")) {
                setError(messageContext, "Invalid spec format. Must be YAML or JSON. " +
                    "Received: " + specContent.substring(0, Math.min(50, specContent.length())));
                return ExecutionResult.ABORT;
            }

            // Get validation level (default: strict)
            String validationLevel = resolveProperty("validation-level", messageContext);
            if (validationLevel == null || validationLevel.isEmpty()) {
                validationLevel = LEVEL_STRICT;
            }
            validationLevel = validationLevel.toLowerCase().trim();

            // Validate level parameter
            if (!LEVEL_LIGHT.equals(validationLevel) &&
                !LEVEL_LENIENT.equals(validationLevel) &&
                !LEVEL_STRICT.equals(validationLevel)) {
                validationLevel = LEVEL_STRICT;
            }

            messageContext.setVariable("openapi.debug.validation.level", validationLevel);

            // Get validation type (default: request)
            String validationType = resolveProperty("validation-type", messageContext);
            if (validationType == null || validationType.isEmpty()) {
                validationType = TYPE_REQUEST;
            }
            validationType = validationType.toLowerCase().trim();

            // Validate type parameter
            if (!TYPE_REQUEST.equals(validationType) && !TYPE_RESPONSE.equals(validationType)) {
                validationType = TYPE_REQUEST;
            }

            messageContext.setVariable("openapi.validation.type", validationType);
            messageContext.setVariable("openapi.debug.validation.type", validationType);

            // Get cached validator
            OpenApiInteractionValidator validator;
            try {
                validator = getValidator(specContent, validationLevel);
            } catch (Exception e) {
                setError(messageContext, "Failed to parse OpenAPI spec: " + e.getMessage());
                return ExecutionResult.ABORT;
            }

            // Common: HTTP method and path (needed for both request and response)
            String httpMethod = messageContext.getVariable("request.verb");
            String requestPath = messageContext.getVariable("proxy.pathsuffix");

            if (httpMethod == null) httpMethod = "GET";
            if (requestPath == null || requestPath.isEmpty()) requestPath = "/";

            ValidationReport report;

            if (TYPE_RESPONSE.equals(validationType)) {
                // ============================================================
                // RESPONSE VALIDATION
                // ============================================================
                report = validateResponse(messageContext, validator, httpMethod, requestPath);

            } else {
                // ============================================================
                // REQUEST VALIDATION
                // ============================================================
                report = validateRequest(messageContext, validator, httpMethod, requestPath);
            }

            // Collect all messages
            List<String> allMessages = collectAllMessages(report);
            int errorCount = allMessages.size();

            // Store all messages
            messageContext.setVariable("openapi.validation.errors.all", String.join("; ", allMessages));
            messageContext.setVariable("openapi.validation.errors.count", String.valueOf(errorCount));

            // Handle based on validation level
            if (LEVEL_LIGHT.equals(validationLevel)) {
                // Light mode: Store errors but don't block
                if (errorCount > 0) {
                    messageContext.setVariable("openapi.validation.error", String.join("; ", allMessages));
                    messageContext.setVariable("openapi.validation.failed", "true");
                } else {
                    messageContext.setVariable("openapi.validation.failed", "false");
                }
                // Always return SUCCESS in light mode (non-blocking)
                return ExecutionResult.SUCCESS;

            } else {
                // Lenient/Strict mode: Block on errors
                if (report.hasErrors()) {
                    String errorMessage = formatErrors(report);
                    setError(messageContext, errorMessage);
                    return ExecutionResult.ABORT;
                }
                messageContext.setVariable("openapi.validation.failed", "false");
                return ExecutionResult.SUCCESS;
            }

        } catch (Exception e) {
            setError(messageContext, "Internal error: " + e.getClass().getName() + " - " + e.getMessage());
            return ExecutionResult.ABORT;
        }
    }

    // ========================================================================
    // VALIDATOR CACHE METHODS
    // ========================================================================

    private static OpenApiInteractionValidator getValidator(String specContent, String validationLevel) {
        String specHash = hashSpec(specContent);
        String cacheKey = specHash + "|" + validationLevel;

        return VALIDATOR_CACHE.computeIfAbsent(cacheKey, key ->
                createValidator(specContent, validationLevel));
    }

    private static String hashSpec(String specContent) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(specContent.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(specContent.hashCode());
        }
    }

    private static OpenApiInteractionValidator createValidator(String specContent, String validationLevel) {
        OpenApiInteractionValidator.Builder builder =
                OpenApiInteractionValidator.createForInlineApiSpecification(specContent);

        switch (validationLevel) {
            case LEVEL_LIGHT:
                // Light mode: Demote all errors to INFO
                builder.withLevelResolver(createLightLevelResolver());
                break;

            case LEVEL_LENIENT:
                // Lenient mode: Only ignore additional properties
                builder.withLevelResolver(LevelResolverFactory.withAdditionalPropertiesIgnored());
                break;

            case LEVEL_STRICT:
            default:
                // Strict mode: No level resolver
                break;
        }

        return builder.build();
    }

    /**
     * Creates a LevelResolver that demotes all errors to INFO level.
     */
    private static LevelResolver createLightLevelResolver() {
        return LevelResolver.create()
            .withLevel("validation.request.body.schema.additionalProperties", ValidationReport.Level.INFO)
            .withLevel("validation.response.body.schema.additionalProperties", ValidationReport.Level.INFO)
            .withLevel("validation.request.body.schema.required", ValidationReport.Level.INFO)
            .withLevel("validation.response.body.schema.required", ValidationReport.Level.INFO)
            .withLevel("validation.request.body.schema.type", ValidationReport.Level.INFO)
            .withLevel("validation.response.body.schema.type", ValidationReport.Level.INFO)
            .withLevel("validation.request.body.schema.format", ValidationReport.Level.INFO)
            .withLevel("validation.request.body.schema.enum", ValidationReport.Level.INFO)
            .withLevel("validation.request.body.schema.minimum", ValidationReport.Level.INFO)
            .withLevel("validation.request.body.schema.maximum", ValidationReport.Level.INFO)
            .withLevel("validation.request.body.schema.minLength", ValidationReport.Level.INFO)
            .withLevel("validation.request.body.schema.maxLength", ValidationReport.Level.INFO)
            .withLevel("validation.request.body.schema.pattern", ValidationReport.Level.INFO)
            .withLevel("validation.request.parameter.query.missing", ValidationReport.Level.INFO)
            .withLevel("validation.request.parameter.header.missing", ValidationReport.Level.INFO)
            .withLevel("validation.request.path.missing", ValidationReport.Level.INFO)
            .withLevel("validation.request.contentType.notAllowed", ValidationReport.Level.INFO)
            .withLevel("validation.request.body.missing", ValidationReport.Level.INFO)
            .build();
    }

    // ========================================================================
    // VALIDATION METHODS
    // ========================================================================

    private ValidationReport validateRequest(MessageContext messageContext,
            OpenApiInteractionValidator validator, String httpMethod, String requestPath) {

        String contentType = messageContext.getVariable("request.header.content-type");
        String requestBody = messageContext.getVariable("request.content");

        if (contentType == null) contentType = "application/json";

        // Build request
        SimpleRequest.Builder requestBuilder = new SimpleRequest.Builder(httpMethod, requestPath);

        if (requestBody != null && !requestBody.isEmpty()) {
            requestBuilder.withBody(requestBody);
            requestBuilder.withContentType(contentType);
        }

        // Add query parameters
        String queryString = messageContext.getVariable("request.querystring");
        if (queryString != null && !queryString.isEmpty()) {
            addQueryParams(requestBuilder, queryString);
        }

        // Add request headers
        addRequestHeaders(requestBuilder, messageContext);

        return validator.validateRequest(requestBuilder.build());
    }

    private ValidationReport validateResponse(MessageContext messageContext,
            OpenApiInteractionValidator validator, String httpMethod, String requestPath) {

        // Get response status code
        String statusStr = messageContext.getVariable("response.status.code");
        int statusCode = 200;
        if (statusStr != null && !statusStr.isEmpty()) {
            try {
                statusCode = Integer.parseInt(statusStr.trim());
            } catch (NumberFormatException e) {
                statusCode = 200;
            }
        }

        // Get response body and content type
        String responseBody = messageContext.getVariable("response.content");
        String contentType = messageContext.getVariable("response.header.content-type");

        if (contentType == null) contentType = "application/json";

        // Build response
        SimpleResponse.Builder responseBuilder = new SimpleResponse.Builder(statusCode);

        if (responseBody != null && !responseBody.isEmpty()) {
            responseBuilder.withBody(responseBody);
            responseBuilder.withContentType(contentType);
        }

        // Add response headers
        addResponseHeaders(responseBuilder, messageContext);

        // For response validation, we also need the request to match the operation
        SimpleRequest.Builder requestBuilder = new SimpleRequest.Builder(httpMethod, requestPath);

        return validator.validateResponse(requestPath, SimpleRequest.Method.valueOf(httpMethod.toUpperCase()),
                responseBuilder.build());
    }

    private void addRequestHeaders(SimpleRequest.Builder builder, MessageContext messageContext) {
        // Add common headers that might be relevant for validation
        String[] headerNames = {"accept", "authorization", "x-api-key", "x-request-id"};
        for (String headerName : headerNames) {
            String value = messageContext.getVariable("request.header." + headerName);
            if (value != null && !value.isEmpty()) {
                builder.withHeader(headerName, value);
            }
        }
    }

    private void addResponseHeaders(SimpleResponse.Builder builder, MessageContext messageContext) {
        // Add common response headers that might be relevant for validation
        String[] headerNames = {"content-type", "x-request-id", "x-correlation-id"};
        for (String headerName : headerNames) {
            String value = messageContext.getVariable("response.header." + headerName);
            if (value != null && !value.isEmpty()) {
                builder.withHeader(headerName, value);
            }
        }
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Load spec content from a resource file in the proxy bundle.
     * Resource files should be placed in: apiproxy/resources/{resourcePath}
     *
     * @param resourcePath Path to the resource file (e.g., "openapi/petstore.yaml")
     * @return The file content as a String, or null if not found
     */
    private String loadResourceFile(String resourcePath) {
        try {
            // Try multiple classloader approaches for Apigee compatibility
            InputStream inputStream = null;

            // Try 1: Thread context classloader
            ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
            if (contextLoader != null) {
                inputStream = contextLoader.getResourceAsStream(resourcePath);
            }

            // Try 2: Class classloader
            if (inputStream == null) {
                inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
            }

            // Try 3: Direct class resource
            if (inputStream == null) {
                inputStream = getClass().getResourceAsStream("/" + resourcePath);
            }

            if (inputStream == null) {
                return null;
            }

            // Read the content
            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
            }
            return content.toString();

        } catch (Exception e) {
            return null;
        }
    }

    private String resolveProperty(String propertyName, MessageContext messageContext) {
        String value = properties.get(propertyName);
        if (value == null) {
            return null;
        }

        // Check for variable reference: {varName}
        if (value.startsWith("{") && value.endsWith("}") && !value.startsWith("{\"")) {
            String varName = value.substring(1, value.length() - 1);
            Object resolved = messageContext.getVariable(varName);
            return resolved != null ? resolved.toString() : null;
        }

        return value;
    }

    private void addQueryParams(SimpleRequest.Builder builder, String queryString) {
        try {
            String[] pairs = queryString.split("&");
            for (String pair : pairs) {
                String[] parts = pair.split("=", 2);
                String key = java.net.URLDecoder.decode(parts[0], "UTF-8");
                String value = parts.length > 1 ? java.net.URLDecoder.decode(parts[1], "UTF-8") : "";
                builder.withQueryParam(key, value);
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    /**
     * Collect all messages (INFO, WARN, ERROR) for light mode.
     */
    private List<String> collectAllMessages(ValidationReport report) {
        List<String> messages = new ArrayList<>();
        for (ValidationReport.Message message : report.getMessages()) {
            ValidationReport.Level level = message.getLevel();
            if (level == ValidationReport.Level.INFO ||
                level == ValidationReport.Level.WARN ||
                level == ValidationReport.Level.ERROR) {
                messages.add("[" + message.getKey() + "] " + message.getMessage());
            }
        }
        return messages;
    }

    /**
     * Format only ERROR level messages.
     */
    private String formatErrors(ValidationReport report) {
        StringBuilder sb = new StringBuilder();
        for (ValidationReport.Message message : report.getMessages()) {
            if (message.getLevel() == ValidationReport.Level.ERROR) {
                if (sb.length() > 0) sb.append("; ");
                sb.append("[").append(message.getKey()).append("] ").append(message.getMessage());
            }
        }
        return sb.toString();
    }

    private void setError(MessageContext messageContext, String errorMessage) {
        messageContext.setVariable("openapi.validation.error", errorMessage);
        messageContext.setVariable("openapi.validation.failed", "true");
    }

    // ========================================================================
    // CACHE MANAGEMENT
    // ========================================================================

    public static void clearCache() {
        VALIDATOR_CACHE.clear();
    }

    public static int getCacheSize() {
        return VALIDATOR_CACHE.size();
    }
}
