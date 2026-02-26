package be.bnppf.openapi.validator.cli;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.Response;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.LevelResolverFactory;
import com.atlassian.oai.validator.report.ValidationReport;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenAPI Validator for CLI usage.
 * Uses standard Java Map types for headers and query parameters.
 */
public class OpenApiValidator {

    private static final ConcurrentHashMap<String, OpenApiValidator> validatorCache = new ConcurrentHashMap<>();

    private OpenApiInteractionValidator validator;
    private ValidationLevel validationLevel = ValidationLevel.STRICT;
    private boolean debugEnabled = false;
    private StringBuilder debugInfo;

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    public static synchronized OpenApiValidator getInstance(String openAPISpec, ValidationLevel level) {
        String cacheKey = hashSpec(openAPISpec) + "|" + level.getValue();
        return validatorCache.computeIfAbsent(cacheKey, key -> new OpenApiValidator(openAPISpec, level));
    }

    public static synchronized OpenApiValidator getInstance(String openAPISpec) {
        return getInstance(openAPISpec, ValidationLevel.STRICT);
    }

    // ========================================================================
    // CONSTRUCTOR
    // ========================================================================

    private OpenApiValidator(String openAPISpec, ValidationLevel level) {
        this.validationLevel = level;
        this.debugInfo = new StringBuilder();
        this.validator = buildValidator(
            OpenApiInteractionValidator.createForInlineApiSpecification(openAPISpec),
            level
        );
    }

    private OpenApiInteractionValidator buildValidator(OpenApiInteractionValidator.Builder builder, ValidationLevel level) {
        switch (level) {
            case LIGHT:
                builder.withLevelResolver(createLightLevelResolver());
                break;
            case LENIENT:
                builder.withLevelResolver(LevelResolverFactory.withAdditionalPropertiesIgnored());
                break;
            case STRICT:
            default:
                break;
        }
        return builder.build();
    }

    private LevelResolver createLightLevelResolver() {
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
    // REQUEST VALIDATION
    // ========================================================================

    /**
     * Validate a request using standard Java Map types.
     *
     * @param payload     Request body (JSON string)
     * @param verb        HTTP method (GET, POST, etc.)
     * @param path        Request path
     * @param queryParams Query parameters as Map (can be null)
     * @param headers     Headers as Map (can be null)
     * @return ValidationResult
     */
    public ValidationResult validateRequest(String payload, String verb, String path,
            Map<String, List<String>> queryParams, Map<String, List<String>> headers) {

        if (debugEnabled) {
            debugInfo = new StringBuilder();
            debugInfo.append("=== REQUEST VALIDATION ===\n");
            debugInfo.append("Verb: ").append(verb).append("\n");
            debugInfo.append("Path: ").append(path).append("\n");
            debugInfo.append("Level: ").append(validationLevel).append("\n");
            logDebugMap("HEADERS", headers);
            logDebugMap("QUERY PARAMS", queryParams);
        }

        ValidationReport report = executeRequestValidation(payload, verb, path, queryParams, headers);
        ValidationResult result = ValidationResult.fromReport(report, validationLevel, "request");

        if (debugEnabled) {
            result.setDebugInfo(debugInfo.toString());
        }

        return result;
    }

    private ValidationReport executeRequestValidation(final String payload, final String verb, final String path,
            final Map<String, List<String>> queryParams, final Map<String, List<String>> headers) {

        Request request = new Request() {
            @Override
            public String getPath() {
                return path;
            }

            @Override
            public Method getMethod() {
                return Request.Method.valueOf(verb.toUpperCase());
            }

            @Override
            public Optional<String> getBody() {
                return Optional.ofNullable(payload);
            }

            @Override
            public Collection<String> getQueryParameters() {
                if (queryParams == null) return Collections.emptyList();
                return queryParams.keySet();
            }

            @Override
            public Collection<String> getQueryParameterValues(String name) {
                if (queryParams == null) return Collections.emptyList();
                List<String> values = queryParams.get(name);
                if (values == null || values.isEmpty()) return Collections.emptyList();
                ArrayList<String> decoded = new ArrayList<>();
                for (String value : values) {
                    try {
                        decoded.add(URLDecoder.decode(value, StandardCharsets.UTF_8.toString()));
                    } catch (UnsupportedEncodingException e) {
                        decoded.add(value);
                    }
                }
                return decoded;
            }

            @Override
            public Map<String, Collection<String>> getHeaders() {
                if (headers == null) return Collections.emptyMap();
                Map<String, Collection<String>> result = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                    result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                }
                return result;
            }

            @Override
            public Collection<String> getHeaderValues(String name) {
                if (headers == null) return Collections.emptyList();
                List<String> values = headers.get(name);
                return (values == null) ? Collections.emptyList() : new ArrayList<>(values);
            }
        };

        return validator.validateRequest(request);
    }

