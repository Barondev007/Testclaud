package be.bnppf.openapi.validator.apigee;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.Response;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.report.ValidationReport.Message;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenAPI Validator optimized for Google Apigee.
 *
 * This class provides a simple interface for validating HTTP requests and responses
 * against an OpenAPI specification within Apigee Java Callouts or JavaScript policies.
 *
 * Usage in Apigee JavaScript:
 * <pre>
 * var validator = ApigeeOpenApiValidator.getInstance(specContent, "LENIENT");
 * var result = validator.validateRequest(body, "POST", "/users", queryParams, headers);
 * if (result.isBlocked()) {
 *     // Handle validation failure
 * }
 * </pre>
 */
public class ApigeeOpenApiValidator {

    private final OpenApiInteractionValidator validator;
    private final ValidationLevel level;
    private boolean debugEnabled = false;
    private StringBuilder debugLog = new StringBuilder();

    // Cache for validator instances (keyed by spec hash + level)
    private static final Map<String, ApigeeOpenApiValidator> validatorCache = new ConcurrentHashMap<>();

    /**
     * Validation levels supported by the validator
     */
    public enum ValidationLevel {
        /** Strict validation - all spec rules enforced */
        STRICT,
        /** Lenient validation - allows additional properties */
        LENIENT,
        /** Light validation - minimal checking, issues as info */
        LIGHT;

        public static ValidationLevel fromString(String value) {
            if (value == null) return LENIENT;
            try {
                return valueOf(value.toUpperCase());
            } catch (IllegalArgumentException e) {
                return LENIENT;
            }
        }
    }

    /**
     * Result of a validation operation
     */
    public static class ValidationResult {
        private final List<String> errors;
        private final List<String> warnings;
        private final List<String> infos;
        private final boolean blocked;
        private String debugInfo;

        public ValidationResult(List<String> errors, List<String> warnings, List<String> infos, boolean blocked) {
            this.errors = errors != null ? errors : new ArrayList<>();
            this.warnings = warnings != null ? warnings : new ArrayList<>();
            this.infos = infos != null ? infos : new ArrayList<>();
            this.blocked = blocked;
        }

        public List<String> getErrors() { return errors; }
        public List<String> getWarnings() { return warnings; }
        public List<String> getInfos() { return infos; }
        public boolean isBlocked() { return blocked; }
        public String getDebugInfo() { return debugInfo; }
        public void setDebugInfo(String debugInfo) { this.debugInfo = debugInfo; }

        /** Convert to Map for Apigee JavaScript interop */
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("errors", errors);
            map.put("warnings", warnings);
            map.put("infos", infos);
            map.put("blocked", blocked);
            map.put("errorCount", errors.size());
            map.put("warningCount", warnings.size());
            map.put("valid", !blocked);
            if (debugInfo != null) {
                map.put("debugInfo", debugInfo);
            }
            return map;
        }

