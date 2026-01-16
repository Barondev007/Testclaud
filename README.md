# Swagger Request Validator - Allow Additional Properties

This project demonstrates how to configure the **Atlassian Swagger Request Validator** to allow additional properties in request and response bodies when validating against an OpenAPI specification.

## Problem

By default, the Atlassian Swagger Request Validator strictly validates requests and responses against the OpenAPI specification. If your API clients send extra fields not defined in the spec, or if your backend returns additional fields, the validation will fail.

## Solution

This project shows how to use `LevelResolver` to ignore additional properties validation errors, making the validator less strict.

## Quick Start

### 1. Add Maven Dependency

```xml
<dependency>
    <groupId>com.atlassian.oai</groupId>
    <artifactId>swagger-request-validator-core</artifactId>
    <version>2.40.0</version>
</dependency>
```

### 2. Create a Lenient Validator

```java
import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;

// Create a LevelResolver that ignores additional properties errors
LevelResolver levelResolver = LevelResolver.create()
    .withLevel("validation.request.body.schema.additionalProperties", ValidationReport.Level.IGNORE)
    .withLevel("validation.response.body.schema.additionalProperties", ValidationReport.Level.IGNORE)
    .build();

// Build the validator with the custom level resolver
OpenApiInteractionValidator validator = OpenApiInteractionValidator
    .createForSpecificationUrl("openapi.yaml")
    .withLevelResolver(levelResolver)
    .build();
```

### 3. Use the Factory (Recommended)

This project provides a convenient factory class:

```java
import com.example.validator.OpenApiValidatorFactory;
import com.example.validator.ApiValidationService;

// Option 1: Use the factory directly
OpenApiInteractionValidator validator = OpenApiValidatorFactory.createLenientValidator("openapi.yaml");

// Option 2: Use the service wrapper
ApiValidationService service = ApiValidationService.createLenient("openapi.yaml");

// Validate a request
ValidationReport report = service.validateRequest("POST", "/users", requestBody, "application/json");

if (!report.hasErrors()) {
    System.out.println("Validation passed!");
}
```

## Available Validation Modes

| Method | Description |
|--------|-------------|
| `createLenientValidator()` | Allows additional properties in both requests and responses |
| `createLenientRequestValidator()` | Allows additional properties in requests only |
| `createLenientResponseValidator()` | Allows additional properties in responses only |
| `createStrictValidator()` | Default strict validation (no additional properties allowed) |
| `createMinimalValidator()` | Highly lenient - ignores multiple validation issues |

## Validation Message Keys

Here are the common message keys you can configure:

| Key | Description |
|-----|-------------|
| `validation.request.body.schema.additionalProperties` | Additional properties in request body |
| `validation.response.body.schema.additionalProperties` | Additional properties in response body |
| `validation.request.body.schema.required` | Missing required fields in request |
| `validation.response.body.schema.required` | Missing required fields in response |
| `validation.request.body.schema.type` | Type mismatch in request |
| `validation.response.body.schema.type` | Type mismatch in response |

## Running the Tests

```bash
mvn test
```

The tests demonstrate:
- Strict validation rejecting additional properties
- Lenient validation accepting additional properties
- Both validators properly validating required fields

## Project Structure

```
.
├── pom.xml                                          # Maven configuration
├── src/main/java/com/example/validator/
│   ├── OpenApiValidatorFactory.java                 # Factory for creating validators
│   └── ApiValidationService.java                    # Service wrapper for validation
├── src/main/resources/
│   └── openapi.yaml                                 # Sample OpenAPI specification
└── src/test/java/com/example/validator/
    └── AdditionalPropertiesValidationTest.java      # Tests demonstrating the behavior
```

## Integration with Axway API Gateway

When using this with Axway API Gateway, you can:

1. Create the lenient validator during application startup
2. Use it in a filter/interceptor to validate incoming requests
3. Use it to validate responses before returning them to clients

Example integration point:

```java
@Component
public class ApiValidationFilter implements Filter {

    private final ApiValidationService validationService;

    public ApiValidationFilter() {
        this.validationService = ApiValidationService.createLenient("classpath:openapi.yaml");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        // Extract request details and validate
        ValidationReport report = validationService.validateRequest(...);

        if (report.hasErrors()) {
            // Handle validation errors
        }

        chain.doFilter(request, response);
    }
}
```

## License

MIT
