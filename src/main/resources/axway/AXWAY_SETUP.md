# Axway API Gateway - Swagger Request Validator Integration

This guide explains how to integrate the Atlassian Swagger Request Validator into Axway API Gateway using a Groovy Script Filter, with support for allowing additional properties.

## Prerequisites

- Axway API Gateway 7.7 or later
- Axway Policy Studio
- Java 11 or later

## Step 1: Download Required JAR Dependencies

Download the following JAR files and their dependencies:

### Required JARs

1. **swagger-request-validator-core** (version 2.30.0)
   ```
   https://repo1.maven.org/maven2/com/atlassian/oai/swagger-request-validator-core/2.30.0/swagger-request-validator-core-2.30.0.jar
   ```

2. **Dependencies** (also required):
   - swagger-parser (v2)
   - json-schema-validator
   - jackson-databind
   - slf4j-api

### Easy way: Use Maven to download all dependencies

Create a temporary `pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
    <modelVersion>4.0.0</modelVersion>
    <groupId>temp</groupId>
    <artifactId>temp</artifactId>
    <version>1.0</version>

    <dependencies>
        <dependency>
            <groupId>com.atlassian.oai</groupId>
            <artifactId>swagger-request-validator-core</artifactId>
            <version>2.30.0</version>
        </dependency>
    </dependencies>
</project>
```

Run:
```bash
mvn dependency:copy-dependencies -DoutputDirectory=./libs
```

This will download all required JARs to the `./libs` folder.

## Step 2: Deploy JARs to Axway

Copy all JAR files to the Axway ext/lib directory:

```bash
# Linux
cp ./libs/*.jar /opt/Axway/apigateway/ext/lib/

# Or for a specific instance
cp ./libs/*.jar /opt/Axway/apigateway/groups/group-2/instance-1/ext/lib/
```

## Step 3: Deploy Your OpenAPI Specification

Copy your OpenAPI spec file to a location accessible by Axway:

```bash
mkdir -p /opt/Axway/apigateway/groups/group-2/instance-1/conf/openapi/
cp your-api-spec.yaml /opt/Axway/apigateway/groups/group-2/instance-1/conf/openapi/
```

## Step 4: Create the Scripting Filter in Policy Studio

### 4.1 Open Policy Studio

1. Launch Axway Policy Studio
2. Connect to your API Gateway instance

### 4.2 Create a New Policy

1. Right-click on **Policies** → **Add Policy**
2. Name it: `OpenAPI Validation Policy`

### 4.3 Add a Scripting Filter

1. From the filter palette, drag **Scripting Filter** into your policy
2. Double-click to configure

### 4.4 Configure the Scripting Filter

1. **Name**: `Validate Request Against OpenAPI Spec`
2. **Language**: `Groovy`
3. **Script**: Copy the content from `SwaggerValidatorFilter.groovy`

4. **Modify the configuration section** in the script:

```groovy
// Path to your OpenAPI spec file
def OPENAPI_SPEC_PATH = "file:///opt/Axway/apigateway/groups/group-2/instance-1/conf/openapi/your-api-spec.yaml"

// Set to true to allow additional properties (lenient mode)
def ALLOW_ADDITIONAL_PROPERTIES = true
```

5. Click **Finish**

### 4.5 Add Error Handling (Optional)

1. Add a **Set Message** filter for validation failures
2. Connect it to the **failure path** of the Scripting Filter

Configure the Set Message filter:
- **Content-Type**: `application/json`
- **Body**:
```json
{
    "error": "Validation Failed",
    "message": "${openapi.validation.error}"
}
```

3. Add a **Reflect Message** filter after Set Message
4. Set HTTP status code to `400 Bad Request`

## Step 5: Policy Flow Diagram

```
┌─────────────────────┐
│   Incoming Request  │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  Scripting Filter   │
│  (OpenAPI Validate) │
└──────────┬──────────┘
           │
     ┌─────┴─────┐
     │           │
  Success     Failure
     │           │
     ▼           ▼
┌─────────┐  ┌─────────────┐
│ Continue│  │ Set Message │
│ to API  │  │ (Error 400) │
└─────────┘  └─────────────┘
```

## Step 6: Apply Policy to Your API

### Option A: API Manager

1. In API Manager, edit your API
2. Go to **Inbound Security** or **Request Policy**
3. Select your `OpenAPI Validation Policy`

### Option B: Policy Studio (Direct)

1. In your existing API policy, add the validation filter at the beginning
2. Wire the success path to continue processing
3. Wire the failure path to return an error response

## Step 7: Restart Axway

After deploying the JARs:

```bash
# Restart the instance
/opt/Axway/apigateway/posix/bin/nodemanager stop
/opt/Axway/apigateway/posix/bin/nodemanager start

# Or restart just the gateway instance
/opt/Axway/apigateway/posix/bin/startinstance -n "instance-1" -g "group-2"
```

## Step 8: Test the Validation

### Test with additional properties (should pass with lenient mode):

```bash
curl -X POST http://your-gateway:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{
    "name": "John Doe",
    "email": "john@example.com",
    "extraField": "this should be allowed"
  }'
```

### Test with missing required field (should fail):

```bash
curl -X POST http://your-gateway:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{
    "name": "John Doe"
  }'
```

Expected error response:
```json
{
    "error": "Validation Failed",
    "message": "[validation.request.body.schema.required] Object has missing required properties ([\"email\"])"
}
```

## Customizing Validation Rules

To customize which validations to ignore, modify the `createCustomLevelResolver()` method in `SwaggerValidatorFilterCustom.groovy`:

```groovy
def createCustomLevelResolver() {
    return LevelResolver.create()
        // Ignore additional properties
        .withLevel("validation.request.body.schema.additionalProperties", ValidationReport.Level.IGNORE)
        .withLevel("validation.response.body.schema.additionalProperties", ValidationReport.Level.IGNORE)

        // Change required field validation to warning only
        .withLevel("validation.request.body.schema.required", ValidationReport.Level.WARN)

        // Ignore missing query parameters
        .withLevel("validation.request.parameter.query.missing", ValidationReport.Level.IGNORE)

        .build()
}
```

## Troubleshooting

### Issue: ClassNotFoundException

**Cause**: Missing JAR dependencies

**Solution**: Ensure all transitive dependencies are in the `ext/lib` folder. Use Maven to download all dependencies.

### Issue: Validator not finding the spec file

**Cause**: Incorrect path to OpenAPI spec

**Solution**: Use absolute path with `file://` prefix:
```groovy
def OPENAPI_SPEC_PATH = "file:///opt/Axway/apigateway/groups/group-2/instance-1/conf/openapi/spec.yaml"
```

### Issue: Performance concerns

**Cause**: Validator being recreated on every request

**Solution**: The provided scripts use a cached validator instance (`ValidatorHolder`). Ensure you're not modifying the script in a way that recreates the validator.

### View Logs

Check Axway trace logs:
```bash
tail -f /opt/Axway/apigateway/groups/group-2/instance-1/logs/trace.log
```

## Available Validation Message Keys

| Key | Description |
|-----|-------------|
| `validation.request.body.schema.additionalProperties` | Additional properties in request |
| `validation.response.body.schema.additionalProperties` | Additional properties in response |
| `validation.request.body.schema.required` | Missing required fields |
| `validation.request.body.schema.type` | Type mismatch |
| `validation.request.parameter.query.missing` | Missing query parameter |
| `validation.request.parameter.header.missing` | Missing header |
| `validation.request.path.missing` | Path not found in spec |
