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
 * - openapi.validation.debug     : Enable debug logging ("true" to enable)
 *
 * Output Attributes:
 * - openapi.validation.failed       : "true" or "false"
 * - openapi.validation.error        : Error message(s)
 * - openapi.validation.errors.count : Number of errors
 * - openapi.validation.errors.all   : All error messages
 * - openapi.validation.type         : "request" or "response"
 * - openapi.validation.debug.headers      : Debug info about extracted headers (when debug enabled)
 * - openapi.validation.debug.queryparams  : Debug info about extracted query params (when debug enabled)
 * - openapi.validation.debug.body         : Debug info about extracted body (when debug enabled)
 */

// Debug flag - set globally for helper methods
@groovy.transform.Field boolean debugEnabled = false

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
        // Check if debug mode is enabled
        def debugAttr = msg.get("openapi.validation.debug")
        debugEnabled = (debugAttr != null && debugAttr.toString().equalsIgnoreCase("true"))

        if (debugEnabled) {
            Trace.info("========== OpenAPI Validation DEBUG MODE ENABLED ==========")
        }

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

        if (debugEnabled) {
            Trace.info("[DEBUG] Validation level: ${validationLevel}")
        }

        // Get cached validator
        def validator = ValidatorCache.getValidator(specContent.trim(), validationLevel)

        // Detect if this is a request or response validation
        def responseStatus = msg.get("http.response.status")
        def isResponseValidation = (responseStatus != null && !responseStatus.toString().isEmpty())

        msg.put("openapi.validation.type", isResponseValidation ? "response" : "request")

        if (debugEnabled) {
            Trace.info("[DEBUG] Validation type: ${isResponseValidation ? 'response' : 'request'}")
            if (isResponseValidation) {
                Trace.info("[DEBUG] Response status: ${responseStatus}")
            }
        }

        // Extract common data
        def httpMethod = msg.get("http.request.verb") ?: "GET"
        def requestPath = msg.get("http.request.path") ?: "/"
        def body = extractBody(msg)
        def contentType = extractContentType(msg)
        def headers = mergeHeaders(msg)
        def queryParams = extractQueryParams(msg)

        if (debugEnabled) {
            Trace.info("[DEBUG] HTTP Method: ${httpMethod}")
            Trace.info("[DEBUG] Request Path: ${requestPath}")
            Trace.info("[DEBUG] Content-Type: ${contentType}")
            Trace.info("[DEBUG] Body length: ${body?.length() ?: 0} chars")
        }

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
    def debugInfo = new StringBuilder()
    try {
        def body = msg.get("content.body")

        if (debugEnabled) {
            debugInfo.append("=== content.body ===\n")
            if (body == null) {
                debugInfo.append("  [NULL] content.body is null\n")
                Trace.info("[DEBUG] content.body: NULL")
            } else {
                debugInfo.append("  [TYPE] ${body.getClass().getName()}\n")
                Trace.info("[DEBUG] content.body type: ${body.getClass().getName()}")
            }
        }

        if (body == null) {
            if (debugEnabled) {
                msg.put("openapi.validation.debug.body", debugInfo.toString())
            }
            return null
        }

        def bodyClassName = body.getClass().getName()
        def extractedBody = null
        def extractionMethod = "unknown"

        // JSONBody
        if (bodyClassName.contains("JSONBody")) {
            extractionMethod = "JSONBody.getJSON()"
            if (debugEnabled) {
                debugInfo.append("  [EXTRACTION] Using getJSON() for JSONBody\n")
                Trace.info("[DEBUG] Extracting body via getJSON() for JSONBody")
            }
            def json = body.getJSON()
            if (json != null) {
                extractedBody = json.toString()
            }
        }
        // XMLBody
        else if (bodyClassName.contains("XMLBody")) {
            extractionMethod = "XMLBody.toString()"
            if (debugEnabled) {
                debugInfo.append("  [EXTRACTION] Using toString() for XMLBody\n")
                Trace.info("[DEBUG] Extracting body via toString() for XMLBody")
            }
            extractedBody = body.toString()
        }
        // Try getContentAsString
        else if (body.metaClass.respondsTo(body, "getContentAsString")) {
            extractionMethod = "getContentAsString()"
            if (debugEnabled) {
                debugInfo.append("  [EXTRACTION] Using getContentAsString()\n")
                Trace.info("[DEBUG] Extracting body via getContentAsString()")
            }
            extractedBody = body.getContentAsString()
        }
        // Try getContent
        else if (body.metaClass.respondsTo(body, "getContent")) {
            extractionMethod = "getContent()"
            if (debugEnabled) {
                debugInfo.append("  [EXTRACTION] Using getContent()\n")
                Trace.info("[DEBUG] Extracting body via getContent()")
            }
            def content = body.getContent()
            if (content instanceof byte[]) {
                extractedBody = new String(content, "UTF-8")
            } else {
                extractedBody = content?.toString()
            }
        }
        // Try InputStream
        else if (body.metaClass.respondsTo(body, "getInputStream")) {
            extractionMethod = "getInputStream()"
            if (debugEnabled) {
                debugInfo.append("  [EXTRACTION] Using getInputStream()\n")
                Trace.info("[DEBUG] Extracting body via getInputStream()")
            }
            def is = body.getInputStream(null)
            if (is != null) {
                extractedBody = is.getText("UTF-8")
            }
        }
        // Fallback to toString
        else {
            extractionMethod = "toString() [fallback]"
            if (debugEnabled) {
                debugInfo.append("  [EXTRACTION] Using toString() as fallback\n")
                debugInfo.append("  [METHODS] Available: ${body.metaClass.methods*.name.unique().sort()}\n")
                Trace.info("[DEBUG] Extracting body via toString() (fallback)")
                Trace.info("[DEBUG]   Available methods: ${body.metaClass.methods*.name.unique().sort().take(20)}")
            }
            extractedBody = body.toString()
        }

        if (debugEnabled) {
            debugInfo.append("  [METHOD] ${extractionMethod}\n")
            debugInfo.append("  [LENGTH] ${extractedBody?.length() ?: 0} chars\n")
            if (extractedBody != null && extractedBody.length() <= 500) {
                debugInfo.append("  [CONTENT] ${extractedBody}\n")
            } else if (extractedBody != null) {
                debugInfo.append("  [CONTENT] ${extractedBody.take(500)}... (truncated)\n")
            }
            Trace.info("[DEBUG] Body extracted via ${extractionMethod}, length: ${extractedBody?.length() ?: 0} chars")
            msg.put("openapi.validation.debug.body", debugInfo.toString())
        }

        return extractedBody

    } catch (Exception e) {
        def errorMsg = "Could not extract body: ${e.getMessage()}"
        debugInfo.append("  [ERROR] ${errorMsg}\n")
        Trace.error("[DEBUG] ${errorMsg}")
        if (debugEnabled) {
            e.printStackTrace()
            msg.put("openapi.validation.debug.body", debugInfo.toString())
        }
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
    def debugInfo = new StringBuilder()

    // List of possible attribute names for headers in Axway
    def headerAttributeNames = [
        "http.headers",
        "http.header",
        "http.request.headers",
        "headers",
        "content.headers",
        "http.content.header"
    ]

    if (debugEnabled) {
        debugInfo.append("=== SEARCHING FOR HEADERS ===\n")
        Trace.info("[DEBUG] ========== SEARCHING FOR HEADERS ==========")
    }

    // Try each possible attribute name
    for (attrName in headerAttributeNames) {
        def headerObj = msg.get(attrName)
        if (debugEnabled) {
            if (headerObj == null) {
                debugInfo.append("  [TRY] ${attrName} = NULL\n")
                Trace.info("[DEBUG]   ${attrName} = NULL")
            } else {
                debugInfo.append("  [FOUND] ${attrName} = ${headerObj.getClass().getName()}\n")
                Trace.info("[DEBUG]   [FOUND] ${attrName} type: ${headerObj.getClass().getName()}")
            }
        }
        if (headerObj != null && mergedHeaders.isEmpty()) {
            extractHeadersToMap(headerObj, mergedHeaders, attrName, debugInfo)
        }
    }

    // Also try to access headers directly from Message object methods
    if (mergedHeaders.isEmpty()) {
        if (debugEnabled) {
            debugInfo.append("=== TRYING MESSAGE OBJECT METHODS ===\n")
            Trace.info("[DEBUG] Trying Message object methods for headers")
        }

        // Try msg.getHeaders() if available
        if (msg.metaClass.respondsTo(msg, "getHeaders")) {
            try {
                def hdrs = msg.getHeaders()
                if (hdrs != null) {
                    if (debugEnabled) {
                        debugInfo.append("  [FOUND] msg.getHeaders() = ${hdrs.getClass().getName()}\n")
                        Trace.info("[DEBUG]   [FOUND] msg.getHeaders() type: ${hdrs.getClass().getName()}")
                    }
                    extractHeadersToMap(hdrs, mergedHeaders, "msg.getHeaders()", debugInfo)
                }
            } catch (Exception e) {
                if (debugEnabled) {
                    debugInfo.append("  [ERROR] msg.getHeaders(): ${e.getMessage()}\n")
                }
            }
        }

        // Try to list all available attributes on msg for debugging
        if (debugEnabled && mergedHeaders.isEmpty()) {
            debugInfo.append("=== LISTING MESSAGE PROPERTIES ===\n")
            Trace.info("[DEBUG] Listing Message object properties")
            try {
                // Try to get all property names
                if (msg.metaClass.respondsTo(msg, "getPropertyNames")) {
                    def propNames = msg.getPropertyNames()
                    debugInfo.append("  [PROPS] ${propNames}\n")
                    Trace.info("[DEBUG]   Properties: ${propNames}")
                }
                // List methods
                def methods = msg.metaClass.methods*.name.unique().sort()
                debugInfo.append("  [METHODS] ${methods.take(30)}\n")
                Trace.info("[DEBUG]   Methods: ${methods.take(30)}")
            } catch (Exception e) {
                debugInfo.append("  [ERROR] Cannot list properties: ${e.getMessage()}\n")
            }
        }
    }

    // Get extra headers (http.content.header) and merge - try additional names too
    def extraHeaderNames = ["http.content.header", "content.header", "http.content.headers"]
    for (attrName in extraHeaderNames) {
        def contentHeaders = msg.get(attrName)
        if (contentHeaders != null) {
            if (debugEnabled) {
                debugInfo.append("=== EXTRA HEADERS: ${attrName} ===\n")
                debugInfo.append("  [TYPE] ${contentHeaders.getClass().getName()}\n")
                Trace.info("[DEBUG] Extra headers from ${attrName}: ${contentHeaders.getClass().getName()}")
            }
            extractHeadersToMap(contentHeaders, mergedHeaders, attrName, debugInfo)
            break
        }
    }

    // Log final merged headers
    if (debugEnabled) {
        debugInfo.append("=== MERGED HEADERS (${mergedHeaders.size()} total) ===\n")
        Trace.info("[DEBUG] ========== MERGED HEADERS (${mergedHeaders.size()} total) ==========")
        mergedHeaders.each { name, value ->
            debugInfo.append("  ${name}: ${value}\n")
            Trace.info("[DEBUG]   ${name}: ${value}")
        }
        msg.put("openapi.validation.debug.headers", debugInfo.toString())
    }

    return mergedHeaders
}

