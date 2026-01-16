import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.SimpleRequest
import com.atlassian.oai.validator.report.LevelResolverFactory
import com.atlassian.oai.validator.report.ValidationReport
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest

/**
 * Axway API Gateway - Swagger Request Validator Filter
 *
 * Features:
 * - Spec content passed directly as parameter (not from file path)
 * - Thread-safe validator caching (one validator per unique spec)
 * - Lenient validation (allows additional properties)
 *
 * Required Axway Message Attributes:
 * - specfile : The OpenAPI spec content as a String (YAML or JSON)
 *
 * Optional Attributes:
 * - openapi.allow.additional.properties : "true" or "false" (default: "true")
 */

// ============================================================================
// VALIDATOR CACHE - Thread-safe, one validator per unique spec content
// ============================================================================

class ValidatorCache {
    // ConcurrentHashMap for thread-safe access across multiple requests
    // Key: hash of spec content + config, Value: validator instance
    private static final ConcurrentHashMap<String, OpenApiInteractionValidator> cache = new ConcurrentHashMap<>()

    /**
     * Get or create a validator for the given spec content.
     * Uses MD5 hash of spec content as cache key for efficiency.
     */
    static OpenApiInteractionValidator getValidator(String specContent, boolean allowAdditionalProperties) {
        // Create cache key from hash of spec content + configuration
        String specHash = hashSpec(specContent)
        String cacheKey = specHash + "|" + allowAdditionalProperties

        // computeIfAbsent is atomic and thread-safe
        return cache.computeIfAbsent(cacheKey) { key ->
            createValidator(specContent, allowAdditionalProperties)
        }
    }

    /**
     * Create MD5 hash of spec content for cache key.
     * This avoids storing large spec strings as keys.
     */
    private static String hashSpec(String specContent) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5")
            byte[] digest = md.digest(specContent.getBytes("UTF-8"))
            StringBuilder sb = new StringBuilder()
            for (byte b : digest) {
                sb.append(String.format("%02x", b))
            }
            return sb.toString()
        } catch (Exception e) {
            // Fallback to hashCode if MD5 fails
            return String.valueOf(specContent.hashCode())
        }
    }

    /**
     * Create a new validator instance from spec content.
     */
    private static OpenApiInteractionValidator createValidator(String specContent, boolean allowAdditionalProperties) {
        def builder = OpenApiInteractionValidator.createForInlineApiSpecification(specContent)

        if (allowAdditionalProperties) {
            builder.withLevelResolver(LevelResolverFactory.withAdditionalPropertiesIgnored())
        }

        return builder.build()
    }

    /**
     * Clear the entire cache (useful for memory management or spec updates).
     */
    static void clearCache() {
        cache.clear()
    }

    /**
     * Get cache size for monitoring.
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
        // Get spec content from message attribute
        def specContent = msg.get("specfile")

        if (specContent == null || specContent.isEmpty()) {
            Trace.error("OpenAPI spec not provided. Set 'specfile' attribute with spec content.")
            msg.put("openapi.validation.error", "OpenAPI spec not provided")
            return false
        }

        // Get configuration (default: allow additional properties)
        def allowAdditionalPropsStr = msg.get("openapi.allow.additional.properties") ?: "true"
        def allowAdditionalProperties = "true".equalsIgnoreCase(allowAdditionalPropsStr)

        // Get cached validator (thread-safe, reuses instance for same spec)
        def validator = ValidatorCache.getValidator(specContent, allowAdditionalProperties)

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
            Trace.error("Validation Failed: " + errorMessage)
            msg.put("openapi.validation.error", errorMessage)
            msg.put("openapi.validation.failed", "true")
            return false
        }

        Trace.debug("Validation Passed: " + httpMethod + " " + requestPath)
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
