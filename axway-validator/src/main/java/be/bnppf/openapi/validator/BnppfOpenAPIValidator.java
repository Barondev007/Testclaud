package be.bnppf.openapi.validator;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.Response;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.LevelResolverFactory;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.report.ValidationReport.Message;
import com.vordel.mime.HeaderSet;
import com.vordel.mime.QueryStringHeaderSet;

/**
 * OpenAPI Validator for Axway API Gateway.
 * Uses Axway types (HeaderSet, QueryStringHeaderSet) directly.
 */
public class BnppfOpenAPIValidator {

    private static final ConcurrentHashMap<String, BnppfOpenAPIValidator> validatorCache = new ConcurrentHashMap<>();

    private OpenApiInteractionValidator validator;
    private ValidationLevel validationLevel = ValidationLevel.STRICT;
    private boolean debugEnabled = false;
    private StringBuilder debugInfo;
    private MaxSizeHashMap<String, Object> exposurePath2SpecifiedPathMap = new MaxSizeHashMap<>();

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    public static synchronized BnppfOpenAPIValidator getInstance(String openAPISpec, ValidationLevel level) {
        String cacheKey = hashSpec(openAPISpec) + "|" + level.getValue();
        return validatorCache.computeIfAbsent(cacheKey, key -> new BnppfOpenAPIValidator(openAPISpec, level));
    }

    public static synchronized BnppfOpenAPIValidator getInstance(String openAPISpec) {
        return getInstance(openAPISpec, ValidationLevel.STRICT);
    }

    // ========================================================================
    // CONSTRUCTOR
    // ========================================================================

    private BnppfOpenAPIValidator(String openAPISpec, ValidationLevel level) {
        this.validationLevel = level;
        this.debugInfo = new StringBuilder();
        exposurePath2SpecifiedPathMap.setMaxSize(1000);
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

    public ValidationResult validateRequest(String payload, String verb, String path,
            QueryStringHeaderSet queryParams, HeaderSet headers) {

        if (debugEnabled) {
            debugInfo = new StringBuilder();
            debugInfo.append("=== REQUEST VALIDATION ===\n");
            debugInfo.append("Verb: ").append(verb).append("\n");
            debugInfo.append("Path: ").append(path).append("\n");
            debugInfo.append("Level: ").append(validationLevel).append("\n");
            logDebugHeaders(headers);
            logDebugQueryParams(queryParams);
        }

        ValidationReport report = performRequestValidation(payload, verb, path, queryParams, headers);
        ValidationResult result = ValidationResult.fromReport(report, validationLevel, "request");

        if (debugEnabled) {
            result.setDebugInfo(debugInfo.toString());
        }

        if (report.hasErrors()) {
            for (Message message : report.getMessages()) {
                logMessage(message);
            }
        }

        return result;
    }

    private ValidationReport performRequestValidation(final String payload, final String verb, String path,
            final QueryStringHeaderSet queryParams, final HeaderSet headers) {

        ValidationReport validationReport = null;
        String originalPath = path;
        boolean cachePath = false;

        if (exposurePath2SpecifiedPathMap.containsKey(path)) {
            Object cached = exposurePath2SpecifiedPathMap.get(path);
            if (cached instanceof ValidationReport) {
                return (ValidationReport) cached;
            } else {
                return executeRequestValidation(payload, verb, (String) cached, queryParams, headers);
            }
        }

        for (int i = 0; i < 5; i++) {
            validationReport = executeRequestValidation(payload, verb, path, queryParams, headers);

            if (validationReport.hasErrors()) {
                if (validationReport.getMessages().toString().contains("No API path found that matches request")) {
                    cachePath = true;
                    if (path.indexOf("/", 1) == -1) {
                        break;
                    } else {
                        path = path.substring(path.indexOf("/", 1));
                    }
                } else {
                    break;
                }
            } else {
                break;
            }
        }

        if (cachePath) {
            if (validationReport.hasErrors() &&
                validationReport.getMessages().toString().contains("No API path found that matches request")) {
                exposurePath2SpecifiedPathMap.put(originalPath, validationReport);
            } else {
                exposurePath2SpecifiedPathMap.put(originalPath, path);
            }
        }

        return validationReport;
    }

    private ValidationReport executeRequestValidation(final String payload, final String verb, final String path,
            final QueryStringHeaderSet queryParams, final HeaderSet headers) {

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
                return queryParams.getHeaderNames();
            }

            @Override
            public Collection<String> getQueryParameterValues(String name) {
                if (queryParams == null) return Collections.emptyList();
                ArrayList<String> values = queryParams.getHeaderValues(name);
                if (values == null) return Collections.emptyList();
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
                return convertHeaderSetToMap(headers);
            }

            @Override
            public Collection<String> getHeaderValues(String name) {
                if (headers == null) return Collections.emptyList();
                ArrayList<String> values = headers.getHeaderValues(name);
                return (values == null) ? Collections.emptyList() : values;
            }
        };

