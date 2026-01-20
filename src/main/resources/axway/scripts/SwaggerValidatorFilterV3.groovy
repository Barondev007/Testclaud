import be.bnppf.openapi.validator.BnppfOpenAPIValidator
import be.bnppf.openapi.validator.ValidationLevel
import be.bnppf.openapi.validator.ValidationResult
import com.vordel.trace.Trace

/**
 * Axway API Gateway - OpenAPI Request/Response Validator V3
 *
 * This script uses the BnppfOpenAPIValidator Java class for validation.
 * The Java class must be deployed as a JAR in the Axway ext/lib directory.
 *
 * Input Attributes:
 * - specfile                  : (Required) OpenAPI spec content (YAML or JSON) or URL
 * - content.body              : Payload to validate (request or response body)
 * - http.request.verb         : HTTP method (GET, POST, PUT, DELETE, etc.)
 * - http.request.path         : Request path (e.g., /users/123)
 * - http.headers              : Request/Response headers (HeaderSet)
 * - http.querystring          : Query parameters (QueryStringHeaderSet)
 * - http.response.status      : Response status code (if set, validates as response)
 * - openapi.validation.level  : Validation level (light, lenient, strict - default: strict)
 * - openapi.validation.debug  : Enable debug logging ("true" to enable)
 *
 * Output Attributes:
 * - openapi.validation.failed       : "true" or "false"
 * - openapi.validation.blocked      : "true" or "false" (based on level)
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
            Trace.info("========== OpenAPI Validation V3 - DEBUG MODE ==========")
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
            def headers = getHeaders(msg)

            if (debugEnabled) {
                Trace.info("[DEBUG] Response status: ${statusCode}")
            }

            result = validator.validateResponseAxway(body, httpMethod, requestPath, statusCode, headers)
            msg.put("openapi.validation.type", "response")

        } else {
            // Request validation
            def headers = getHeaders(msg)
            def queryParams = getQueryParams(msg)

            result = validator.validateRequestAxway(body, httpMethod, requestPath, queryParams, headers)
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

        // Return based on blocked status (allows flow to continue for LIGHT mode)
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

def getHeaders(Message msg) {
    // Try multiple possible attribute names
    def headerAttributeNames = [
        "http.headers",
        "http.header",
        "http.request.headers",
        "headers",
        "content.headers"
    ]

    for (attrName in headerAttributeNames) {
        def headers = msg.get(attrName)
        // Check by class name to avoid direct dependency on HeaderSet
        if (headers != null && headers.getClass().getName().contains("HeaderSet")) {
            return headers
        }
    }

    // Return null if no headers found
    return null
}

def getQueryParams(Message msg) {
    // Try multiple possible attribute names
    def queryParamAttributeNames = [
        "http.querystring",
        "http.request.querystring",
        "querystring",
        "param.query"
    ]

    for (attrName in queryParamAttributeNames) {
        def params = msg.get(attrName)
        // Check by class name to avoid direct dependency on QueryStringHeaderSet
        if (params != null && params.getClass().getName().contains("QueryStringHeaderSet")) {
            return params
        }
    }

    // Return null if no query params found
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

// Execute
return invoke(msg)
