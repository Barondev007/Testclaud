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

## WAF Troubleshooting

If your Web Application Firewall (WAF) blocks the JAR deployment, use these commands to analyze the JAR contents.

### Diagnostic Commands

```bash
cd apigee-validator

# Rebuild the JAR
mvn clean package -DskipTests

# Check JAR size
ls -lh target/openapivalidator-1.0.0.jar

# Count files in JAR
jar -tf target/openapivalidator-1.0.0.jar | wc -l

# List all JAR contents to a file
jar -tf target/openapivalidator-1.0.0.jar > jar-contents.txt
```

### Find Potential WAF Triggers

```bash
# Find suspicious class names (constructors, unsafe, reflection, etc.)
jar -tf target/openapivalidator-1.0.0.jar | grep -iE "(Unsafe|Constructor|Reflect|Script|Exec|Runtime|Process|Deseriali|URL|Remote)"

# Find embedded data files (should be empty after filtering)
jar -tf target/openapivalidator-1.0.0.jar | grep -iE "\.(json|yaml|yml|js|sh|xml|properties)$"

# Find non-class files
jar -tf target/openapivalidator-1.0.0.jar | grep -vE "\.class$"

# Check for SnakeYAML Constructor classes (deserialization risk)
jar -tf target/openapivalidator-1.0.0.jar | grep -i "constructor"

# Check for URL fetching classes (SSRF risk)
jar -tf target/openapivalidator-1.0.0.jar | grep -iE "(remote|url|fetch|http)"
```

### Search for Suspicious Strings in Bytecode

```bash
# Extract and search for shell/exec patterns
unzip -p target/openapivalidator-1.0.0.jar | strings | grep -iE "(exec|eval|Runtime|ProcessBuilder|cmd\.exe|/bin/sh)" | head -30

# Search for SQL patterns
unzip -p target/openapivalidator-1.0.0.jar | strings | grep -iE "(SELECT.*FROM|DROP TABLE|INSERT INTO)" | head -20

# Search for script injection patterns
unzip -p target/openapivalidator-1.0.0.jar | strings | grep -iE "(<script|javascript:|onclick=)" | head -20

# Search for path traversal patterns
unzip -p target/openapivalidator-1.0.0.jar | strings | grep -E "\.\./|\.\.\\\\|file://" | head -20
```

### Common WAF Triggers in This JAR

| Component | Risk | Mitigation |
|-----------|------|------------|
| SnakeYAML | Deserialization gadgets | UnsafeConstructor classes excluded |
| Embedded JSON/YAML | Example specs with patterns | All data files excluded |
| URL Resolver | SSRF-like patterns | swagger-parser-safe-url-resolver excluded |
| Reflection libs | Code execution risk | ClassMate excluded |

### If WAF Still Blocks

1. **Get the specific WAF rule ID** from your WAF administrator
2. **Identify the exact pattern** that triggered the block
3. **Add targeted exclusions** to the shade plugin in `pom.xml`

Example exclusion in pom.xml:
```xml
<filter>
    <artifact>com.example:problematic-lib</artifact>
    <excludes>
        <exclude>**/ProblematicClass.class</exclude>
    </excludes>
</filter>
```

### WAF Testing Profiles

Use these Maven profiles to build different JAR versions, each excluding specific dependencies. Deploy each one to identify which dependency triggers the WAF.

#### Build Commands

```bash
# Build all test versions
mvn clean package -Pwaf-test-minimal -DskipTests
mvn clean package -Pwaf-test-no-snakeyaml -DskipTests
mvn clean package -Pwaf-test-no-networknt -DskipTests
mvn clean package -Pwaf-test-no-swagger-parser -DskipTests
mvn clean package -Pwaf-test-no-swagger-core -DskipTests
mvn clean package -Pwaf-test-no-atlassian -DskipTests
```

#### Test JARs Generated

| Profile | JAR Name | What's Excluded |
|---------|----------|-----------------|
| `waf-test-minimal` | `openapivalidator-MINIMAL.jar` | All dependencies (only your code) |
| `waf-test-no-snakeyaml` | `openapivalidator-NO-SNAKEYAML.jar` | SnakeYAML (YAML parsing) |
| `waf-test-no-networknt` | `openapivalidator-NO-NETWORKNT.jar` | Networknt JSON Schema Validator |
| `waf-test-no-swagger-parser` | `openapivalidator-NO-SWAGGER-PARSER.jar` | Swagger Parser v3 |
| `waf-test-no-swagger-core` | `openapivalidator-NO-SWAGGER-CORE.jar` | Swagger Core v3 |
| `waf-test-no-atlassian` | `openapivalidator-NO-ATLASSIAN.jar` | Atlassian Validator Core |

#### Testing Procedure

1. **Start with MINIMAL** - deploy `openapivalidator-MINIMAL.jar`
   - If blocked: WAF triggers on your own code
   - If passes: Continue testing

2. **Test each profile** - deploy each JAR one by one
   - Track which ones pass and which ones fail

3. **Identify the culprit** - the dependency in the blocked JAR is the trigger

4. **Binary search** - if multiple fail, combine exclusions to narrow down

#### Quick Test Script

```bash
#!/bin/bash
# Build all WAF test JARs
cd apigee-validator

profiles=(
    "waf-test-minimal"
    "waf-test-no-snakeyaml"
    "waf-test-no-networknt"
    "waf-test-no-swagger-parser"
    "waf-test-no-swagger-core"
    "waf-test-no-atlassian"
)

for profile in "${profiles[@]}"; do
    echo "Building $profile..."
    mvn clean package -P$profile -DskipTests -q
done

echo ""
echo "Generated JARs:"
ls -lh target/*.jar
```
