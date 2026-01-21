import be.bnppf.openapi.validator.BnppfOpenAPIValidator
import be.bnppf.openapi.validator.ValidationLevel
import be.bnppf.openapi.validator.ValidationResult
import com.vordel.mime.HeaderSet
import com.vordel.mime.QueryStringHeaderSet
import com.vordel.trace.Trace

/**
 * Axway API Gateway - OpenAPI Request/Response Validator V3
 *
 * Input Attributes:
 * - specfile                  : (Required) OpenAPI spec content (YAML or JSON)
 * - content.body              : Payload to validate
 * - http.request.verb         : HTTP method (GET, POST, PUT, DELETE, etc.)
 * - http.request.path         : Request path
 * - http.headers / headers    : Request/Response headers (HeaderSet)
 * - http.querystring          : Query parameters (QueryStringHeaderSet)
 * - http.response.status      : Response status code (if set, validates as response)
 * - openapi.validation.level  : Validation level (light, lenient, strict - default: strict)
 * - openapi.validation.debug  : Enable debug logging ("true" to enable)
 *
 * Output Attributes:
 * - openapi.validation.failed       : "true" or "false"
 * - openapi.validation.blocked      : "true" or "false"
 * - openapi.validation.error        : Error message(s)
 * - openapi.validation.errors.count : Number of errors
 * - openapi.validation.errors.all   : All error messages
 * - openapi.validation.type         : "request" or "response"
 * - openapi.validation.debug.info   : Debug information (when debug enabled)
 */

def invoke(Message msg) {
    try {
        // Get validation level
        def levelStr = msg.get("openapi.validation.level") ?: "strict"
        ValidationLevel level = ValidationLevel.fromString(levelStr)

        // Check debug mode
        def debugAttr = msg.get("openapi.validation.debug")
        boolean debugEnabled = (debugAttr != null && debugAttr.toString().equalsIgnoreCase("true"))

        if (debugEnabled) {
            Trace.info("========== OpenAPI Validation V3 ==========")
            Trace.info("[DEBUG] Validation level: ${level}")
        }

        // Get spec content
        def specContent = msg.get("specfile")
        if (specContent == null || specContent.toString().isEmpty()) {
            Trace.error("OpenAPI spec not provided. Set 'specfile' attribute.")
            msg.put("openapi.validation.error", "OpenAPI spec not provided")
            msg.put("openapi.validation.failed", "true")
            msg.put("openapi.validation.blocked", "true")
            return false
        }

        // Get validator instance (cached)
        BnppfOpenAPIValidator validator = BnppfOpenAPIValidator.getInstance(specContent.toString(), level)
        validator.setDebugEnabled(debugEnabled)

        // Detect if this is a request or response validation
        def responseStatus = msg.get("http.response.status")
        boolean isResponseValidation = (responseStatus != null && !responseStatus.toString().isEmpty())

        // Extract common data
        def httpMethod = (msg.get("http.request.verb") ?: "GET").toString()
        def requestPath = (msg.get("http.request.path") ?: "/").toString()
        def body = extractBody(msg)

        if (debugEnabled) {
            Trace.info("[DEBUG] Validation type: ${isResponseValidation ? 'response' : 'request'}")
            Trace.info("[DEBUG] HTTP Method: ${httpMethod}")
            Trace.info("[DEBUG] Request Path: ${requestPath}")
            Trace.info("[DEBUG] Body length: ${body?.length() ?: 0} chars")
        }

        ValidationResult result

        if (isResponseValidation) {
            // Response validation
            int statusCode = parseStatusCode(responseStatus)
            HeaderSet headers = getHeaders(msg)

            if (debugEnabled) {
                Trace.info("[DEBUG] Response status: ${statusCode}")
                logHeaders(headers)
            }

            result = validator.validateResponse(body, httpMethod, requestPath, statusCode, headers)
            msg.put("openapi.validation.type", "response")

        } else {
            // Request validation
            HeaderSet headers = getHeaders(msg)
            QueryStringHeaderSet queryParams = getQueryParams(msg)

            if (debugEnabled) {
                logHeaders(headers)
                logQueryParams(queryParams)
            }

            result = validator.validateRequest(body, httpMethod, requestPath, queryParams, headers)
            msg.put("openapi.validation.type", "request")
        }

        // Store results in message attributes
        msg.put("openapi.validation.failed", result.isValid() ? "false" : "true")
        msg.put("openapi.validation.blocked", result.isBlocked() ? "true" : "false")
        msg.put("openapi.validation.errors.count", String.valueOf(result.getErrorCount()))
        msg.put("openapi.validation.errors.all", result.getAllMessagesAsString())

        if (!result.isValid()) {
            msg.put("openapi.validation.error", result.getErrorsAsString())
            Trace.error("Validation errors: " + result.getErrorsAsString())
        }

        if (debugEnabled && result.getDebugInfo() != null) {
            msg.put("openapi.validation.debug.info", result.getDebugInfo())
            Trace.info("[DEBUG] Debug info:\n" + result.getDebugInfo())
        }

        return !result.isBlocked()

    } catch (Exception e) {
        Trace.error("Validation Exception: " + e.getMessage())
        e.printStackTrace()
        msg.put("openapi.validation.error", "Internal validation error: " + e.getMessage())
        msg.put("openapi.validation.failed", "true")
        msg.put("openapi.validation.blocked", "true")
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

        def bodyClassName = body.getClass().getName()

        if (bodyClassName.contains("JSONBody")) {
            def json = body.getJSON()
            return json?.toString()
        }

        if (bodyClassName.contains("XMLBody")) {
            return body.toString()
        }

        if (body.metaClass.respondsTo(body, "getContentAsString")) {
            return body.getContentAsString()
        }

        if (body.metaClass.respondsTo(body, "getContent")) {
            def content = body.getContent()
            if (content instanceof byte[]) {
                return new String(content, "UTF-8")
            }
            return content?.toString()
        }

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

HeaderSet getHeaders(Message msg) {
    // Try common attribute names for headers
    def attributeNames = ["headers", "http.headers", "http.request.headers"]

    for (attrName in attributeNames) {
        def headers = msg.get(attrName)
        if (headers != null && headers instanceof HeaderSet) {
            return headers
        }
    }
    return null
}

QueryStringHeaderSet getQueryParams(Message msg) {
    // Try common attribute names for query params
    def attributeNames = ["http.querystring", "http.request.querystring", "querystring"]

    for (attrName in attributeNames) {
        def params = msg.get(attrName)
        if (params != null && params instanceof QueryStringHeaderSet) {
            return params
        }
    }
    return null
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

void logHeaders(HeaderSet headers) {
    if (headers == null) {
        Trace.info("[DEBUG] Headers: null")
        return
    }
    def names = headers.getHeaderSet()
    Trace.info("[DEBUG] Headers count: ${names?.size() ?: 0}")
    names?.each { name ->
        Trace.info("[DEBUG]   ${name}: ${headers.getHeaderValues(name)}")
    }
}

void logQueryParams(QueryStringHeaderSet params) {
    if (params == null) {
        Trace.info("[DEBUG] QueryParams: null")
        return
    }
    def names = params.getHeaderSet()
    Trace.info("[DEBUG] QueryParams count: ${names?.size() ?: 0}")
    names?.each { name ->
        Trace.info("[DEBUG]   ${name}: ${params.getHeaderValues(name)}")
    }
}

// Execute
return invoke(msg)
