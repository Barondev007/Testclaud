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

### Option A: Using Proxy Resource File with JavaScript Include (Recommended for Apigee Edge)

Store your OpenAPI spec as a JavaScript object and use `<IncludeURL>` to load it. This works reliably in Apigee Edge.

**Step 1: Convert your YAML spec to a JavaScript object file**

First, convert your YAML to JSON, then wrap it as a JavaScript object.

Create `resources/jsc/openapi-spec.js` containing your spec as a JavaScript object:
```javascript
// openapi-spec.js - Your OpenAPI specification as a JavaScript object
var OPENAPI_SPEC = {
  "openapi": "3.0.3",
  "info": {
    "title": "Users API",
    "version": "1.0.0"
  },
  "paths": {
    "/users": {
      "post": {
        "requestBody": {
          "required": true,
          "content": {
            "application/json": {
              "schema": {
                "type": "object",
                "required": ["name", "email"],
                "properties": {
                  "name": { "type": "string" },
                  "email": { "type": "string", "format": "email" }
                }
              }
            }
          }
        },
        "responses": {
          "201": { "description": "Created" }
        }
      }
    }
  }
};
```

> **Converting YAML to JavaScript object:**
> 1. Convert YAML to JSON using an online tool or CLI: `yq -o=json petstore.yaml > petstore.json`
> 2. Add `var OPENAPI_SPEC = ` at the beginning and `;` at the end
> 3. Save as `openapi-spec.js`
>
> Example script:
> ```bash
> # Convert YAML to JSON, then to JS object
> echo "var OPENAPI_SPEC = " > openapi-spec.js
> yq -o=json petstore.yaml >> openapi-spec.js
> echo ";" >> openapi-spec.js
> ```

**Step 2: Create the loader JavaScript**

Create `resources/jsc/loadOasSpec.js`:
```javascript
// loadOasSpec.js - Converts JS object to JSON string and sets flow variable
// OPENAPI_SPEC is defined in openapi-spec.js (included via IncludeURL)
context.setVariable('openapi.spec.content', JSON.stringify(OPENAPI_SPEC));
```

**Step 3: Proxy bundle structure**

```
apiproxy/
├── proxies/
│   └── default.xml
├── policies/
│   ├── JS-LoadOasSpec.xml
│   └── JavaCallout-ValidateRequest.xml
└── resources/
    ├── jsc/
    │   ├── openapi-spec.js       ← Your spec as JS variable
    │   └── loadOasSpec.js        ← Loader script
    └── java/
        └── openapi-validator-apigee-callout-1.0.0.jar
```

**Step 4: Create the JavaScript policy**

Create `policies/JS-LoadOasSpec.xml`:
```xml
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Javascript name="JS-LoadOasSpec" timeLimit="2000">
    <!-- Include the spec file first (defines OPENAPI_SPEC variable) -->
    <IncludeURL>jsc://openapi-spec.js</IncludeURL>
    <!-- Main script that sets the flow variable -->
    <ResourceURL>jsc://loadOasSpec.js</ResourceURL>
</Javascript>
```

**Step 5: Configure proxy flow**

```xml
<PreFlow>
    <Request>
        <!-- Load spec from JavaScript include into variable -->
        <Step>
            <Name>JS-LoadOasSpec</Name>
        </Step>
        <!-- Validate request using the variable -->
        <Step>
            <Name>JavaCallout-ValidateRequest</Name>
        </Step>
        <Step>
            <Name>RaiseFault-ValidationError</Name>
            <Condition>openapi.validation.failed = "true"</Condition>
        </Step>
    </Request>
</PreFlow>
```

**Step 6: Configure Java Callout**

```xml
<JavaCallout name="JavaCallout-ValidateRequest">
    <Properties>
        <Property name="specfile">{openapi.spec.content}</Property>
        <Property name="validation-type">request</Property>
        <Property name="validation-level">strict</Property>
    </Properties>
    <ClassName>com.example.apigee.OpenApiValidatorCallout</ClassName>
    <ResourceURL>java://openapi-validator-apigee-callout-1.0.0.jar</ResourceURL>
</JavaCallout>
```

> **Benefits:** Works reliably in Apigee Edge, no KVM size limits, spec is version-controlled with proxy.

