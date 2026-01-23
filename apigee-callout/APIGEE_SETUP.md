# Apigee - OpenAPI Validator Java Callout

This guide explains how to use the OpenAPI Validator Java Callout in Apigee to validate requests and responses against an OpenAPI specification.

## Features

- **Request and Response validation** (configurable via property)
- **Multiple spec sources**: property, variable, KVM, or resource file
- **Resource file support**: Upload large specs as files (no KVM size limits)
- **Thread-safe validator caching** (one validator per unique spec + validation level)
- **Configurable validation levels** (light, lenient, strict)
- **Multiple APIs in parallel** (each spec gets its own cached validator)

## Architecture

```
                    ┌─────────────────────────────────────────┐
                    │           VALIDATOR_CACHE               │
                    │  (ConcurrentHashMap - Thread-Safe)      │
                    ├─────────────────────────────────────────┤
                    │  "hash-a|true" → Validator A            │
                    │  "hash-b|true" → Validator B            │
                    │  "hash-c|false" → Validator C           │
                    └─────────────────────────────────────────┘
                                      ▲
                                      │ getValidator()
          ┌───────────────────────────┼───────────────────────────┐
          │                           │                           │
    ┌─────┴─────┐              ┌─────┴─────┐              ┌─────┴─────┐
    │  Proxy A  │              │  Proxy B  │              │  Proxy C  │
    │  Request  │              │  Request  │              │  Request  │
    └───────────┘              └───────────┘              └───────────┘
```

## Step 1: Build the JAR

```bash
cd apigee-callout
mvn clean package
```

This creates an uber-JAR with all dependencies:
```
target/openapi-validator-apigee-callout-1.0.0.jar
```

## Step 2: Add JAR to Your Apigee Proxy

### Option A: Using Apigee UI

1. Open your API proxy in Apigee Edge/X console
2. Go to **Develop** tab
3. Click **+** next to **Resources**
4. Select **JAR** and upload `openapi-validator-apigee-callout-1.0.0.jar`

### Option B: Using Proxy Bundle Structure

Place the JAR in your proxy bundle:
```
apiproxy/
├── proxies/
│   └── default.xml
├── targets/
│   └── default.xml
├── policies/
│   └── JavaCallout-ValidateRequest.xml
└── resources/
    └── java/
        └── openapi-validator-apigee-callout-1.0.0.jar
```

## Step 3: Store Your OpenAPI Spec

### Option A: Using Resource File (Recommended for Large Specs)

Upload your OpenAPI spec as a resource file in the proxy bundle. This avoids KVM size limits (~512KB).

**Proxy bundle structure:**
```
apiproxy/
├── proxies/
│   └── default.xml
├── policies/
│   └── JavaCallout-ValidateRequest.xml
└── resources/
    ├── java/
    │   └── openapi-validator-apigee-callout-1.0.0.jar
    └── openapi/
        └── petstore.yaml    ← Your OpenAPI spec file
```

**Using Apigee UI:**
1. Go to **Develop** tab
2. Click **+** next to **Resources**
3. Select **Other** (or appropriate type)
4. Upload your spec file (e.g., `petstore.yaml`)
5. Set the resource path (e.g., `openapi/petstore.yaml`)

**Java Callout configuration:**
```xml
<JavaCallout name="JavaCallout-ValidateRequest">
    <Properties>
        <Property name="spec-resource">openapi/petstore.yaml</Property>
        <Property name="validation-type">request</Property>
        <Property name="validation-level">strict</Property>
    </Properties>
    <ClassName>com.example.apigee.OpenApiValidatorCallout</ClassName>
    <ResourceURL>java://openapi-validator-apigee-callout-1.0.0.jar</ResourceURL>
</JavaCallout>
```

### Option B: Using KVM (Key Value Map)

> **Note:** KVM has a size limit of ~512KB per entry. For large specs, use Option A (Resource File).

1. Create a KVM named `openapi-specs`
2. Add an entry:
   - Key: `users-api-spec`
   - Value: (your OpenAPI spec YAML/JSON content)

### Option C: Using Properties

Store the spec in a property file or environment variable.

### Option D: Using AssignMessage to Set Variable

Create an AssignMessage policy to set the spec content:

```xml
<!-- AssignMessage-SetSpec.xml -->
<AssignMessage name="AssignMessage-SetSpec">
    <AssignVariable>
        <Name>openapi.spec.content</Name>
        <Value>
openapi: 3.0.3
info:
  title: Users API
  version: 1.0.0
paths:
  /users:
    post:
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required:
                - name
                - email
              properties:
                name:
                  type: string
                email:
                  type: string
                  format: email
        </Value>
    </AssignVariable>
</AssignMessage>
```

## Step 4: Create the Java Callout Policy