def extractHeadersToMap(Object headers, Map<String, String> targetMap, String sourceName = "unknown", StringBuilder debugInfo = null) {
    try {
        if (headers instanceof Map) {
            if (debugEnabled) {
                debugInfo?.append("  [EXTRACTION] Using Map iteration for ${sourceName}\n")
                Trace.info("[DEBUG] ${sourceName}: Extracting as Map (${headers.size()} entries)")
            }
            headers.each { key, value ->
                def keyStr = key.toString()
                def valueStr = value?.toString() ?: ""
                targetMap[keyStr] = valueStr
                if (debugEnabled) {
                    debugInfo?.append("    [MAP] ${keyStr} = ${valueStr}\n")
                    Trace.info("[DEBUG]   [MAP] ${keyStr} = ${valueStr}")
                }
            }
        } else if (headers.metaClass.respondsTo(headers, "getHeaderNames")) {
            // Axway HeaderSet
            if (debugEnabled) {
                debugInfo?.append("  [EXTRACTION] Using getHeaderNames() for ${sourceName}\n")
                Trace.info("[DEBUG] ${sourceName}: Extracting via getHeaderNames() method")
            }
            def headerNames = headers.getHeaderNames()
            if (debugEnabled) {
                debugInfo?.append("  [HEADER_NAMES] Found: ${headerNames}\n")
                Trace.info("[DEBUG]   Header names found: ${headerNames}")
            }
            headerNames.each { name ->
                def value = headers.getHeader(name)
                def nameStr = name.toString()
                def valueStr = value?.toString() ?: ""
                targetMap[nameStr] = valueStr
                if (debugEnabled) {
                    debugInfo?.append("    [HDR] ${nameStr} = ${valueStr}\n")
                    Trace.info("[DEBUG]   [HDR] ${nameStr} = ${valueStr}")
                }
            }
        } else if (headers.metaClass.respondsTo(headers, "entrySet")) {
            if (debugEnabled) {
                debugInfo?.append("  [EXTRACTION] Using entrySet() for ${sourceName}\n")
                Trace.info("[DEBUG] ${sourceName}: Extracting via entrySet() method")
            }
            headers.entrySet().each { entry ->
                def keyStr = entry.key.toString()
                def valueStr = entry.value?.toString() ?: ""
                targetMap[keyStr] = valueStr
                if (debugEnabled) {
                    debugInfo?.append("    [ENTRY] ${keyStr} = ${valueStr}\n")
                    Trace.info("[DEBUG]   [ENTRY] ${keyStr} = ${valueStr}")
                }
            }
        } else {
            // Unknown type - try to list available methods
            if (debugEnabled) {
                debugInfo?.append("  [WARNING] Unknown header type for ${sourceName}\n")
                debugInfo?.append("  [METHODS] Available: ${headers.metaClass.methods*.name.unique().sort()}\n")
                Trace.warn("[DEBUG] ${sourceName}: Unknown header type - cannot extract")
                Trace.info("[DEBUG]   Available methods: ${headers.metaClass.methods*.name.unique().sort().take(20)}")
            }
        }
    } catch (Exception e) {
        def errorMsg = "Could not extract headers from ${sourceName}: ${e.getMessage()}"
        debugInfo?.append("  [ERROR] ${errorMsg}\n")
        Trace.error("[DEBUG] ${errorMsg}")
        if (debugEnabled) {
            e.printStackTrace()
        }
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
    def debugInfo = new StringBuilder()

    try {
        // List of possible attribute names for query params in Axway
        def queryParamAttributeNames = [
            "param.query",
            "params",
            "http.querystring",
            "http.request.querystring",
            "query.params",
            "queryParams",
            "http.request.uri.query"
        ]

        if (debugEnabled) {
            debugInfo.append("=== SEARCHING FOR QUERY PARAMS ===\n")
            Trace.info("[DEBUG] ========== SEARCHING FOR QUERY PARAMS ==========")
        }

        def foundQueryParams = null
        def foundAttrName = null

        // Try each possible attribute name
        for (attrName in queryParamAttributeNames) {
            def queryObj = msg.get(attrName)
            if (debugEnabled) {
                if (queryObj == null) {
                    debugInfo.append("  [TRY] ${attrName} = NULL\n")
                    Trace.info("[DEBUG]   ${attrName} = NULL")
                } else {
                    debugInfo.append("  [FOUND] ${attrName} = ${queryObj.getClass().getName()}\n")
                    Trace.info("[DEBUG]   [FOUND] ${attrName} type: ${queryObj.getClass().getName()}")
                }
            }
            if (queryObj != null && foundQueryParams == null) {
                foundQueryParams = queryObj
                foundAttrName = attrName
            }
        }

        // Also try to get query string from URI
        if (foundQueryParams == null) {
            def uri = msg.get("http.request.uri")
            if (uri != null) {
                if (debugEnabled) {
                    debugInfo.append("  [TRY] http.request.uri = ${uri}\n")
                    Trace.info("[DEBUG]   http.request.uri = ${uri}")
                }
                def uriStr = uri.toString()
                def queryIdx = uriStr.indexOf('?')
                if (queryIdx >= 0 && queryIdx < uriStr.length() - 1) {
                    foundQueryParams = uriStr.substring(queryIdx + 1)
                    foundAttrName = "http.request.uri (parsed)"
                    if (debugEnabled) {
                        debugInfo.append("  [PARSED] Query from URI: ${foundQueryParams}\n")
                        Trace.info("[DEBUG]   Parsed query from URI: ${foundQueryParams}")
                    }
                }
            }
        }

        // Try Message object methods
        if (foundQueryParams == null) {
            if (debugEnabled) {
                debugInfo.append("=== TRYING MESSAGE OBJECT METHODS ===\n")
                Trace.info("[DEBUG] Trying Message object methods for query params")
            }

            if (msg.metaClass.respondsTo(msg, "getQueryString")) {
                try {
                    def qs = msg.getQueryString()
                    if (qs != null) {
                        foundQueryParams = qs
                        foundAttrName = "msg.getQueryString()"
                        if (debugEnabled) {
                            debugInfo.append("  [FOUND] msg.getQueryString() = ${qs}\n")
                            Trace.info("[DEBUG]   [FOUND] msg.getQueryString() = ${qs}")
                        }
                    }
                } catch (Exception e) {
                    if (debugEnabled) {
                        debugInfo.append("  [ERROR] msg.getQueryString(): ${e.getMessage()}\n")
                    }
                }
            }

            if (msg.metaClass.respondsTo(msg, "getParameters")) {
                try {
                    def prms = msg.getParameters()
                    if (prms != null) {
                        foundQueryParams = prms
                        foundAttrName = "msg.getParameters()"
                        if (debugEnabled) {
                            debugInfo.append("  [FOUND] msg.getParameters() = ${prms.getClass().getName()}\n")
                            Trace.info("[DEBUG]   [FOUND] msg.getParameters() type: ${prms.getClass().getName()}")
                        }
                    }
                } catch (Exception e) {
                    if (debugEnabled) {
                        debugInfo.append("  [ERROR] msg.getParameters(): ${e.getMessage()}\n")
                    }
                }
            }
        }

        // Process the found query params
        if (foundQueryParams == null) {
            if (debugEnabled) {
                debugInfo.append("=== NO QUERY PARAMS FOUND ===\n")
                Trace.info("[DEBUG] No query params found in any attribute")

                // List available message properties for debugging
                debugInfo.append("=== LISTING MESSAGE PROPERTIES ===\n")
                Trace.info("[DEBUG] Listing Message object properties")
                try {
                    if (msg.metaClass.respondsTo(msg, "getPropertyNames")) {
                        def propNames = msg.getPropertyNames()
                        debugInfo.append("  [PROPS] ${propNames}\n")
                        Trace.info("[DEBUG]   Properties: ${propNames}")
                    }
                    def methods = msg.metaClass.methods*.name.unique().sort()
                    debugInfo.append("  [METHODS] ${methods.take(30)}\n")
                    Trace.info("[DEBUG]   Methods: ${methods.take(30)}")
                } catch (Exception e) {
                    debugInfo.append("  [ERROR] Cannot list: ${e.getMessage()}\n")
                }

                msg.put("openapi.validation.debug.queryparams", debugInfo.toString())
            }
            return params
        }

        // Process based on type
        if (debugEnabled) {
            debugInfo.append("=== EXTRACTING FROM: ${foundAttrName} ===\n")
            Trace.info("[DEBUG] Extracting query params from: ${foundAttrName}")
        }

        if (foundQueryParams instanceof String) {
            // It's a query string, parse it
            params = parseQueryString(foundQueryParams.toString())
            if (debugEnabled) {
                debugInfo.append("  [PARSED] String query: ${params}\n")
                Trace.info("[DEBUG]   Parsed string query: ${params}")
            }
        } else if (foundQueryParams instanceof Map) {
            if (debugEnabled) {
                debugInfo.append("  [EXTRACTION] Using Map iteration\n")
                Trace.info("[DEBUG]   Extracting as Map (${foundQueryParams.size()} entries)")
            }
            foundQueryParams.each { key, value ->
                def keyStr = key.toString()
                if (!params.containsKey(keyStr)) {
                    params[keyStr] = []
                }
                if (value instanceof List) {
                    value.each { v ->
                        def vStr = v?.toString() ?: ""
                        params[keyStr].add(vStr)
                        if (debugEnabled) {
                            debugInfo.append("    [MAP-LIST] ${keyStr} += ${vStr}\n")
                            Trace.info("[DEBUG]     [MAP-LIST] ${keyStr} += ${vStr}")
                        }
                    }
                } else {
                    def vStr = value?.toString() ?: ""
                    params[keyStr].add(vStr)
                    if (debugEnabled) {
                        debugInfo.append("    [MAP] ${keyStr} = ${vStr}\n")
                        Trace.info("[DEBUG]     [MAP] ${keyStr} = ${vStr}")
                    }
                }
            }
        } else if (foundQueryParams.metaClass.respondsTo(foundQueryParams, "getParameterNames")) {
            // Axway ParameterSet
            if (debugEnabled) {
                debugInfo.append("  [EXTRACTION] Using getParameterNames()\n")
                Trace.info("[DEBUG]   Extracting via getParameterNames() method")
            }
            def paramNames = foundQueryParams.getParameterNames()
            if (debugEnabled) {
                debugInfo.append("  [PARAM_NAMES] Found: ${paramNames}\n")
                Trace.info("[DEBUG]     Parameter names found: ${paramNames}")
            }
            paramNames.each { name ->
                def values = foundQueryParams.getParameterValues(name)
                def nameStr = name.toString()
                def valuesList = values?.collect { it?.toString() ?: "" } ?: [""]
                params[nameStr] = valuesList
                if (debugEnabled) {
                    debugInfo.append("    [PARAM] ${nameStr} = ${valuesList}\n")
                    Trace.info("[DEBUG]     [PARAM] ${nameStr} = ${valuesList}")
                }
            }
        } else {
            // Unknown type - try toString and parse
            if (debugEnabled) {
                debugInfo.append("  [WARNING] Unknown type, trying toString()\n")
                debugInfo.append("  [METHODS] Available: ${foundQueryParams.metaClass.methods*.name.unique().sort().take(20)}\n")
                Trace.warn("[DEBUG]   Unknown type - trying toString()")
            }
            def qs = foundQueryParams.toString()
            if (qs && !qs.isEmpty()) {
                params = parseQueryString(qs)
            }
        }

        // Log final query params
        if (debugEnabled) {
            debugInfo.append("=== FINAL QUERY PARAMS (${params.size()} total) ===\n")
            Trace.info("[DEBUG] ========== FINAL QUERY PARAMS (${params.size()} total) ==========")
            params.each { name, values ->
                debugInfo.append("  ${name}: ${values}\n")
                Trace.info("[DEBUG]   ${name}: ${values}")
            }
            msg.put("openapi.validation.debug.queryparams", debugInfo.toString())
        }

    } catch (Exception e) {
        def errorMsg = "Could not extract query params: ${e.getMessage()}"
        debugInfo.append("  [ERROR] ${errorMsg}\n")
        Trace.error("[DEBUG] ${errorMsg}")
        if (debugEnabled) {
            e.printStackTrace()
            msg.put("openapi.validation.debug.queryparams", debugInfo.toString())
        }
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
