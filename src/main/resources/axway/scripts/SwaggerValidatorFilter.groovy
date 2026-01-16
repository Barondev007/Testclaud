import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.SimpleRequest
import com.atlassian.oai.validator.report.LevelResolverFactory
import com.atlassian.oai.validator.report.ValidationReport
import java.util.concurrent.ConcurrentHashMap

/**
 * Axway API Gateway - Swagger Request Validator Filter
 *
 * Features:
 * - Spec path passed as parameter (supports multiple APIs)
 * - Thread-safe validator caching (one validator per spec)
 * - Lenient validation (allows additional properties)
 *
 * Required Axway Message Attributes (set before this filter):
 * - openapi.spec.path : Path to the OpenAPI spec file (e.g., "file:///path/to/spec.yaml")
 *
 * Optional Attributes:
 * - openapi.allow.additional.properties : "true" or "false" (default: "true")
 */

// ============================================================================
// VALIDATOR CACHE - Thread-safe, one validator per spec
// ============================================================================

class ValidatorCache {
    // ConcurrentHashMap for thread-safe access across multiple requests
    private static final ConcurrentHashMap<String, OpenApiInteractionValidator> cache = new ConcurrentHashMap<>()

    /**
     * Get or create a validator for the given spec path.
     * Thread-safe: multiple threads can safely call this method.
     */
    static OpenApiInteractionValidator getValidator(String specPath, boolean allowAdditionalProperties) {
        // Create cache key that includes the configuration
        String cacheKey = specPath + "|" + allowAdditionalProperties

        // computeIfAbsent is atomic and thread-safe
        return cache.computeIfAbsent(cacheKey) { key ->
            createValidator(specPath, allowAdditionalProperties)
        }
    }

    /**
     * Create a new validator instance.
     */
    private static OpenApiInteractionValidator createValidator(String specPath, boolean allowAdditionalProperties) {
        def builder = OpenApiInteractionValidator.createForSpecificationUrl(specPath)

        if (allowAdditionalProperties) {
            builder.withLevelResolver(LevelResolverFactory.withAdditionalPropertiesIgnored())
        }

        return builder.build()
    }

    /**
     * Clear the cache (useful for reloading specs).
     */
    static void clearCache() {
        cache.clear()
    }

    /**
     * Remove a specific spec from the cache.
     */
    static void invalidate(String specPath) {
        cache.keySet().removeIf { it.startsWith(specPath + "|") }
    }

    /**
     * Get cache statistics.
     */
    static int getCacheSize() {
        return cache.size()
    }
}

// ============================================================================
// MAIN VALIDATION LOGIC
// ============================================================================

def invoke(Message msg) {
    try {
        // Get spec path from message attribute (set by previous filter or API Manager)
        def specPath = msg.get("openapi.spec.path")

        if (specPath == null || specPath.isEmpty()) {
            Trace.error("OpenAPI spec path not configured. Set 'openapi.spec.path' attribute.")
            msg.put("openapi.validation.error", "OpenAPI spec path not configured")
            return false
        }

        // Get configuration (default: allow additional properties)
        def allowAdditionalPropsStr = msg.get("openapi.allow.additional.properties") ?: "true"
        def allowAdditionalProperties = "true".equalsIgnoreCase(allowAdditionalPropsStr)

        // Get cached validator (thread-safe)
        def validator = ValidatorCache.getValidator(specPath, allowAdditionalProperties)

        // Extract request details
        def httpMethod = msg.get("http.request.verb") ?: "GET"
        def requestPath = msg.get("http.request.path") ?: "/"
        def contentType = msg.get("content.type") ?: "application/json"
        def requestBody = extractBody(msg)

        // Build request for validation
        def requestBuilder = new SimpleRequest.Builder(httpMethod, requestPath)

        if (requestBody != null && !requestBody.isEmpty()) {
            requestBuilder.withBody(requestBody)
            requestBuilder.withContentType(contentType)
        }

        // Add query parameters
        def queryString = msg.get("http.request.querystring")
        if (queryString != null && !queryString.isEmpty()) {
            parseQueryParams(queryString).each { key, values ->
                values.each { value ->
                    requestBuilder.withQueryParam(key, value)
                }
            }
        }

        // Validate request
        def report = validator.validateRequest(requestBuilder.build())

        if (report.hasErrors()) {
            def errorMessage = formatErrors(report)
            Trace.error("Validation Failed [" + specPath + "]: " + errorMessage)
            msg.put("openapi.validation.error", errorMessage)
            msg.put("openapi.validation.failed", "true")
            return false
        }

        Trace.debug("Validation Passed [" + specPath + "]: " + httpMethod + " " + requestPath)
        msg.put("openapi.validation.failed", "false")
        return true

    } catch (Exception e) {
        Trace.error("Validation Exception: " + e.getMessage())
        e.printStackTrace()
        msg.put("openapi.validation.error", "Internal validation error: " + e.getMessage())
        return false
    }
}

// ============================================================================
// HELPER METHODS
// ============================================================================

def extractBody(Message msg) {
    try {
        def body = msg.get("content.body")
        if (body == null) return null

        if (body.metaClass.respondsTo(body, "getInputStream")) {
            def is = body.getInputStream()
            if (is != null) {
                return is.getText("UTF-8")
            }
        }
        return body.toString()
    } catch (Exception e) {
        Trace.debug("Could not extract body: " + e.getMessage())
        return null
    }
}

def parseQueryParams(String queryString) {
    def params = [:]
    queryString.split("&").each { param ->
        def parts = param.split("=", 2)
        def key = URLDecoder.decode(parts[0], "UTF-8")
        def value = parts.length > 1 ? URLDecoder.decode(parts[1], "UTF-8") : ""
        if (!params.containsKey(key)) {
            params[key] = []
        }
        params[key].add(value)
    }
    return params
}

def formatErrors(ValidationReport report) {
    def errors = []
    report.getMessages().each { message ->
        if (message.getLevel() == ValidationReport.Level.ERROR) {
            errors.add("[" + message.getKey() + "] " + message.getMessage())
        }
    }
    return errors.join("; ")
}

// Execute
return invoke(msg)
