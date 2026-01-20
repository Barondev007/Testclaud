# Axway OpenAPI Validator

OpenAPI Request/Response Validator for Axway API Gateway.

## Features

- **Validation Levels**: LIGHT (non-blocking), LENIENT (allow extra properties), STRICT (all errors block)
- **Thread-safe caching**: Validators are cached for performance
- **Debug mode**: Detailed logging for troubleshooting
- **Path mapping**: Automatic handling of FE-API exposure paths
- **API Manager integration**: Fetch specs directly from API Manager

## Building

```bash
mvn clean package
```

This will create an uber-JAR with all dependencies in `target/openapi-validator-1.0.0.jar`.

## Deployment

1. Copy the JAR to Axway API Gateway:
   ```
   cp target/openapi-validator-1.0.0.jar $AXWAY_HOME/apigateway/ext/lib/
   ```

2. Restart the API Gateway instance.

3. Use the Groovy script `SwaggerValidatorFilterV3.groovy` in your policies.

## Usage in Groovy Script

```groovy
import be.bnppf.openapi.validator.BnppfOpenAPIValidator
import be.bnppf.openapi.validator.ValidationLevel
import be.bnppf.openapi.validator.ValidationResult

// Get validator instance (cached)
def validator = BnppfOpenAPIValidator.getInstance(specContent, ValidationLevel.LENIENT)

// Validate request
ValidationResult result = validator.validateRequest(body, verb, path, queryParams, headers)

// Check results
if (result.isBlocked()) {
    // Handle validation failure
}
```

## Validation Levels

| Level | Description |
|-------|-------------|
| STRICT | All validation errors block the flow (default) |
| LENIENT | Additional properties are allowed, other errors block |
| LIGHT | All errors are collected but flow is not blocked |

## Message Attributes

### Input Attributes

| Attribute | Description |
|-----------|-------------|
| `specfile` | OpenAPI spec (YAML/JSON content or URL) |
| `content.body` | Request/Response body |
| `http.request.verb` | HTTP method |
| `http.request.path` | Request path |
| `http.headers` | Headers (HeaderSet) |
| `http.querystring` | Query parameters (QueryStringHeaderSet) |
| `http.response.status` | Response status (for response validation) |
| `openapi.validation.level` | Validation level (light/lenient/strict) |
| `openapi.validation.debug` | Enable debug mode ("true") |

### Output Attributes

| Attribute | Description |
|-----------|-------------|
| `openapi.validation.failed` | "true" if validation errors exist |
| `openapi.validation.blocked` | "true" if flow should be blocked |
| `openapi.validation.error` | Error messages |
| `openapi.validation.errors.count` | Number of errors |
| `openapi.validation.errors.all` | All messages |
| `openapi.validation.type` | "request" or "response" |
| `openapi.validation.debug.info` | Debug information (when enabled) |

## Java Classes

- `BnppfOpenAPIValidator` - Main validator class
- `ValidationLevel` - Enum for validation levels
- `ValidationResult` - Validation result holder
- `Utils` - Utility methods
- `APIManagerSchemaProvider` - Fetch specs from API Manager
