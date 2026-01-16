# Axway API Gateway - Swagger Request Validator Integration

This guide explains how to integrate the Atlassian Swagger Request Validator into Axway API Gateway using a Groovy Script Filter, with support for:
- Allowing additional properties (lenient validation)
- **Multiple APIs in parallel** (multi-tenant)
- **Performance optimization** (validator caching)

## Architecture Overview

```
                    ┌─────────────────────────────────────────┐
                    │           ValidatorCache                │
                    │  (ConcurrentHashMap - Thread-Safe)      │
                    ├─────────────────────────────────────────┤
                    │  "spec-a.yaml|true" → Validator A       │
                    │  "spec-b.yaml|true" → Validator B       │
                    │  "spec-c.yaml|false" → Validator C      │
                    └─────────────────────────────────────────┘
                                      ▲
                                      │ getValidator()
          ┌───────────────────────────┼───────────────────────────┐
          │                           │                           │
    ┌─────┴─────┐              ┌─────┴─────┐              ┌─────┴─────┐
    │  API A    │              │  API B    │              │  API C    │
    │  Request  │              │  Request  │              │  Request  │
    └───────────┘              └───────────┘              └───────────┘
```

## Prerequisites

- Axway API Gateway 7.7 or later
- Axway Policy Studio
- Java 11 or later

## Step 1: Download Required JAR Dependencies

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

## Step 2: Deploy JARs to Axway

```bash
cp ./libs/*.jar /opt/Axway/apigateway/ext/lib/
```

## Step 3: Deploy Your OpenAPI Specifications

```bash
mkdir -p /opt/Axway/apigateway/conf/openapi/

# Deploy multiple specs for different APIs
cp users-api-spec.yaml /opt/Axway/apigateway/conf/openapi/
cp orders-api-spec.yaml /opt/Axway/apigateway/conf/openapi/
cp products-api-spec.yaml /opt/Axway/apigateway/conf/openapi/
```

## Step 4: Create the Policy in Policy Studio

### 4.1 Create the Validation Policy

1. Right-click on **Policies** → **Add Policy**
2. Name it: `OpenAPI Validation Policy`

### 4.2 Add a "Set Attribute" Filter (to pass the spec path)

Before the validation script, add a **Set Attribute** filter:

1. **Name**: `Set OpenAPI Spec Path`
2. **Attribute Name**: `openapi.spec.path`
3. **Attribute Value**: `file:///opt/Axway/apigateway/conf/openapi/your-api-spec.yaml`

You can also use **Axway selectors** to dynamically set the spec:
```
file:///opt/Axway/apigateway/conf/openapi/${api.name}-spec.yaml
```

### 4.3 Add the Scripting Filter

1. Drag **Scripting Filter** into your policy (after Set Attribute)
2. **Name**: `Validate Request`
3. **Language**: `Groovy`
4. **Script**: Copy content from `SwaggerValidatorFilter.groovy`

### 4.4 Add Error Handling

Add a **Set Message** filter on the failure path:
- **Content-Type**: `application/json`
- **Body**:
```json
{
    "error": "Validation Failed",
    "message": "${openapi.validation.error}"
}
```

## Step 5: Policy Flow Diagram

```
┌─────────────────────┐
│   Incoming Request  │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│   Set Attribute     │
│  openapi.spec.path  │◄─── Dynamic: based on API name/path
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  Scripting Filter   │
│  (Validator Cache)  │◄─── Reuses cached validator per spec
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

## Step 6: Configure for Multiple APIs

### Option A: One Policy per API

Create separate policies, each with its own spec path:

**Policy: Users API Validation**
```
Set Attribute: openapi.spec.path = "file:///opt/Axway/apigateway/conf/openapi/users-api.yaml"
     ↓
Scripting Filter: SwaggerValidatorFilter.groovy
```

**Policy: Orders API Validation**
```
Set Attribute: openapi.spec.path = "file:///opt/Axway/apigateway/conf/openapi/orders-api.yaml"
     ↓
