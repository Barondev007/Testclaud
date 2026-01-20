# Developer Quick-Start Guide

## OpenAPI Validator for Axway API Gateway

---

## 5-Minute Setup

### 1. Build the JAR

```bash
cd axway-validator
mvn clean package
```

### 2. Deploy

```bash
# Copy JAR to Axway
cp target/openapi-validator-1.0.0.jar $AXWAY_HOME/ext/lib/

# Copy Groovy script
cp src/main/resources/axway/scripts/SwaggerValidatorFilterV3.groovy \
   $AXWAY_HOME/scripts/

# Restart Axway
```

### 3. Configure in Policy Studio

1. Add **Set Attribute** filter before your validation:
   - `openapi.spec` = Your OpenAPI spec as string
   - `openapi.validation.level` = `LENIENT`

2. Add **Scripting Filter**:
   - Language: Groovy
   - Script: `SwaggerValidatorFilterV3.groovy`

---

## API Reference

### Classes

```
be.bnppf.openapi.validator
├── OpenAPIValidator    # Main validator
├── ValidationLevel     # Enum: LIGHT, LENIENT, STRICT
├── ValidationResult    # Validation outcome
└── Utils               # Helper utilities
```

### Key Methods

```java
// Get cached validator instance
OpenAPIValidator validator = OpenAPIValidator.getInstance(
    String openAPISpec,        // OpenAPI spec as string
    ValidationLevel level,     // LIGHT, LENIENT, or STRICT
    boolean useCache          // true for production
);

// Validate a request
ValidationResult result = validator.isValidRequest(
    String method,             // GET, POST, PUT, DELETE, etc.
    String path,               // /api/users/123
    HeaderSet headers,         // Request headers
    QueryStringHeaderSet query,// Query parameters
    String body               // Request body (can be null)
);

// Check results
result.isValid()      // true if no errors
result.isBlocked()    // true if should block request
result.getErrors()    // List of validation errors
```

---

## Validation Levels Quick Reference

```
LIGHT:
├── Path exists: ✓
├── Method allowed: ✓
├── Required fields: Warn only
├── Type checking: Warn only
└── Extra properties: Allowed

LENIENT (Recommended):
├── Path exists: ✓
├── Method allowed: ✓
├── Required fields: ✓ Block if missing
├── Type checking: ✓ Block if wrong
└── Extra properties: Allowed ← Key difference

STRICT:
├── Path exists: ✓
├── Method allowed: ✓
├── Required fields: ✓ Block if missing
├── Type checking: ✓ Block if wrong
└── Extra properties: ✗ Block if present
```

---

## Groovy Script Template

```groovy
import be.bnppf.openapi.validator.OpenAPIValidator
import be.bnppf.openapi.validator.ValidationLevel
import be.bnppf.openapi.validator.ValidationResult
import com.vordel.trace.Trace

// Configuration
def spec = msg.get("openapi.spec")
def level = ValidationLevel.fromString(
    msg.get("openapi.validation.level") ?: "LENIENT"
)

// Get validator (cached)
def validator = OpenAPIValidator.getInstance(spec, level, true)

// Extract request details
def method = http.getVerb()
def path = http.getRequestURI()
def headers = msg.getHeaderSet()
def queryParams = msg.getQueryParams()
def body = bodyAsString

// Validate
ValidationResult result = validator.isValidRequest(
    method, path, headers, queryParams, body
)

// Handle result
if (result.isBlocked()) {
    Trace.error("Validation failed: " + result.getErrors())

    // Set error response
    msg.set("http.response.status", 400)
    msg.set("http.response.body", [
        error: "Bad Request",
        details: result.getErrors()
    ] as JSON)

    return false  // Block request
}

return true  // Allow request to proceed
```

---

## Debugging

### Enable Trace Logging

```groovy
import be.bnppf.openapi.validator.Utils
import com.vordel.trace.Trace

// Before validation
Trace.info("Validating: ${method} ${path}")
Trace.debug("Headers: ${headers}")
Trace.debug("Query: ${queryParams}")

// After validation
if (!result.isValid()) {
    result.getErrors().each { error ->
        Trace.error("Validation error: ${error}")
    }
}
```

### Common Issues

| Issue | Solution |
|-------|----------|
| `ClassNotFoundException` | JAR not in `ext/lib/`, restart Axway |
| `NullPointerException` on spec | Check `openapi.spec` attribute is set |
| Valid requests blocked | Try `LENIENT` or `LIGHT` level |
| Path not found | Check spec paths match actual paths |

---

## Testing

### Run Unit Tests

```bash
cd axway-validator
mvn test
```

### Manual Testing

```bash
# Should pass
curl -X GET "https://gateway/api/users/123"

# Should fail (invalid endpoint)
curl -X GET "https://gateway/api/not-in-spec"

# Should fail (wrong method)
curl -X DELETE "https://gateway/api/users/123"  # if DELETE not defined
```

---

## Project Structure

```
axway-validator/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/be/bnppf/openapi/validator/
│   │   │   ├── OpenAPIValidator.java
│   │   │   ├── ValidationLevel.java
│   │   │   ├── ValidationResult.java
│   │   │   └── Utils.java
│   │   └── resources/axway/scripts/
│   │       └── SwaggerValidatorFilterV3.groovy
│   └── test/
│       └── java/be/bnppf/openapi/validator/
│           ├── OpenAPIValidatorTest.java
│           ├── ValidationLevelTest.java
│           └── ...
└── target/
    └── openapi-validator-1.0.0.jar
```

---

## Maven Coordinates

```xml
<dependency>
    <groupId>be.bnppf</groupId>
    <artifactId>openapi-validator</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

*For detailed documentation, see [OpenAPI-Validator-Documentation.md](OpenAPI-Validator-Documentation.md)*
