# OpenAPI Validator for Apigee

This module provides OpenAPI request/response validation optimized for Google Apigee.

## Features

- **Apigee-compatible**: All dependencies are shaded to avoid conflicts with Apigee's runtime
- **Lightweight**: Minimized JAR with only essential dependencies
- **Simple API**: Easy to use from Java Callouts or JavaScript policies
- **Validation Levels**: STRICT, LENIENT, and LIGHT modes
- **Caching**: Validator instances are cached for performance

## Building

```bash
cd apigee-validator
mvn clean package
```

This creates: `target/openapi-validator-apigee-1.0.0.jar`

## Uploading to Apigee

1. Go to your Apigee organization
2. Navigate to **Develop > API Proxies > [Your Proxy] > Develop**
3. Click **+ New** under Resources
4. Select **Java** and upload `openapi-validator-apigee-1.0.0.jar`

## Usage in Apigee

### Option 1: Java Callout Policy

```xml
<JavaCallout name="ValidateRequest">
    <Properties>
        <Property name="spec">{openapi.spec}</Property>
        <Property name="level">LENIENT</Property>
    </Properties>
    <ClassName>be.bnppf.openapi.validator.apigee.ApigeeCallout</ClassName>
    <ResourceURL>java://openapi-validator-apigee-1.0.0.jar</ResourceURL>
</JavaCallout>
```

### Option 2: JavaScript Policy

```javascript
// Load the validator
var ApigeeOpenApiValidator = Java.type('be.bnppf.openapi.validator.apigee.ApigeeOpenApiValidator');

// Get OpenAPI spec from a variable
var specContent = context.getVariable('openapi.spec');

// Get request details
var method = context.getVariable('request.verb');
var path = context.getVariable('proxy.pathsuffix');
var body = context.getVariable('request.content');

// Create validator (cached)
var validator = ApigeeOpenApiValidator.getInstance(specContent, 'LENIENT');

// Validate request
var result = validator.validateRequest(body, method, path);

// Check result
if (result.isBlocked()) {
    context.setVariable('validation.failed', true);
    context.setVariable('validation.errors', result.getErrors().toString());
} else {
    context.setVariable('validation.failed', false);
}
```

### Option 3: Simple JavaScript Usage

```javascript
var ApigeeCallout = Java.type('be.bnppf.openapi.validator.apigee.ApigeeCallout');

// Simple validation
var resultJson = ApigeeCallout.validateRequestSimple(
    specContent,
    'POST',
    '/users',
    '{"name":"John"}'
);

var result = JSON.parse(resultJson);
if (!result.valid) {
    // Handle validation failure
}
```

## Validation Levels

| Level | Description |
|-------|-------------|
| STRICT | All spec rules enforced. Additional properties cause errors. |
| LENIENT | Additional properties allowed (reported as warnings). Default. |
| LIGHT | Minimal validation. Most issues reported as info/warnings. |

## Response Validation

```javascript
var result = validator.validateResponse(
    responseBody,      // Response body string
    'POST',            // Original request method
    '/users',          // Original request path
    200,               // Response status code
    responseHeaders    // Response headers map
);
```

## Troubleshooting

### "Class not found" error
- Ensure the JAR is uploaded to your Apigee environment
- Check the ResourceURL path in your policy

### "Duplicate class" or version conflict
- This shaded JAR relocates all dependencies to avoid conflicts
- If you still see conflicts, check for other JARs in your proxy

### Performance issues
- Validators are cached by spec content + level
- First validation may be slower (spec parsing)
- Subsequent validations are fast

## Dependencies (all shaded)

All dependencies are relocated to `apigee.shaded.*` to avoid conflicts:

- Atlassian Swagger Request Validator
- Jackson (JSON processing)
- SnakeYAML
- SLF4J (NOP logger)
- Swagger Parser
- JSON Schema Validator
