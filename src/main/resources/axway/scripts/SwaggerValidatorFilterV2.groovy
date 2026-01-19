import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleRequest
import com.atlassian.oai.validator.model.SimpleResponse
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.LevelResolverFactory
import com.atlassian.oai.validator.report.ValidationReport
import java.util.concurrent.ConcurrentHashMap
import java.security.MessageDigest

/**
 * Axway API Gateway - OpenAPI Request/Response Validator
 *
 * Features:
 * - Automatic request/response detection based on http.response.status
 * - Configurable validation levels (light, lenient, strict)
 * - Thread-safe validator caching
 * - Header merging support
 *
 * Input Attributes:
 * - specfile                  : (Required) OpenAPI spec content (YAML or JSON)
 * - content.body              : Payload to validate (request or response body)
 * - http.request.verb         : HTTP method (GET, POST, PUT, DELETE, etc.)
 * - http.request.path         : Request path (e.g., /users/123)
 * - http.header               : Headers map/object
 * - http.content.header       : Extra headers to merge (optional)
 * - param.query               : Query parameters map/object
 * - http.response.status      : Response status code (if set, validates as response)
 * - openapi.validation.level  : Validation level (light, lenient, strict - default: strict)
 *
 * Output Attributes:
 * - openapi.validation.failed       : "true" or "false"
 * - openapi.validation.error        : Error message(s)
 * - openapi.validation.errors.count : Number of errors
 * - openapi.validation.errors.all   : All error messages
 * - openapi.validation.type         : "request" or "response"
 */

// ============================================================================
// VALIDATION LEVELS
// ============================================================================

class ValidationLevel {
    static final String LIGHT = "light"
    static final String LENIENT = "lenient"
    static final String STRICT = "strict"
}

