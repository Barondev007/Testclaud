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
 */
public class OpenApiValidatorCallout implements Execution {

    private static final ConcurrentHashMap<String, OpenApiInteractionValidator> VALIDATOR_CACHE =
            new ConcurrentHashMap<>();

    private final Map<String, String> properties;

    public OpenApiValidatorCallout(Map<String, String> properties) {
        this.properties = properties;
    }

    @Override
    public ExecutionResult execute(MessageContext messageContext, ExecutionContext executionContext) {
        try {
            // Get spec content from property
            String specContent = resolveProperty("specfile", messageContext);

            // Debug: Store what we received
            messageContext.setVariable("openapi.debug.specfile.raw", properties.get("specfile"));
            messageContext.setVariable("openapi.debug.specfile.resolved",
                specContent != null ? specContent.substring(0, Math.min(200, specContent.length())) : "NULL");
            messageContext.setVariable("openapi.debug.specfile.length",
                specContent != null ? String.valueOf(specContent.length()) : "0");

            if (specContent == null || specContent.isEmpty()) {
                setError(messageContext, "OpenAPI spec not provided. Set 'specfile' property. Raw value: "
                    + properties.get("specfile"));
                return ExecutionResult.ABORT;
            }

            // Trim whitespace and check for valid start
            specContent = specContent.trim();

            // Basic validation: spec should start with openapi/swagger (YAML) or { (JSON)
            if (!specContent.startsWith("openapi") &&
                !specContent.startsWith("swagger") &&
                !specContent.startsWith("{") &&
                !specContent.startsWith("\"openapi") &&
                !specContent.startsWith("'openapi")) {
                setError(messageContext, "Invalid spec format. Must be YAML (start with 'openapi:') or JSON (start with '{'). " +
                    "Received: " + specContent.substring(0, Math.min(50, specContent.length())));
                return ExecutionResult.ABORT;
            }

            // Get configuration
            String allowAdditionalPropsStr = resolveProperty("allow-additional-properties", messageContext);
            boolean allowAdditionalProperties = allowAdditionalPropsStr == null ||
                    "true".equalsIgnoreCase(allowAdditionalPropsStr);

            // Get cached validator
            OpenApiInteractionValidator validator;
            try {
                validator = getValidator(specContent, allowAdditionalProperties);
            } catch (Exception e) {
                setError(messageContext, "Failed to parse OpenAPI spec: " + e.getMessage() +
                    ". Spec preview: " + specContent.substring(0, Math.min(100, specContent.length())));
                return ExecutionResult.ABORT;
            }

            // Extract request details
            String httpMethod = messageContext.getVariable("request.verb");
            String requestPath = messageContext.getVariable("proxy.pathsuffix");
            String contentType = messageContext.getVariable("request.header.content-type");
            String requestBody = messageContext.getVariable("request.content");

            if (httpMethod == null) httpMethod = "GET";
            if (requestPath == null || requestPath.isEmpty()) requestPath = "/";
            if (contentType == null) contentType = "application/json";

            // Debug: Store request info
            messageContext.setVariable("openapi.debug.request.method", httpMethod);
            messageContext.setVariable("openapi.debug.request.path", requestPath);

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

            // Validate
            ValidationReport report = validator.validateRequest(requestBuilder.build());

            if (report.hasErrors()) {
                String errorMessage = formatErrors(report);
                setError(messageContext, errorMessage);
                return ExecutionResult.ABORT;
            }

            messageContext.setVariable("openapi.validation.failed", "false");
            return ExecutionResult.SUCCESS;

        } catch (Exception e) {
            setError(messageContext, "Internal error: " + e.getClass().getName() + " - " + e.getMessage());
            return ExecutionResult.ABORT;
        }
    }

    private static OpenApiInteractionValidator getValidator(String specContent, boolean allowAdditionalProperties) {
        String specHash = hashSpec(specContent);
        String cacheKey = specHash + "|" + allowAdditionalProperties;

        return VALIDATOR_CACHE.computeIfAbsent(cacheKey, key ->
                createValidator(specContent, allowAdditionalProperties));
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

    private static OpenApiInteractionValidator createValidator(String specContent, boolean allowAdditionalProperties) {
        OpenApiInteractionValidator.Builder builder =
                OpenApiInteractionValidator.createForInlineApiSpecification(specContent);

        if (allowAdditionalProperties) {
            builder.withLevelResolver(LevelResolverFactory.withAdditionalPropertiesIgnored());
        }

        return builder.build();
    }

    /**
     * Resolve property value - supports Apigee variable references.
     */
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

    public static void clearCache() {
        VALIDATOR_CACHE.clear();
    }

    public static int getCacheSize() {
        return VALIDATOR_CACHE.size();
    }
}
