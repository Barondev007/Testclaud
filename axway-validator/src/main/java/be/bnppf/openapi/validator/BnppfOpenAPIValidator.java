package be.bnppf.openapi.validator;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Supports both Axway types (HeaderSet, QueryStringHeaderSet) and Map types.
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
    // REQUEST VALIDATION - Object parameters (flexible for Groovy)
    // ========================================================================

    public ValidationResult validateRequest(String payload, String verb, String path,
            Object queryParams, Object headers) {

        if (debugEnabled) {
            debugInfo = new StringBuilder();
            debugInfo.append("=== REQUEST VALIDATION ===\n");
            debugInfo.append("Verb: ").append(verb).append("\n");
            debugInfo.append("Path: ").append(path).append("\n");
            debugInfo.append("Level: ").append(validationLevel).append("\n");
            debugInfo.append("QueryParams type: ").append(queryParams != null ? queryParams.getClass().getName() : "null").append("\n");
            debugInfo.append("Headers type: ").append(headers != null ? headers.getClass().getName() : "null").append("\n");
        }

        // Convert to maps for internal processing
        Map<String, Collection<String>> headersMap = convertToMap(headers);
        Map<String, Collection<String>> queryParamsMap = convertToMap(queryParams);

        if (debugEnabled) {
            logDebugMap("HEADERS", headersMap);
            logDebugMap("QUERY PARAMS", queryParamsMap);
        }

        ValidationReport report = performRequestValidation(payload, verb, path, queryParamsMap, headersMap);
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
            final Map<String, Collection<String>> queryParams, final Map<String, Collection<String>> headers) {

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
            final Map<String, Collection<String>> queryParams, final Map<String, Collection<String>> headers) {

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
                Collection<String> values = queryParams.get(name);
                if (values == null) return Collections.emptyList();
                // URL decode values
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
                return headers;
            }

            @Override
            public Collection<String> getHeaderValues(String name) {
                if (headers == null) return Collections.emptyList();
                Collection<String> values = headers.get(name);
                return (values == null) ? Collections.emptyList() : values;
            }
        };

        return validator.validateRequest(request);
    }

    // ========================================================================
    // RESPONSE VALIDATION - Object parameters (flexible for Groovy)
    // ========================================================================

    public ValidationResult validateResponse(String payload, String verb, String path, int status, Object headers) {

        if (debugEnabled) {
            debugInfo = new StringBuilder();
            debugInfo.append("=== RESPONSE VALIDATION ===\n");
            debugInfo.append("Verb: ").append(verb).append("\n");
            debugInfo.append("Path: ").append(path).append("\n");
            debugInfo.append("Status: ").append(status).append("\n");
            debugInfo.append("Level: ").append(validationLevel).append("\n");
            debugInfo.append("Headers type: ").append(headers != null ? headers.getClass().getName() : "null").append("\n");
        }

        Map<String, Collection<String>> headersMap = convertToMap(headers);

        if (debugEnabled) {
            logDebugMap("HEADERS", headersMap);
        }

        ValidationReport report = executeResponseValidation(payload, verb, path, status, headersMap);
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
            final int status, final Map<String, Collection<String>> headers) {

        Response response = new Response() {
            @Override
            public int getStatus() {
                return status;
            }

            @Override
            public Collection<String> getHeaderValues(String name) {
                if (headers == null) return Collections.emptyList();
                Collection<String> values = headers.get(name);
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
    // TYPE CONVERSION
    // ========================================================================

    @SuppressWarnings("unchecked")
    private Map<String, Collection<String>> convertToMap(Object obj) {
        if (obj == null) {
            return Collections.emptyMap();
        }

        // Already a Map
        if (obj instanceof Map) {
            Map<String, Collection<String>> result = new LinkedHashMap<>();
            Map<?, ?> map = (Map<?, ?>) obj;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey().toString();
                Object value = entry.getValue();
                if (value instanceof Collection) {
                    ArrayList<String> values = new ArrayList<>();
                    for (Object v : (Collection<?>) value) {
                        values.add(v.toString());
                    }
                    result.put(key, values);
                } else if (value != null) {
                    ArrayList<String> values = new ArrayList<>();
                    values.add(value.toString());
                    result.put(key, values);
                }
            }
            return result;
        }

        // Try to handle HeaderSet-like objects using reflection
        Map<String, Collection<String>> result = new LinkedHashMap<>();
        try {
            // Get the method to retrieve header values
            java.lang.reflect.Method getValuesMethod = findMethod(obj, "getHeaderValues", String.class);
            if (getValuesMethod == null) {
                getValuesMethod = findMethod(obj, "getValues", String.class);
            }
            if (getValuesMethod == null) {
                return Collections.emptyMap();
            }

            // Get header names - try multiple approaches
            Collection<String> headerNames = getHeaderNames(obj);
            if (headerNames == null || headerNames.isEmpty()) {
                return Collections.emptyMap();
            }

            // Build the map
            for (String headerName : headerNames) {
                Object values = getValuesMethod.invoke(obj, headerName);
                if (values instanceof Collection) {
                    ArrayList<String> valueList = new ArrayList<>();
                    for (Object v : (Collection<?>) values) {
                        valueList.add(v.toString());
                    }
                    result.put(headerName, valueList);
                } else if (values != null) {
                    ArrayList<String> valueList = new ArrayList<>();
                    valueList.add(values.toString());
                    result.put(headerName, valueList);
                }
            }
            return result;
        } catch (Exception e) {
            if (debugEnabled) {
                debugInfo.append("  Error converting headers: ").append(e.getMessage()).append("\n");
            }
            return Collections.emptyMap();
        }
    }

    private java.lang.reflect.Method findMethod(Object obj, String name, Class<?>... paramTypes) {
        try {
            return obj.getClass().getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Collection<String> getHeaderNames(Object obj) {
        // Try Iterable first (for-each loop support)
        if (obj instanceof Iterable) {
            ArrayList<String> names = new ArrayList<>();
            for (Object name : (Iterable<?>) obj) {
                names.add(name.toString());
            }
            if (!names.isEmpty()) {
                return names;
            }
        }

        // Try various method names that might return header names
        String[] methodNames = {"getHeaderNames", "getNames", "keySet", "getHeaderSet", "names"};
        for (String methodName : methodNames) {
            try {
                java.lang.reflect.Method method = obj.getClass().getMethod(methodName);
                Object result = method.invoke(obj);
                if (result instanceof Collection) {
                    ArrayList<String> names = new ArrayList<>();
                    for (Object name : (Collection<?>) result) {
                        names.add(name.toString());
                    }
                    return names;
                } else if (result instanceof Iterable) {
                    ArrayList<String> names = new ArrayList<>();
                    for (Object name : (Iterable<?>) result) {
                        names.add(name.toString());
                    }
                    return names;
                }
            } catch (Exception e) {
                // Try next method
            }
        }

        return Collections.emptyList();
    }

    // ========================================================================
    // DEBUG LOGGING
    // ========================================================================

    private void logDebugMap(String name, Map<String, Collection<String>> map) {
        if (!debugEnabled) return;
        debugInfo.append("=== ").append(name).append(" ===\n");
        if (map == null || map.isEmpty()) {
            debugInfo.append("  (empty)\n");
            return;
        }
        debugInfo.append("  Count: ").append(map.size()).append("\n");
        for (Map.Entry<String, Collection<String>> entry : map.entrySet()) {
            debugInfo.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
    }

    private void logMessage(Message message) {
        try {
            Class<?> traceClass = Class.forName("com.vordel.trace.Trace");
            java.lang.reflect.Method method = traceClass.getMethod("info", String.class);
            method.invoke(null, message.getMessage());
        } catch (Exception e) {
            // Ignore - not in Axway environment
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

    // ========================================================================
    // GETTERS AND SETTERS
    // ========================================================================

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