### Request Validation

Create `JavaCallout-ValidateRequest.xml`:

```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<JavaCallout name="JavaCallout-ValidateRequest">
    <Properties>
        <!-- Spec content: can be literal or variable reference -->
        <Property name="specfile">{openapi.spec.content}</Property>

        <!-- Validation type: "request" (default) or "response" -->
        <Property name="validation-type">request</Property>

        <!-- Validation level: "strict" (default), "lenient", or "light" -->
        <Property name="validation-level">strict</Property>
    </Properties>
    <ClassName>com.example.apigee.OpenApiValidatorCallout</ClassName>
    <ResourceURL>java://openapi-validator-apigee-callout-1.0.0.jar</ResourceURL>
</JavaCallout>
```

### Response Validation

Create `JavaCallout-ValidateResponse.xml`:

```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<JavaCallout name="JavaCallout-ValidateResponse">
    <Properties>
        <Property name="specfile">{openapi.spec.content}</Property>
        <Property name="validation-type">response</Property>
        <Property name="validation-level">strict</Property>
    </Properties>
    <ClassName>com.example.apigee.OpenApiValidatorCallout</ClassName>
    <ResourceURL>java://openapi-validator-apigee-callout-1.0.0.jar</ResourceURL>
</JavaCallout>
```

### Validation Levels

| Level | Behavior |
|-------|----------|
| `strict` | All validation errors block the flow |
| `lenient` | Additional properties are ignored, other errors block |
| `light` | Errors are stored in variables but flow is NOT blocked |

### Using KVM for Spec Content

If using KVM, first retrieve the spec with KeyValueMapOperations:

```xml
<!-- KVM-GetSpec.xml -->
<KeyValueMapOperations name="KVM-GetSpec" mapIdentifier="openapi-specs">
    <Scope>environment</Scope>
    <Get assignTo="openapi.spec.content">
        <Key>
            <Parameter>users-api-spec</Parameter>
        </Key>
    </Get>
</KeyValueMapOperations>
```

Then reference the variable in the Java Callout:
```xml
<Property name="specfile">{openapi.spec.content}</Property>
```

## Step 5: Create Error Handling Policy

Create `RaiseFault-ValidationError.xml`:

```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<RaiseFault name="RaiseFault-ValidationError">
    <FaultResponse>
        <Set>
            <StatusCode>400</StatusCode>
            <ReasonPhrase>Bad Request</ReasonPhrase>
            <Payload contentType="application/json">
{
    "error": "Validation Failed",
    "message": "{openapi.validation.error}"
}
            </Payload>
        </Set>
    </FaultResponse>
    <IgnoreUnresolvedVariables>true</IgnoreUnresolvedVariables>
</RaiseFault>
```

## Step 6: Configure Proxy Flow

Update your proxy endpoint (`proxies/default.xml`):

```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<ProxyEndpoint name="default">
    <PreFlow name="PreFlow">
        <Request>
            <!-- Step 1: Get spec from KVM (if using KVM) -->
            <Step>
                <Name>KVM-GetSpec</Name>
            </Step>

            <!-- Step 2: Validate request against OpenAPI spec -->
            <Step>
                <Name>JavaCallout-ValidateRequest</Name>
            </Step>

            <!-- Step 3: Handle validation errors -->
            <Step>
                <Name>RaiseFault-ValidationError</Name>
                <Condition>openapi.validation.failed = "true"</Condition>
            </Step>
        </Request>
        <Response>
            <!-- Step 4: Validate response against OpenAPI spec -->
            <Step>
                <Name>JavaCallout-ValidateResponse</Name>
            </Step>

            <!-- Step 5: Handle response validation errors (optional) -->
            <Step>
                <Name>RaiseFault-ResponseValidationError</Name>
                <Condition>openapi.validation.failed = "true"</Condition>
            </Step>
        </Response>
    </PreFlow>

    <HTTPProxyConnection>
        <BasePath>/v1/users</BasePath>
        <VirtualHost>secure</VirtualHost>
    </HTTPProxyConnection>

    <RouteRule name="default">
        <TargetEndpoint>default</TargetEndpoint>
    </RouteRule>
</ProxyEndpoint>
```

## Step 7: Flow Diagram