    // ========================================================================
    // RESPONSE VALIDATION
    // ========================================================================

    /**
     * Validate a response using standard Java Map types.
     *
     * @param payload Response body (JSON string)
     * @param verb    HTTP method (GET, POST, etc.)
     * @param path    Request path
     * @param status  HTTP status code
     * @param headers Response headers as Map (can be null)
     * @return ValidationResult
     */
    public ValidationResult validateResponse(String payload, String verb, String path, int status,
            Map<String, List<String>> headers) {

        if (debugEnabled) {
            debugInfo = new StringBuilder();
            debugInfo.append("=== RESPONSE VALIDATION ===\n");
            debugInfo.append("Verb: ").append(verb).append("\n");
            debugInfo.append("Path: ").append(path).append("\n");
            debugInfo.append("Status: ").append(status).append("\n");
            debugInfo.append("Level: ").append(validationLevel).append("\n");
            logDebugMap("HEADERS", headers);
        }

        ValidationReport report = executeResponseValidation(payload, verb, path, status, headers);
        ValidationResult result = ValidationResult.fromReport(report, validationLevel, "response");

        if (debugEnabled) {
            result.setDebugInfo(debugInfo.toString());
        }

        return result;
    }

    private ValidationReport executeResponseValidation(final String payload, String verb, String path,
            final int status, final Map<String, List<String>> headers) {

        Response response = new Response() {
            @Override
            public int getStatus() {
                return status;
            }

            @Override
            public Collection<String> getHeaderValues(String name) {
                if (headers == null) return Collections.emptyList();
                List<String> values = headers.get(name);
                return (values == null) ? Collections.emptyList() : new ArrayList<>(values);
            }

            @Override
            public Optional<String> getBody() {
                return Optional.ofNullable(payload);
            }
        };

        return validator.validateResponse(path, Request.Method.valueOf(verb.toUpperCase()), response);
    }

    // ========================================================================
    // DEBUG LOGGING
    // ========================================================================

    private void logDebugMap(String label, Map<String, List<String>> map) {
        if (!debugEnabled) return;
        debugInfo.append("=== ").append(label).append(" ===\n");
        if (map == null) {
            debugInfo.append("  (null)\n");
            return;
        }
        int count = 0;
        for (Map.Entry<String, List<String>> entry : map.entrySet()) {
            debugInfo.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            count++;
        }
        if (count == 0) {
            debugInfo.append("  (empty)\n");
        }
    }

    // ========================================================================
    // UTILITY METHODS
    // ========================================================================

    private static String hashSpec(String specContent) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(specContent.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(specContent.hashCode());
        }
    }

    public static void clearCache() {
        validatorCache.clear();
    }

    public static int getCacheSize() {
        return validatorCache.size();
    }

    public ValidationLevel getValidationLevel() {
        return validationLevel;
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    public void setDebugEnabled(boolean debugEnabled) {
        this.debugEnabled = debugEnabled;
    }
}
