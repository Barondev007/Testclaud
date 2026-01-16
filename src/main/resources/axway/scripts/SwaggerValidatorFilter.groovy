import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.SimpleRequest
import com.atlassian.oai.validator.model.SimpleResponse
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.LevelResolverFactory
import com.atlassian.oai.validator.report.ValidationReport
import com.vordel.circuit.CircuitAbortException
import com.vordel.circuit.Message
import com.vordel.mime.Body
import com.vordel.mime.HeaderSet

/**
 * Axway API Gateway - Swagger Request Validator Filter (Groovy Script)
 *
 * This script validates incoming requests against an OpenAPI specification
 * while allowing additional properties in the request/response body.
 *
 * Usage: Add this as a Scripting Filter in your Axway Policy Studio
 */

// ============================================================================
// CONFIGURATION - Modify these values according to your setup
// ============================================================================

// Path to your OpenAPI spec file (relative to Axway install or absolute path)
def OPENAPI_SPEC_PATH = "file:///opt/Axway/apigateway/groups/group-2/instance-1/conf/openapi/your-api-spec.yaml"

// Set to true to allow additional properties (lenient mode)
def ALLOW_ADDITIONAL_PROPERTIES = true

// Set to true to validate responses as well
def VALIDATE_RESPONSE = false

// ============================================================================
// VALIDATOR INITIALIZATION (cached for performance)
// ============================================================================

// Use a static holder to cache the validator instance
class ValidatorHolder {
    static OpenApiInteractionValidator validator = null
    static String specPath = null

    static synchronized OpenApiInteractionValidator getValidator(String path, boolean lenient) {
        if (validator == null || specPath != path) {
            specPath = path
            if (lenient) {
                // Create lenient validator that allows additional properties
                validator = OpenApiInteractionValidator
                    .createForSpecificationUrl(path)
                    .withLevelResolver(LevelResolverFactory.withAdditionalPropertiesIgnored())
                    .build()
            } else {
                // Create strict validator
                validator = OpenApiInteractionValidator
                    .createForSpecificationUrl(path)
                    .build()
            }
        }
        return validator
    }
}

// ============================================================================
// MAIN VALIDATION LOGIC
// ============================================================================

def invoke(Message msg) {
    try {
        // Get the validator instance
        def validator = ValidatorHolder.getValidator(OPENAPI_SPEC_PATH, ALLOW_ADDITIONAL_PROPERTIES)

        // Extract request details from Axway message
        def httpMethod = msg.get("http.request.verb") ?: "GET"
        def requestPath = msg.get("http.request.path") ?: "/"
        def contentType = msg.get("content.type") ?: "application/json"
        def requestBody = extractBody(msg)

        // Build the request for validation
        def requestBuilder = new SimpleRequest.Builder(httpMethod, requestPath)

        if (requestBody != null && !requestBody.isEmpty()) {
            requestBuilder.withBody(requestBody)
            requestBuilder.withContentType(contentType)
        }

        // Add query parameters if present
        def queryString = msg.get("http.request.querystring")
        if (queryString != null && !queryString.isEmpty()) {
            parseQueryParams(queryString).each { key, values ->
                values.each { value ->
                    requestBuilder.withQueryParam(key, value)
                }
            }
        }

        // Add headers
        def headers = msg.get("http.headers")
        if (headers != null) {
            headers.each { name, value ->
                requestBuilder.withHeader(name, value)
            }
        }

        // Perform validation
        def request = requestBuilder.build()
        def report = validator.validateRequest(request)

        // Check for validation errors
        if (report.hasErrors()) {
            def errorMessage = formatValidationErrors(report)

            // Log the validation error
            Trace.error("OpenAPI Validation Failed: " + errorMessage)

            // Store error details in message for error handling policy
            msg.put("openapi.validation.error", errorMessage)
            msg.put("openapi.validation.errors", report.getMessages())

            // Return false to indicate validation failure
            return false
        }

        // Validation passed
        Trace.debug("OpenAPI Validation Passed for: " + httpMethod + " " + requestPath)
        return true

    } catch (Exception e) {
        Trace.error("OpenAPI Validation Exception: " + e.getMessage())
        msg.put("openapi.validation.error", "Validation error: " + e.getMessage())
        return false
    }
}

// ============================================================================
// HELPER METHODS
// ============================================================================

/**
 * Extract request body from Axway message
 */
def extractBody(Message msg) {
    try {
        def body = msg.get("content.body")
        if (body != null) {
            if (body instanceof Body) {
                def inputStream = body.getInputStream()
                if (inputStream != null) {
                    return inputStream.getText("UTF-8")
                }
            } else if (body instanceof String) {
                return body
            }
        }
        return null
    } catch (Exception e) {
        Trace.debug("Could not extract body: " + e.getMessage())
        return null
    }
}

/**
 * Parse query string into a map
 */
def parseQueryParams(String queryString) {
    def params = [:]
    if (queryString == null || queryString.isEmpty()) {
        return params
    }

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
 * Format validation errors into a readable message
 */
def formatValidationErrors(ValidationReport report) {
    def sb = new StringBuilder()
    sb.append("Validation errors: ")

    report.getMessages().each { message ->
        if (message.getLevel() == ValidationReport.Level.ERROR) {
            sb.append("[").append(message.getKey()).append("] ")
            sb.append(message.getMessage()).append("; ")
        }
    }

    return sb.toString()
}

// Execute the validation
return invoke(msg)
