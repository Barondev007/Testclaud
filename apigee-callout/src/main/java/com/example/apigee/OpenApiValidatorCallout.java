package com.example.apigee;

import com.apigee.flow.execution.ExecutionContext;
import com.apigee.flow.execution.ExecutionResult;
import com.apigee.flow.execution.spi.Execution;
import com.apigee.flow.message.MessageContext;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.report.LevelResolverFactory;
import com.atlassian.oai.validator.report.ValidationReport;

import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Apigee Java Callout for OpenAPI Request Validation
 *
 * Features:
 * - Spec content passed directly as property (not from file path)
 * - Thread-safe validator caching (one validator per unique spec)
 * - Lenient validation (allows additional properties)
 *
 * Required Properties:
 * - specfile : The OpenAPI spec content as a String (YAML or JSON)
 *
 * Optional Properties:
 * - allow-additional-properties : "true" or "false" (default: "true")
 *
 * Output Variables (set in message context):
 * - openapi.validation.error : Error message if validation fails
 * - openapi.validation.failed : "true" or "false"
 */
public class OpenApiValidatorCallout implements Execution {

    // ========================================================================
    // VALIDATOR CACHE - Thread-safe, one validator per unique spec content
    // ========================================================================

    private static final ConcurrentHashMap<String, OpenApiInteractionValidator> VALIDATOR_CACHE =
            new ConcurrentHashMap<>();

    // Property map from callout configuration
    private final Map<String, String> properties;

    /**
     * Constructor called by Apigee with properties from the callout configuration.
     */
    public OpenApiValidatorCallout(Map<String, String> properties) {
        this.properties = properties;
    }

    // ========================================================================
    // MAIN EXECUTION
    // ========================================================================

    @Override
    public ExecutionResult execute(MessageContext messageContext, ExecutionContext executionContext) {
        try {
            // Get spec content from property (can reference a variable)
            String specContent = resolveProperty("specfile", messageContext);

            if (specContent == null || specContent.isEmpty()) {
                setError(messageContext, "OpenAPI spec not provided. Set 'specfile' property.");
                return ExecutionResult.ABORT;
            }

            // Get configuration (default: allow additional properties)
            String allowAdditionalPropsStr = resolveProperty("allow-additional-properties", messageContext);
            boolean allowAdditionalProperties = allowAdditionalPropsStr == null ||
                    "true".equalsIgnoreCase(allowAdditionalPropsStr);

            // Get cached validator (thread-safe, reuses instance for same spec)
            OpenApiInteractionValidator validator = getValidator(specContent, allowAdditionalProperties);

            // Extract request details from Apigee message context
            String httpMethod = messageContext.getVariable("request.verb");
            String requestPath = messageContext.getVariable("proxy.pathsuffix");
            String contentType = messageContext.getVariable("request.header.content-type");
            String requestBody = messageContext.getVariable("request.content");

            if (httpMethod == null) httpMethod = "GET";
            if (requestPath == null) requestPath = "/";
            if (contentType == null) contentType = "application/json";

            // Build request for validation
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

            // Validate request
            ValidationReport report = validator.validateRequest(requestBuilder.build());

            if (report.hasErrors()) {
                String errorMessage = formatErrors(report);
                setError(messageContext, errorMessage);
                return ExecutionResult.ABORT;
            }

            // Validation passed
            messageContext.setVariable("openapi.validation.failed", "false");
            return ExecutionResult.SUCCESS;

        } catch (Exception e) {
            setError(messageContext, "Internal validation error: " + e.getMessage());
            return ExecutionResult.ABORT;
        }
    }

    // ========================================================================
    // VALIDATOR CACHE METHODS
    // ========================================================================

    /**
     * Get or create a validator for the given spec content.
     */
    private static OpenApiInteractionValidator getValidator(String specContent, boolean allowAdditionalProperties) {
        String specHash = hashSpec(specContent);
        String cacheKey = specHash + "|" + allowAdditionalProperties;

        return VALIDATOR_CACHE.computeIfAbsent(cacheKey, key ->
                createValidator(specContent, allowAdditionalProperties));
    }

    /**
     * Create MD5 hash of spec content for cache key.
     */
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

    /**
     * Create a new validator instance from spec content.
     */
    private static OpenApiInteractionValidator createValidator(String specContent, boolean allowAdditionalProperties) {
        OpenApiInteractionValidator.Builder builder =
                OpenApiInteractionValidator.createForInlineApiSpecification(specContent);

        if (allowAdditionalProperties) {
            builder.withLevelResolver(LevelResolverFactory.withAdditionalPropertiesIgnored());
        }

        return builder.build();
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Resolve a property value, supporting Apigee variable references.
     */
    private String resolveProperty(String propertyName, MessageContext messageContext) {
        String value = properties.get(propertyName);
        if (value == null) {
            return null;
        }

        // If the value is a variable reference like {myVar}, resolve it
        if (value.startsWith("{") && value.endsWith("}")) {
            String varName = value.substring(1, value.length() - 1);
            return messageContext.getVariable(varName);
        }

        return value;
    }

    /**
     * Add query parameters to the request builder.
     */
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
            // Ignore query param parsing errors
        }
    }

    /**
     * Format validation errors into a readable message.
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

    /**
     * Set error variables in message context.
     */
    private void setError(MessageContext messageContext, String errorMessage) {
        messageContext.setVariable("openapi.validation.error", errorMessage);
        messageContext.setVariable("openapi.validation.failed", "true");
    }

    // ========================================================================
    // CACHE MANAGEMENT (can be called from another callout if needed)
    // ========================================================================

    /**
     * Clear the validator cache.
     */
    public static void clearCache() {
        VALIDATOR_CACHE.clear();
    }

    /**
     * Get the current cache size.
     */
    public static int getCacheSize() {
        return VALIDATOR_CACHE.size();
    }
}
