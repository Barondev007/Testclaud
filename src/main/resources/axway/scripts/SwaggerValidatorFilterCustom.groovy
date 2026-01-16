import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.SimpleRequest
import com.atlassian.oai.validator.model.SimpleResponse
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.ValidationReport
import com.vordel.circuit.Message

/**
 * Axway API Gateway - Swagger Request Validator Filter (Custom LevelResolver)
 *
 * This version uses a custom LevelResolver for fine-grained control over
 * which validation errors to ignore.
 */

// ============================================================================
// CONFIGURATION
// ============================================================================

// Path to your OpenAPI spec file
def OPENAPI_SPEC_PATH = "file:///opt/Axway/apigateway/groups/group-2/instance-1/conf/openapi/your-api-spec.yaml"

// ============================================================================
// CUSTOM LEVEL RESOLVER CONFIGURATION
// ============================================================================

/**
 * Create a custom LevelResolver with specific validation rules.
 * Modify this method to customize which validations to ignore/warn/error.
 */
def createCustomLevelResolver() {
    return LevelResolver.create()
        // Ignore additional properties in request body
        .withLevel("validation.request.body.schema.additionalProperties", ValidationReport.Level.IGNORE)
        // Ignore additional properties in response body
        .withLevel("validation.response.body.schema.additionalProperties", ValidationReport.Level.IGNORE)
        // You can add more customizations here:
        // .withLevel("validation.request.body.schema.required", ValidationReport.Level.WARN)
        // .withLevel("validation.request.parameter.query.missing", ValidationReport.Level.WARN)
        .build()
}

// ============================================================================
// VALIDATOR INITIALIZATION (cached)
// ============================================================================

class ValidatorHolder {
    static OpenApiInteractionValidator validator = null
    static String specPath = null
}

def getValidator(String path, LevelResolver levelResolver) {
    synchronized(ValidatorHolder.class) {
        if (ValidatorHolder.validator == null || ValidatorHolder.specPath != path) {
            ValidatorHolder.specPath = path
            ValidatorHolder.validator = OpenApiInteractionValidator
                .createForSpecificationUrl(path)
                .withLevelResolver(levelResolver)
                .build()
        }
        return ValidatorHolder.validator
    }
}

// ============================================================================
// MAIN VALIDATION LOGIC
// ============================================================================

def invoke(Message msg) {
    try {
        // Create custom level resolver and get validator
        def levelResolver = createCustomLevelResolver()
        def validator = getValidator(OPENAPI_SPEC_PATH, levelResolver)

        // Extract request details
        def httpMethod = msg.get("http.request.verb") ?: "GET"
        def requestPath = msg.get("http.request.path") ?: "/"
        def contentType = msg.get("content.type") ?: "application/json"
        def requestBody = extractBody(msg)

        // Build request
        def requestBuilder = new SimpleRequest.Builder(httpMethod, requestPath)

        if (requestBody != null && !requestBody.isEmpty()) {
            requestBuilder.withBody(requestBody)
            requestBuilder.withContentType(contentType)
        }

        // Add query parameters
        def queryString = msg.get("http.request.querystring")
        if (queryString != null) {
            parseQueryParams(queryString).each { key, values ->
                values.each { value ->
                    requestBuilder.withQueryParam(key, value)
                }
            }
        }

        // Validate
        def report = validator.validateRequest(requestBuilder.build())

        if (report.hasErrors()) {
            def errorMessage = formatErrors(report)
            Trace.error("Validation Failed: " + errorMessage)
            msg.put("openapi.validation.error", errorMessage)

            // Store detailed errors for custom error response
            msg.put("openapi.validation.details", getErrorDetails(report))

            return false
        }

        Trace.debug("Validation Passed: " + httpMethod + " " + requestPath)
        return true

    } catch (Exception e) {
        Trace.error("Validation Exception: " + e.getMessage())
        msg.put("openapi.validation.error", e.getMessage())
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
            return body.getInputStream()?.getText("UTF-8")
        }
        return body.toString()
    } catch (Exception e) {
        return null
    }
}

def parseQueryParams(String queryString) {
    def params = [:]
    queryString?.split("&")?.each { param ->
        def parts = param.split("=", 2)
        def key = URLDecoder.decode(parts[0], "UTF-8")
        def value = parts.length > 1 ? URLDecoder.decode(parts[1], "UTF-8") : ""
        params.computeIfAbsent(key) { [] }.add(value)
    }
    return params
}

def formatErrors(ValidationReport report) {
    return report.getMessages()
        .findAll { it.getLevel() == ValidationReport.Level.ERROR }
        .collect { "[${it.getKey()}] ${it.getMessage()}" }
        .join("; ")
}

def getErrorDetails(ValidationReport report) {
    return report.getMessages()
        .findAll { it.getLevel() == ValidationReport.Level.ERROR }
        .collect { [key: it.getKey(), message: it.getMessage(), context: it.getContext()?.toString()] }
}

return invoke(msg)
