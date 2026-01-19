import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.SimpleRequest
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.LevelResolverFactory
import com.atlassian.oai.validator.report.ValidationReport
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest

/**
 * Axway API Gateway - Swagger Request Validator Filter
 *
 * Features:
 * - Spec content passed directly as parameter (specfile attribute)
 * - Thread-safe validator caching (one validator per unique spec + validation level)
 * - Configurable validation levels
 *
 * Required Axway Message Attributes:
 * - specfile : The OpenAPI spec content as a String (YAML or JSON)
 *
 * Optional Attributes:
 * - openapi.validation.level : Validation level (default: "strict")
 *     - "light"    : All errors stored in variable, flow NOT blocked
 *     - "lenient"  : Additional properties ignored, other errors block flow
 *     - "strict"   : All errors block the flow
 *
 * Output Attributes:
 * - openapi.validation.failed : "true" or "false"
 * - openapi.validation.error : Error message(s) if validation failed
 * - openapi.validation.errors.count : Number of validation errors
 * - openapi.validation.errors.all : All error messages (for light mode)
 */

// ============================================================================
// VALIDATION LEVELS
// ============================================================================

class ValidationLevel {
    static final String LIGHT = "light"       // Non-blocking, all errors stored
    static final String LENIENT = "lenient"   // Additional properties allowed, other errors block
    static final String STRICT = "strict"     // All errors block
}

// ============================================================================
// VALIDATOR CACHE - Thread-safe, one validator per unique spec + level
// ============================================================================

class ValidatorCache {
    private static final ConcurrentHashMap<String, OpenApiInteractionValidator> cache = new ConcurrentHashMap<>()

    static OpenApiInteractionValidator getValidator(String specContent, String validationLevel) {
        String specHash = hashSpec(specContent)
        String cacheKey = specHash + "|" + validationLevel

        return cache.computeIfAbsent(cacheKey) { key ->
            createValidator(specContent, validationLevel)
        }
    }

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
            return String.valueOf(specContent.hashCode())
        }
    }

    private static OpenApiInteractionValidator createValidator(String specContent, String validationLevel) {
        def builder = OpenApiInteractionValidator.createForInlineApiSpecification(specContent)

        // Apply level resolver based on validation level
        switch (validationLevel) {
            case ValidationLevel.LIGHT:
                // Light mode: Demote all errors to WARN so hasErrors() returns false
                // but messages are still collected
                builder.withLevelResolver(createLightLevelResolver())
                break

            case ValidationLevel.LENIENT:
                // Lenient mode: Only ignore additional properties
                builder.withLevelResolver(LevelResolverFactory.withAdditionalPropertiesIgnored())
                break

            case ValidationLevel.STRICT:
            default:
                // Strict mode: No level resolver, all errors are reported as-is
                break
        }

        return builder.build()
    }

    /**
     * Creates a LevelResolver that demotes all errors to INFO level
     * This allows collecting all messages without blocking the flow
     */
    private static LevelResolver createLightLevelResolver() {
        return LevelResolver.create()
            // Demote all common validation errors to INFO (non-blocking)
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
            .build()
    }

    static void clearCache() {
        cache.clear()
    }

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
            Trace.error("OpenAPI spec not provided. Set 'specfile' attribute.")
            msg.put("openapi.validation.error", "OpenAPI spec not provided")
            msg.put("openapi.validation.failed", "true")
            return false
        }

        // Get validation level (default: strict)
        def validationLevel = msg.get("openapi.validation.level") ?: ValidationLevel.STRICT
        validationLevel = validationLevel.toLowerCase().trim()

        // Validate the level parameter
        if (![ValidationLevel.LIGHT, ValidationLevel.LENIENT, ValidationLevel.STRICT].contains(validationLevel)) {
            Trace.warn("Invalid validation level '${validationLevel}', defaulting to 'strict'")
            validationLevel = ValidationLevel.STRICT
        }

        // Get cached validator
        def validator = ValidatorCache.getValidator(specContent.trim(), validationLevel)

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

        // Collect all messages (errors, warnings, info)
        def allMessages = collectAllMessages(report)
        def errorCount = allMessages.size()

        // Store all messages
        msg.put("openapi.validation.errors.all", allMessages.join("; "))
        msg.put("openapi.validation.errors.count", String.valueOf(errorCount))

        // Handle based on validation level
        switch (validationLevel) {
            case ValidationLevel.LIGHT:
                // Light mode: Store errors but don't block
                if (errorCount > 0) {
                    msg.put("openapi.validation.error", allMessages.join("; "))
                    msg.put("openapi.validation.failed", "true")
                    Trace.info("Validation issues (light mode, non-blocking): " + allMessages.join("; "))
                } else {
                    msg.put("openapi.validation.failed", "false")
                }
                // Always return true in light mode (non-blocking)
                return true

            case ValidationLevel.LENIENT:
            case ValidationLevel.STRICT:
            default:
                // Lenient/Strict mode: Block on errors
                if (report.hasErrors()) {
                    def errorMessage = formatErrors(report)
                    msg.put("openapi.validation.error", errorMessage)
                    msg.put("openapi.validation.failed", "true")
                    Trace.error("Validation Failed: " + errorMessage)
                    return false
                }
                msg.put("openapi.validation.failed", "false")
                return true
        }

    } catch (Exception e) {
        Trace.error("Validation Exception: " + e.getMessage())
        e.printStackTrace()
        msg.put("openapi.validation.error", "Internal validation error: " + e.getMessage())
        msg.put("openapi.validation.failed", "true")
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

        // Handle different Axway body types
        def bodyClassName = body.getClass().getName()

        // JSONBody - use getJSON() to get the JSON object, then convert to string
        if (bodyClassName.contains("JSONBody")) {
            def json = body.getJSON()
            if (json != null) {
                return json.toString()
            }
        }

        // XMLBody - use getDocument() or toString()
        if (bodyClassName.contains("XMLBody")) {
            return body.toString()
        }

        // Try to get content as string directly
        if (body.metaClass.respondsTo(body, "getContentAsString")) {
            return body.getContentAsString()
        }

        // Try to get as bytes and convert
        if (body.metaClass.respondsTo(body, "getContent")) {
            def content = body.getContent()
            if (content instanceof byte[]) {
                return new String(content, "UTF-8")
            }
            return content?.toString()
        }

        // Try InputStream (for generic Body types)
        if (body.metaClass.respondsTo(body, "getInputStream")) {
            def is = body.getInputStream(null)  // Axway may require content type param
            if (is != null) {
                return is.getText("UTF-8")
            }
        }

        // Fallback: try toString()
        return body.toString()

    } catch (Exception e) {
        Trace.debug("Could not extract body: " + e.getClass().getName() + " - " + e.getMessage())
        // Try alternative: get raw content from message
        try {
            def rawContent = msg.get("content")
            if (rawContent != null) {
                return rawContent.toString()
            }
        } catch (Exception e2) {
            Trace.debug("Could not extract raw content: " + e2.getMessage())
        }
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

/**
 * Collect all messages (INFO level and above) for light mode
 */
def collectAllMessages(ValidationReport report) {
    def messages = []
    report.getMessages().each { message ->
        // Collect INFO, WARN, and ERROR messages
        if (message.getLevel() in [ValidationReport.Level.INFO,
                                    ValidationReport.Level.WARN,
                                    ValidationReport.Level.ERROR]) {
            messages.add("[" + message.getKey() + "] " + message.getMessage())
        }
    }
    return messages
}

/**
 * Format only ERROR level messages
 */
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
