# OpenAPI Validator for Apigee

This module provides OpenAPI request/response validation optimized for Google Apigee.

## Features

- **Apigee-compatible**: All dependencies are shaded to avoid conflicts with Apigee's runtime
- **WAF-safe**: Only includes `.class` files, excludes embedded resources that trigger WAF
- **Lightweight**: Minimized JAR with only essential dependencies
- **Simple API**: Easy to use from Java Callouts or JavaScript policies
- **Validation Levels**: STRICT, LENIENT, and LIGHT modes
- **Caching**: Validator instances are cached for performance

## Building

```bash
cd apigee-validator
mvn clean package -DskipTests
```

This creates: `target/openapivalidator-1.0.0.jar`

**Note**: The build is WAF-safe by default. It only includes `.class` files and excludes all embedded resources (JSON, YAML, XML, properties) that can trigger Web Application Firewalls.

## Uploading to Apigee

1. Go to your Apigee organization
2. Navigate to **Develop > API Proxies > [Your Proxy] > Develop**
3. Click **+ New** under Resources
4. Select **Java** and upload `openapivalidator-1.0.0.jar`

## Usage in Apigee

### Option 1: Java Callout Policy

```xml
<JavaCallout name="ValidateRequest">
    <Properties>
        <Property name="spec">{openapi.spec}</Property>
        <Property name="level">LENIENT</Property>
    </Properties>
    <ClassName>be.bnppf.openapi.validator.apigee.ApigeeCallout</ClassName>
    <ResourceURL>java://openapivalidator-1.0.0.jar</ResourceURL>
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

All dependencies are relocated to `shaded.*` to avoid conflicts:

- Atlassian Swagger Request Validator
- Swagger Parser v3
- Swagger Core v3
- Networknt JSON Schema Validator
- SnakeYAML

## WAF Information

This JAR is built with WAF-safe configuration:

- **Only `.class` files** are included
- **All embedded resources excluded**: JSON, YAML, XML, properties, scripts
- **Root cause**: Embedded resources in dependencies (example specs, schemas) trigger WAF pattern matching

### Diagnostic Commands

```bash
# Check JAR contents
jar -tf target/openapivalidator-1.0.0.jar | wc -l

# Verify no embedded resources (should return nothing)
jar -tf target/openapivalidator-1.0.0.jar | grep -iE "\.(json|yaml|yml|xml|properties)$"

# List non-class files (should only show META-INF/MANIFEST.MF)
jar -tf target/openapivalidator-1.0.0.jar | grep -vE "\.class$"
```