```
┌─────────────────────┐
│   Incoming Request  │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│    KVM-GetSpec      │  ← Get spec content from KVM
│  (if using KVM)     │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│   JavaCallout       │  ← Validate request
│  ValidateRequest    │     (validation-type=request)
└──────────┬──────────┘
           │
     ┌─────┴─────┐
     │           │
  SUCCESS    validation.failed="true"
     │           │
     │           ▼
     │      ┌─────────────┐
     │      │ RaiseFault  │
     │      │ (400 Error) │
     │      └─────────────┘
     ▼
┌─────────────────────┐
│    Target Server    │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│   JavaCallout       │  ← Validate response
│  ValidateResponse   │     (validation-type=response)
└──────────┬──────────┘
           │
     ┌─────┴─────┐
     │           │
  SUCCESS    validation.failed="true"
     │           │
     ▼           ▼
┌─────────┐  ┌─────────────┐
│ Return  │  │ RaiseFault  │
│ Response│  │ (500 Error) │
└─────────┘  └─────────────┘
```

## Step 8: Deploy and Test

### Deploy the Proxy

```bash
# Using Apigee CLI (apigeecli)
apigeecli apis create bundle -f apiproxy -n my-api-proxy

# Or using Maven
mvn install -Ptest -Dorg=your-org -Denv=test
```

### Test with Additional Properties (should PASS):

```bash
curl -X POST https://your-org-test.apigee.net/v1/users \
  -H "Content-Type: application/json" \
  -d '{
    "name": "John Doe",
    "email": "john@example.com",
    "extraField": "this is allowed"
  }'
```

### Test with Missing Required Field (should FAIL):

```bash
curl -X POST https://your-org-test.apigee.net/v1/users \
  -H "Content-Type: application/json" \
  -d '{"name": "John Doe"}'
```

Expected response:
```json
{
    "error": "Validation Failed",
    "message": "[validation.request.body.schema.required] Object has missing required properties ([\"email\"])"
}
```

## Configuration Reference

### Java Callout Properties

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| `specfile` | No* | - | OpenAPI spec content (YAML or JSON). Can be literal or variable reference `{varName}` |
| `spec-resource` | No* | - | Path to spec file in proxy resources (e.g., `openapi/petstore.yaml`). **Recommended for large specs.** |
| `validation-type` | No | `request` | Type of validation: `request` or `response` |
| `validation-level` | No | `strict` | Validation level: `strict`, `lenient`, or `light` |

> **Note:** Either `specfile` or `spec-resource` must be provided. `specfile` takes precedence if both are set.

### Output Variables

| Variable | Description |
|----------|-------------|
| `openapi.validation.failed` | `"true"` or `"false"` |
| `openapi.validation.error` | Error message(s) if validation fails |
| `openapi.validation.errors.count` | Number of validation errors |
| `openapi.validation.errors.all` | All error messages (useful for light mode) |
| `openapi.validation.type` | Type of validation performed: `"request"` or `"response"` |

### Input Variables (read by the callout)

#### For Request Validation
| Variable | Description |
|----------|-------------|
| `request.verb` | HTTP method (GET, POST, etc.) |
| `proxy.pathsuffix` | Request path |
| `request.content` | Request body |
| `request.header.content-type` | Content-Type header |
| `request.querystring` | Query parameters |

#### For Response Validation
| Variable | Description |
|----------|-------------|
| `request.verb` | HTTP method (needed to match operation) |
| `proxy.pathsuffix` | Request path (needed to match operation) |
| `response.status.code` | HTTP response status code |
| `response.content` | Response body |
| `response.header.content-type` | Response Content-Type header |

## Performance

| Aspect | First Request | Subsequent Requests |
|--------|---------------|---------------------|
| Validator creation | ~500-1000ms | 0ms (cached) |
| Request validation | ~1-5ms | ~1-5ms |
| Memory per spec | ~1-5MB | Reused |

## Multiple APIs Example

For multiple APIs, use different KVM keys or variables:

```xml
<!-- For Users API -->
<Step>
    <Name>KVM-GetSpec</Name>
    <Condition>proxy.pathsuffix MatchesPath "/users/**"</Condition>
</Step>

<!-- For Orders API -->
<Step>
    <Name>KVM-GetOrdersSpec</Name>
    <Condition>proxy.pathsuffix MatchesPath "/orders/**"</Condition>
</Step>

<!-- Same Java Callout works for both -->
<Step>
    <Name>JavaCallout-ValidateRequest</Name>
</Step>
```

Each unique spec content gets its own cached validator automatically.

## Troubleshooting

### Issue: ClassNotFoundException

**Cause**: JAR not properly uploaded or wrong path

**Solution**: Verify JAR is in `apiproxy/resources/java/` and `ResourceURL` matches

### Issue: Spec parsing error

**Cause**: Invalid YAML/JSON in spec content

**Solution**: Validate your OpenAPI spec using a tool like Swagger Editor

### Issue: Variable not resolved

**Cause**: Variable reference `{varName}` not set before callout

**Solution**: Ensure KVM or AssignMessage runs before JavaCallout

### View Trace

Use Apigee Debug/Trace to see:
- `openapi.validation.error`
- `openapi.validation.failed`
- Java Callout execution time