See [JavaScript policy documentation](https://docs.apigee.com/api-platform/reference/policies/javascript-policy) for more details on `<IncludeURL>`.

### Option B: Using URL (For External Spec Storage)

Store your OpenAPI spec in a cloud storage bucket (GCS, S3, Azure Blob) or any HTTP server, and fetch it via URL. The spec is cached after first fetch.

**Step 1: Upload spec to storage**

Upload your spec to a publicly accessible URL or one that the Apigee MP can access:
- Google Cloud Storage: `https://storage.googleapis.com/your-bucket/openapi/petstore.yaml`
- AWS S3: `https://your-bucket.s3.amazonaws.com/openapi/petstore.yaml`
- Any HTTP server: `https://your-server.com/specs/petstore.yaml`

**Step 2: Configure Java Callout**

```xml
<JavaCallout name="JavaCallout-ValidateRequest">
    <Properties>
        <Property name="spec-url">https://storage.googleapis.com/your-bucket/openapi/petstore.yaml</Property>
        <Property name="validation-type">request</Property>
        <Property name="validation-level">strict</Property>
    </Properties>
    <ClassName>com.example.apigee.OpenApiValidatorCallout</ClassName>
    <ResourceURL>java://openapi-validator-apigee-callout-1.0.0.jar</ResourceURL>
</JavaCallout>
```

**Using a variable for the URL:**
```xml
<Property name="spec-url">{openapi.spec.url}</Property>
```

> **Note:** The spec is cached after first fetch. To reload, redeploy the proxy or call `clearUrlCache()`.

### Option B: Using Proxy Resource File (Like OASValidation Policy)

Upload your OpenAPI spec as a resource file in the proxy bundle. The Java Callout accesses it directly, similar to the built-in OASValidation policy.

**Step 1: Add spec file to proxy resources**

```
apiproxy/
├── proxies/
│   └── default.xml
├── policies/
│   └── JavaCallout-ValidateRequest.xml
└── resources/
    ├── oas/
    │   └── petstore.yaml        ← Your OpenAPI spec file (OAS type)
    └── java/
        └── openapi-validator-apigee-callout-1.0.0.jar
```

**Using Apigee UI:**
1. Go to **Develop** tab
2. Click **+** next to **Resources**
3. Select resource type: **OpenAPI Spec** (or **oas**)
4. Upload your spec file

**Step 2: Configure Java Callout**

```xml
<JavaCallout name="JavaCallout-ValidateRequest">
    <Properties>
        <!-- Reference the resource file directly -->
        <Property name="spec-resource">oas://petstore.yaml</Property>
        <Property name="validation-type">request</Property>
        <Property name="validation-level">strict</Property>
    </Properties>
    <ClassName>com.example.apigee.OpenApiValidatorCallout</ClassName>
    <ResourceURL>java://openapi-validator-apigee-callout-1.0.0.jar</ResourceURL>
</JavaCallout>
```

**Supported resource path formats:**
- `oas://petstore.yaml` - OAS resource type (recommended)
- `openapi/petstore.yaml` - Direct path
- `petstore.yaml` - Simple filename

> **Note:** This works similar to the built-in OASValidation policy's `<OASResource>` element.

### Option C: Using Resource File Bundled in JAR

Bundle your OpenAPI spec **inside the JAR** at build time. Useful if you want a self-contained JAR.

**Step 1: Add spec to JAR source**

```
apigee-callout/
├── src/main/
│   ├── java/...
│   └── resources/
│       └── openapi/
│           └── petstore.yaml    ← Your spec file HERE
└── pom.xml
```

**Step 2: Rebuild the JAR**
```bash
mvn clean package
```

**Step 3: Configure Java Callout**

```xml
<JavaCallout name="JavaCallout-ValidateRequest">
    <Properties>
        <Property name="spec-resource">openapi/petstore.yaml</Property>
        <Property name="validation-type">request</Property>
    </Properties>
    <ClassName>com.example.apigee.OpenApiValidatorCallout</ClassName>
    <ResourceURL>java://openapi-validator-apigee-callout-1.0.0.jar</ResourceURL>
</JavaCallout>
```

> **Tip:** To update the spec, rebuild the JAR and redeploy.

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
| `spec-url` | No* | - | URL to fetch spec from (e.g., `https://storage.googleapis.com/bucket/spec.yaml`). **Recommended for Apigee Edge.** |
| `spec-resource` | No* | - | Path to spec file in proxy resources (e.g., `oas://petstore.yaml` or `openapi/petstore.yaml`). Works like OASValidation policy. |
| `validation-type` | No | `request` | Type of validation: `request` or `response` |
| `validation-level` | No | `strict` | Validation level: `strict`, `lenient`, or `light` |

> **Note:** One of `specfile`, `spec-url`, or `spec-resource` must be provided. They are checked in that order.

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