Scripting Filter: SwaggerValidatorFilter.groovy
```

### Option B: Dynamic Spec Selection (Single Reusable Policy)

Use a **Switch on Attribute** or **Compare Attribute** filter:

```
┌─────────────────────┐
│   Incoming Request  │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│  Switch on Path     │
│  http.request.path  │
└──────────┬──────────┘
           │
     ┌─────┼─────┬─────────┐
     │     │     │         │
  /users  /orders  /products
     │     │     │         │
     ▼     ▼     ▼         ▼
  [Set   [Set   [Set     [Default]
  users  orders products
  spec]  spec]  spec]
     │     │     │         │
     └─────┴─────┴─────────┘
                 │
                 ▼
     ┌─────────────────────┐
     │  Scripting Filter   │
     │  (Shared Validator) │
     └─────────────────────┘
```

### Option C: Using API Manager Custom Properties

In API Manager, set a custom property on each API:
- Property: `openapi.spec.path`
- Value: `file:///opt/Axway/apigateway/conf/openapi/my-api-spec.yaml`

The script will automatically read this from the message attributes.

## Message Attributes Reference

| Attribute | Description | Example |
|-----------|-------------|---------|
| `openapi.spec.path` | **Required**. Path to OpenAPI spec | `file:///path/to/spec.yaml` |
| `openapi.allow.additional.properties` | Optional. Allow extra fields | `true` (default) or `false` |
| `openapi.validation.error` | Output. Error message if failed | Set by script |
| `openapi.validation.failed` | Output. Boolean flag | `true` or `false` |

## Performance Characteristics

### Validator Caching

The script uses a **ConcurrentHashMap** to cache validators:

```groovy
class ValidatorCache {
    private static final ConcurrentHashMap<String, OpenApiInteractionValidator> cache = new ConcurrentHashMap<>()

    static OpenApiInteractionValidator getValidator(String specPath, boolean allowAdditionalProperties) {
        String cacheKey = specPath + "|" + allowAdditionalProperties
        return cache.computeIfAbsent(cacheKey) { key ->
            createValidator(specPath, allowAdditionalProperties)
        }
    }
}
```

### Performance Benefits

| Aspect | Without Cache | With Cache |
|--------|---------------|------------|
| First request (cold) | ~500-1000ms (parse spec) | ~500-1000ms |
| Subsequent requests | ~500-1000ms | **<5ms** |
| Memory per spec | N/A | ~1-5MB |
| Thread safety | N/A | Full (ConcurrentHashMap) |

### Cache Management

```groovy
// Clear all cached validators (e.g., after spec update)
ValidatorCache.clearCache()

// Remove specific spec from cache
ValidatorCache.invalidate("file:///path/to/spec.yaml")

// Check cache size
int size = ValidatorCache.getCacheSize()
```

## Restart Axway

After deploying JARs:

```bash
/opt/Axway/apigateway/posix/bin/nodemanager stop
/opt/Axway/apigateway/posix/bin/nodemanager start
```

## Testing

### Test with additional properties (should PASS):

```bash
curl -X POST http://gateway:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{
    "name": "John Doe",
    "email": "john@example.com",
    "extraField": "allowed with lenient validation"
  }'
```

### Test with missing required field (should FAIL):

```bash
curl -X POST http://gateway:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"name": "John Doe"}'
```

## Troubleshooting

### Issue: ClassNotFoundException

**Solution**: Ensure all dependencies are in `ext/lib`:
```bash
mvn dependency:copy-dependencies -DoutputDirectory=./libs
cp ./libs/*.jar /opt/Axway/apigateway/ext/lib/
```

### Issue: Spec file not found

**Solution**: Use absolute path with `file://` prefix:
```
file:///opt/Axway/apigateway/conf/openapi/spec.yaml
```

### Issue: Changes to spec not reflected

**Solution**: The validator is cached. Either:
1. Restart gateway, or
2. Add a management endpoint to call `ValidatorCache.clearCache()`

### View Logs

```bash
tail -f /opt/Axway/apigateway/groups/group-2/instance-1/logs/trace.log | grep -i validation
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