        return validator.validateRequest(request);
    }

    // ========================================================================
    // RESPONSE VALIDATION
    // ========================================================================

    public ValidationResult validateResponse(String payload, String verb, String path, int status, HeaderSet headers) {

        if (debugEnabled) {
            debugInfo = new StringBuilder();
            debugInfo.append("=== RESPONSE VALIDATION ===\n");
            debugInfo.append("Verb: ").append(verb).append("\n");
            debugInfo.append("Path: ").append(path).append("\n");
            debugInfo.append("Status: ").append(status).append("\n");
            debugInfo.append("Level: ").append(validationLevel).append("\n");
            logDebugHeaders(headers);
        }

        ValidationReport report = executeResponseValidation(payload, verb, path, status, headers);
        ValidationResult result = ValidationResult.fromReport(report, validationLevel, "response");

        if (debugEnabled) {
            result.setDebugInfo(debugInfo.toString());
        }

        if (report.hasErrors()) {
            for (Message message : report.getMessages()) {
                logMessage(message);
            }
        }

        return result;
    }

    private ValidationReport executeResponseValidation(final String payload, String verb, String path,
            final int status, final HeaderSet headers) {

        Response response = new Response() {
            @Override
            public int getStatus() {
                return status;
            }

            @Override
            public Collection<String> getHeaderValues(String name) {
                if (headers == null) return Collections.emptyList();
                ArrayList<String> values = headers.getHeaderValues(name);
                return (values == null) ? Collections.emptyList() : values;
            }

            @Override
            public Optional<String> getBody() {
                return Optional.ofNullable(payload);
            }
        };

        return validator.validateResponse(path, Request.Method.valueOf(verb.toUpperCase()), response);
    }

    // ========================================================================
    // CONVERSION FOR ATLASSIAN VALIDATOR
    // ========================================================================

    private Map<String, Collection<String>> convertHeaderSetToMap(HeaderSet headers) {
        if (headers == null) return Collections.emptyMap();
        Map<String, Collection<String>> result = new LinkedHashMap<>();
        Collection<String> names = headers.getHeaderNames();
        if (names != null) {
            for (String name : names) {
                ArrayList<String> values = headers.getHeaderValues(name);
                if (values != null) {
                    result.put(name, values);
                }
            }
        }
        return result;
    }

    // ========================================================================
    // DEBUG LOGGING
    // ========================================================================

    private void logDebugHeaders(HeaderSet headers) {
        if (!debugEnabled) return;
        debugInfo.append("=== HEADERS ===\n");
        if (headers == null) {
            debugInfo.append("  (null)\n");
            return;
        }
        Collection<String> names = headers.getHeaderNames();
        if (names == null || names.isEmpty()) {
            debugInfo.append("  (empty)\n");
            return;
        }
        debugInfo.append("  Count: ").append(names.size()).append("\n");
        for (String name : names) {
            debugInfo.append("  ").append(name).append(": ").append(headers.getHeaderValues(name)).append("\n");
        }
    }

    private void logDebugQueryParams(QueryStringHeaderSet queryParams) {
        if (!debugEnabled) return;
        debugInfo.append("=== QUERY PARAMS ===\n");
        if (queryParams == null) {
            debugInfo.append("  (null)\n");
            return;
        }
        Collection<String> names = queryParams.getHeaderNames();
        if (names == null || names.isEmpty()) {
            debugInfo.append("  (empty)\n");
            return;
        }
        debugInfo.append("  Count: ").append(names.size()).append("\n");
        for (String name : names) {
            debugInfo.append("  ").append(name).append(": ").append(queryParams.getHeaderValues(name)).append("\n");
        }
    }

    private void logMessage(Message message) {
        try {
            Class<?> traceClass = Class.forName("com.vordel.trace.Trace");
            java.lang.reflect.Method method = traceClass.getMethod("info", String.class);
            method.invoke(null, message.getMessage());
        } catch (Exception e) {
            // Ignore
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

    // ========================================================================
    // INNER CLASSES
    // ========================================================================

    static class MaxSizeHashMap<K, V> extends LinkedHashMap<K, V> {
        private static final long serialVersionUID = 1L;
        private int maxSize;

        @Override
        protected boolean removeEldestEntry(Entry<K, V> eldest) {
            return size() > maxSize;
        }

        public void setMaxSize(int maxSize) {
            clear();
            this.maxSize = maxSize;
        }
    }
}