        /** Convert to JSON string for Apigee */
        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"valid\":").append(!blocked).append(",");
            sb.append("\"blocked\":").append(blocked).append(",");
            sb.append("\"errorCount\":").append(errors.size()).append(",");
            sb.append("\"warningCount\":").append(warnings.size()).append(",");
            sb.append("\"errors\":").append(listToJson(errors)).append(",");
            sb.append("\"warnings\":").append(listToJson(warnings)).append(",");
            sb.append("\"infos\":").append(listToJson(infos));
            sb.append("}");
            return sb.toString();
        }

        private String listToJson(List<String> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(escapeJson(list.get(i))).append("\"");
            }
            sb.append("]");
            return sb.toString();
        }

        private String escapeJson(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }
    }

    private ApigeeOpenApiValidator(String specContent, ValidationLevel level) {
        this.level = level;

        OpenApiInteractionValidator.Builder builder = OpenApiInteractionValidator
                .createForInlineApiSpecification(specContent);

        // Configure based on validation level
        switch (level) {
            case STRICT:
                // No additional properties allowed
                break;
            case LENIENT:
                builder.withLevelResolver(
                    com.atlassian.oai.validator.report.LevelResolver.create()
                        .withLevel("validation.request.body.schema.additionalProperties", ValidationReport.Level.WARN)
                        .withLevel("validation.response.body.schema.additionalProperties", ValidationReport.Level.WARN)
                        .build()
                );
                break;
            case LIGHT:
                builder.withLevelResolver(
                    com.atlassian.oai.validator.report.LevelResolver.create()
                        .withLevel("validation.request.body.schema.additionalProperties", ValidationReport.Level.INFO)
                        .withLevel("validation.response.body.schema.additionalProperties", ValidationReport.Level.INFO)
                        .withLevel("validation.request.body.schema.type", ValidationReport.Level.INFO)
                        .withLevel("validation.request.body.schema.required", ValidationReport.Level.WARN)
                        .build()
                );
                break;
        }

        this.validator = builder.build();
    }

    /**
     * Get or create a validator instance (cached).
     *
     * @param specContent OpenAPI specification as JSON or YAML string
     * @param levelStr Validation level: "STRICT", "LENIENT", or "LIGHT"
     * @return Validator instance
     */
    public static ApigeeOpenApiValidator getInstance(String specContent, String levelStr) {
        ValidationLevel level = ValidationLevel.fromString(levelStr);
        return getInstance(specContent, level);
    }

    /**
     * Get or create a validator instance (cached).
     */
    public static ApigeeOpenApiValidator getInstance(String specContent, ValidationLevel level) {
        String cacheKey = specContent.hashCode() + "_" + level.name();
        return validatorCache.computeIfAbsent(cacheKey, k -> new ApigeeOpenApiValidator(specContent, level));
    }

    /**
     * Create a new validator instance (not cached).
     */
    public static ApigeeOpenApiValidator create(String specContent, String levelStr) {
        ValidationLevel level = ValidationLevel.fromString(levelStr);
        return new ApigeeOpenApiValidator(specContent, level);
    }

    /**
     * Clear the validator cache.
     */
    public static void clearCache() {
        validatorCache.clear();
    }

    /**
     * Enable/disable debug logging.
     */
    public void setDebugEnabled(boolean enabled) {
        this.debugEnabled = enabled;
        this.debugLog = new StringBuilder();
    }

    /**
     * Validate a request.
     *
     * @param body Request body (can be null for GET requests)
     * @param method HTTP method (GET, POST, PUT, DELETE, etc.)
     * @param path Request path (e.g., "/users/123")
     * @param queryParams Query parameters as Map
     * @param headers HTTP headers as Map
     * @return Validation result
     */
    public ValidationResult validateRequest(
            String body,
            String method,
            String path,
            Map<String, List<String>> queryParams,
            Map<String, List<String>> headers) {

        if (debugEnabled) {
            debugLog.append("=== Request Validation ===\n");
            debugLog.append("Method: ").append(method).append("\n");
            debugLog.append("Path: ").append(path).append("\n");
            debugLog.append("Level: ").append(level).append("\n");
        }

        try {
            // Build the request
            SimpleRequest.Builder requestBuilder = new SimpleRequest.Builder(
                    Request.Method.valueOf(method.toUpperCase()),
                    path
            );

            // Add query parameters
            if (queryParams != null) {
                for (Map.Entry<String, List<String>> entry : queryParams.entrySet()) {
                    for (String value : entry.getValue()) {
                        requestBuilder.withQueryParam(entry.getKey(), value);
                    }
                }
            }

            // Add headers
            if (headers != null) {
                for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                    for (String value : entry.getValue()) {
                        requestBuilder.withHeader(entry.getKey(), value);
                    }
                }
            }

            // Add body
            if (body != null && !body.isEmpty()) {
                requestBuilder.withBody(body);
            }

            Request request = requestBuilder.build();
            ValidationReport report = validator.validateRequest(request);

            return processReport(report);

        } catch (Exception e) {
            if (debugEnabled) {
                debugLog.append("ERROR: ").append(e.getMessage()).append("\n");
            }
            List<String> errors = new ArrayList<>();
            errors.add("Validation error: " + e.getMessage());
            ValidationResult result = new ValidationResult(errors, null, null, true);
            result.setDebugInfo(debugLog.toString());
            return result;
        }
    }

    /**
     * Validate a response.
     *
     * @param body Response body
     * @param method HTTP method of the original request
     * @param path Request path
     * @param statusCode HTTP status code (e.g., 200, 404)
     * @param headers Response headers
     * @return Validation result
     */
    public ValidationResult validateResponse(
            String body,
            String method,
            String path,
            int statusCode,
            Map<String, List<String>> headers) {

        if (debugEnabled) {
            debugLog.append("=== Response Validation ===\n");
            debugLog.append("Method: ").append(method).append("\n");
            debugLog.append("Path: ").append(path).append("\n");
            debugLog.append("Status: ").append(statusCode).append("\n");
        }

        try {
            // Build the response
            SimpleResponse.Builder responseBuilder = new SimpleResponse.Builder(statusCode);

            // Add headers
            if (headers != null) {
                for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                    for (String value : entry.getValue()) {
                        responseBuilder.withHeader(entry.getKey(), value);
                    }
                }
            }

            // Add body
            if (body != null && !body.isEmpty()) {
                responseBuilder.withBody(body);
            }

            Response response = responseBuilder.build();
            ValidationReport report = validator.validateResponse(path, Request.Method.valueOf(method.toUpperCase()), response);

            return processReport(report);

        } catch (Exception e) {
            if (debugEnabled) {
                debugLog.append("ERROR: ").append(e.getMessage()).append("\n");
            }
            List<String> errors = new ArrayList<>();
            errors.add("Validation error: " + e.getMessage());
            ValidationResult result = new ValidationResult(errors, null, null, true);
            result.setDebugInfo(debugLog.toString());
            return result;
        }
    }

    /**
     * Simple request validation with minimal parameters.
     * Useful for Apigee JavaScript callouts.
     */
    public ValidationResult validateRequest(String body, String method, String path) {
        return validateRequest(body, method, path, null, null);
    }

    /**
     * Process validation report and create result.
     */
    private ValidationResult processReport(ValidationReport report) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> infos = new ArrayList<>();

        for (Message message : report.getMessages()) {
            String msgText = message.getMessage();
            String key = message.getKey();

            if (debugEnabled) {
                debugLog.append(message.getLevel()).append(": ")
                        .append(key).append(" - ").append(msgText).append("\n");
            }

            switch (message.getLevel()) {
                case ERROR:
                    errors.add(formatMessage(key, msgText));
                    break;
                case WARN:
                    warnings.add(formatMessage(key, msgText));
                    break;
                case INFO:
                    infos.add(formatMessage(key, msgText));
                    break;
                default:
                    // IGNORE level - skip
                    break;
            }
        }

        // Determine if request should be blocked based on level
        boolean blocked = !errors.isEmpty();
        if (level == ValidationLevel.STRICT && !warnings.isEmpty()) {
            blocked = true;
        }

        ValidationResult result = new ValidationResult(errors, warnings, infos, blocked);
        if (debugEnabled) {
            result.setDebugInfo(debugLog.toString());
        }
        return result;
    }

    private String formatMessage(String key, String message) {
        if (key != null && !key.isEmpty()) {
            return "[" + key + "] " + message;
        }
        return message;
    }

    /**
     * Get the validation level.
     */
    public ValidationLevel getValidationLevel() {
        return level;
    }

    /**
     * Check if spec is valid OpenAPI.
     */
    public static boolean isValidSpec(String specContent) {
        try {
            OpenApiInteractionValidator.createForInlineApiSpecification(specContent).build();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