// ============================================================================
// VALIDATOR CACHE
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

        switch (validationLevel) {
            case ValidationLevel.LIGHT:
                builder.withLevelResolver(createLightLevelResolver())
                break
            case ValidationLevel.LENIENT:
                builder.withLevelResolver(LevelResolverFactory.withAdditionalPropertiesIgnored())
                break
            case ValidationLevel.STRICT:
            default:
                break
        }

        return builder.build()
    }

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
        // Get spec content
        def specContent = msg.get("specfile")

        if (specContent == null || specContent.isEmpty()) {
            Trace.error("OpenAPI spec not provided. Set 'specfile' attribute.")
            msg.put("openapi.validation.error", "OpenAPI spec not provided")
            msg.put("openapi.validation.failed", "true")
            return false
        }

        // Get validation level
        def validationLevel = msg.get("openapi.validation.level") ?: ValidationLevel.STRICT
        validationLevel = validationLevel.toLowerCase().trim()

        if (![ValidationLevel.LIGHT, ValidationLevel.LENIENT, ValidationLevel.STRICT].contains(validationLevel)) {
            Trace.warn("Invalid validation level '${validationLevel}', defaulting to 'strict'")
            validationLevel = ValidationLevel.STRICT
        }

        // Get cached validator
        def validator = ValidatorCache.getValidator(specContent.trim(), validationLevel)

        // Detect if this is a request or response validation
        def responseStatus = msg.get("http.response.status")
        def isResponseValidation = (responseStatus != null && !responseStatus.toString().isEmpty())

        msg.put("openapi.validation.type", isResponseValidation ? "response" : "request")

        // Extract common data
        def httpMethod = msg.get("http.request.verb") ?: "GET"
        def requestPath = msg.get("http.request.path") ?: "/"
        def body = extractBody(msg)
        def contentType = extractContentType(msg)
        def headers = mergeHeaders(msg)
        def queryParams = extractQueryParams(msg)

        ValidationReport report

        if (isResponseValidation) {
            // Response validation
            int statusCode = parseStatusCode(responseStatus)
            report = validateResponse(validator, httpMethod, requestPath, statusCode, body, contentType, headers)
        } else {
            // Request validation
            report = validateRequest(validator, httpMethod, requestPath, body, contentType, headers, queryParams)
        }

        // Collect all messages
        def allMessages = collectAllMessages(report)
        def errorCount = allMessages.size()

        msg.put("openapi.validation.errors.all", allMessages.join("; "))
        msg.put("openapi.validation.errors.count", String.valueOf(errorCount))

        // Handle based on validation level
        switch (validationLevel) {
            case ValidationLevel.LIGHT:
                if (errorCount > 0) {
                    msg.put("openapi.validation.error", allMessages.join("; "))
                    msg.put("openapi.validation.failed", "true")
                    Trace.info("Validation issues (light mode, non-blocking): " + allMessages.join("; "))
                } else {
                    msg.put("openapi.validation.failed", "false")
                }
                return true

            case ValidationLevel.LENIENT:
            case ValidationLevel.STRICT:
            default:
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
// REQUEST VALIDATION
// ============================================================================

def validateRequest(OpenApiInteractionValidator validator, String httpMethod, String requestPath,
                    String body, String contentType, Map<String, String> headers, Map<String, List<String>> queryParams) {

    def requestBuilder = new SimpleRequest.Builder(httpMethod, requestPath)

    // Add body
    if (body != null && !body.isEmpty()) {
        requestBuilder.withBody(body)
        if (contentType != null) {
            requestBuilder.withContentType(contentType)
        }
    }

    // Add headers
    headers.each { name, value ->
        requestBuilder.withHeader(name, value)
    }

    // Add query parameters
    queryParams.each { name, values ->
        values.each { value ->
            requestBuilder.withQueryParam(name, value)
        }
    }

    return validator.validateRequest(requestBuilder.build())
}

// ============================================================================
// RESPONSE VALIDATION
// ============================================================================

def validateResponse(OpenApiInteractionValidator validator, String httpMethod, String requestPath,
                     int statusCode, String body, String contentType, Map<String, String> headers) {

    def responseBuilder = new SimpleResponse.Builder(statusCode)

    // Add body
    if (body != null && !body.isEmpty()) {
        responseBuilder.withBody(body)
        if (contentType != null) {
            responseBuilder.withContentType(contentType)
        }
    }

    // Add headers
    headers.each { name, value ->
        responseBuilder.withHeader(name, value)
    }

    // Convert HTTP method string to Request.Method enum
    def method = Request.Method.valueOf(httpMethod.toUpperCase())

    return validator.validateResponse(requestPath, method, responseBuilder.build())
}

// ============================================================================
// HELPER METHODS
// ============================================================================

def extractBody(Message msg) {
    try {
        def body = msg.get("content.body")
        if (body == null) return null

        def bodyClassName = body.getClass().getName()

        // JSONBody
        if (bodyClassName.contains("JSONBody")) {
            def json = body.getJSON()
            if (json != null) {
                return json.toString()
            }
        }

        // XMLBody
        if (bodyClassName.contains("XMLBody")) {
            return body.toString()
        }

        // Try getContentAsString
        if (body.metaClass.respondsTo(body, "getContentAsString")) {
            return body.getContentAsString()
        }

        // Try getContent
        if (body.metaClass.respondsTo(body, "getContent")) {
            def content = body.getContent()
            if (content instanceof byte[]) {
                return new String(content, "UTF-8")
            }
            return content?.toString()
        }

        // Try InputStream
        if (body.metaClass.respondsTo(body, "getInputStream")) {
            def is = body.getInputStream(null)
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

def extractContentType(Message msg) {
    // Try to get from headers first
    def headers = msg.get("http.header")
    if (headers != null) {
        def ct = getHeaderValue(headers, "Content-Type")
        if (ct != null) return ct
    }

    // Try content.type attribute
    def contentType = msg.get("content.type")
    if (contentType != null) return contentType.toString()

    // Default
    return "application/json"
}

def mergeHeaders(Message msg) {
    def mergedHeaders = [:]

    // Get main headers (http.header)
    def httpHeaders = msg.get("http.header")
    if (httpHeaders != null) {
        extractHeadersToMap(httpHeaders, mergedHeaders)
    }

    // Get extra headers (http.content.header) and merge
    def contentHeaders = msg.get("http.content.header")
    if (contentHeaders != null) {
        extractHeadersToMap(contentHeaders, mergedHeaders)
    }

    return mergedHeaders
}

def extractHeadersToMap(Object headers, Map<String, String> targetMap) {
    try {
        if (headers instanceof Map) {
            headers.each { key, value ->
                targetMap[key.toString()] = value?.toString() ?: ""
            }
        } else if (headers.metaClass.respondsTo(headers, "getHeaderNames")) {
            // Axway HeaderSet
            def headerNames = headers.getHeaderNames()
            headerNames.each { name ->
                def value = headers.getHeader(name)
                targetMap[name.toString()] = value?.toString() ?: ""
            }
        } else if (headers.metaClass.respondsTo(headers, "entrySet")) {
            headers.entrySet().each { entry ->
                targetMap[entry.key.toString()] = entry.value?.toString() ?: ""
            }
        }
    } catch (Exception e) {
        Trace.debug("Could not extract headers: " + e.getMessage())
    }
}

def getHeaderValue(Object headers, String headerName) {
    try {
        if (headers instanceof Map) {
            // Case-insensitive lookup
            def entry = headers.find { it.key.toString().equalsIgnoreCase(headerName) }
            return entry?.value?.toString()
        } else if (headers.metaClass.respondsTo(headers, "getHeader")) {
            return headers.getHeader(headerName)?.toString()
        }
    } catch (Exception e) {
        Trace.debug("Could not get header value: " + e.getMessage())
    }
    return null
}

def extractQueryParams(Message msg) {
    def params = [:]

    try {
        def queryParams = msg.get("param.query")

        if (queryParams == null) {
            // Try alternative: http.request.querystring
            def queryString = msg.get("http.request.querystring")
            if (queryString != null && !queryString.isEmpty()) {
                return parseQueryString(queryString.toString())
            }
            return params
        }

        if (queryParams instanceof Map) {
            queryParams.each { key, value ->
                def keyStr = key.toString()
                if (!params.containsKey(keyStr)) {
                    params[keyStr] = []
                }
                if (value instanceof List) {
                    value.each { v -> params[keyStr].add(v?.toString() ?: "") }
                } else {
                    params[keyStr].add(value?.toString() ?: "")
                }
            }
        } else if (queryParams.metaClass.respondsTo(queryParams, "getParameterNames")) {
            // Axway ParameterSet
            def paramNames = queryParams.getParameterNames()
            paramNames.each { name ->
                def values = queryParams.getParameterValues(name)
                params[name.toString()] = values?.collect { it?.toString() ?: "" } ?: [""]
            }
        }
    } catch (Exception e) {
        Trace.debug("Could not extract query params: " + e.getMessage())
    }

    return params
}

def parseQueryString(String queryString) {
    def params = [:]
    if (queryString == null || queryString.isEmpty()) return params

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

def parseStatusCode(Object status) {
    try {
        if (status instanceof Integer) return status
        if (status instanceof Number) return status.intValue()
        return Integer.parseInt(status.toString().trim())
    } catch (Exception e) {
        Trace.warn("Could not parse status code '${status}', defaulting to 200")
        return 200
    }
}

def collectAllMessages(ValidationReport report) {
    def messages = []
    report.getMessages().each { message ->
        if (message.getLevel() in [ValidationReport.Level.INFO,
                                    ValidationReport.Level.WARN,
                                    ValidationReport.Level.ERROR]) {
            messages.add("[" + message.getKey() + "] " + message.getMessage())
        }
    }
    return messages
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
