# OpenAPI Validator for Apigee

This module provides OpenAPI request/response validation optimized for Google Apigee.

## Features

- **Apigee-compatible**: All dependencies are shaded to avoid conflicts with Apigee's runtime
- **Lightweight**: Minimized JAR with only essential dependencies
- **Simple API**: Easy to use from Java Callouts or JavaScript policies
- **Validation Levels**: STRICT, LENIENT, and LIGHT modes
- **Caching**: Validator instances are cached for performance

## Building

### Standard Build
```bash
cd apigee-validator
mvn clean package -DskipTests
```

### WAF-Safe Build (Recommended)
If your environment has a Web Application Firewall (WAF), use this profile:
```bash
cd apigee-validator
mvn clean package -Pwaf-safe -DskipTests
```

This creates: `target/openapivalidator-WAF-SAFE.jar`

**Root cause**: Embedded resources (JSON, YAML, XML, properties files) in dependencies trigger WAF rules. The `waf-safe` profile includes only `.class` files, excluding all embedded resources.

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

#### Phase 1: Exclude One Dependency (identify if single dependency is the problem)

```bash
mvn clean package -Pwaf-test-minimal -DskipTests
mvn clean package -Pwaf-test-no-snakeyaml -DskipTests
mvn clean package -Pwaf-test-no-networknt -DskipTests
mvn clean package -Pwaf-test-no-swagger-parser -DskipTests
mvn clean package -Pwaf-test-no-swagger-core -DskipTests
mvn clean package -Pwaf-test-no-atlassian -DskipTests
```

#### Phase 2: Add One Dependency (when MINIMAL passes but others fail)

```bash
mvn clean package -Pwaf-test-only-snakeyaml -DskipTests
mvn clean package -Pwaf-test-only-networknt -DskipTests
mvn clean package -Pwaf-test-only-swagger-parser -DskipTests
mvn clean package -Pwaf-test-only-swagger-core -DskipTests
mvn clean package -Pwaf-test-only-atlassian -DskipTests
```

#### Test JARs Generated

**Phase 1 - Exclude one:**

| Profile | JAR Name | What's Excluded |
|---------|----------|-----------------|
| `waf-test-minimal` | `openapivalidator-MINIMAL.jar` | All dependencies (only your code) |
| `waf-test-no-snakeyaml` | `openapivalidator-NO-SNAKEYAML.jar` | SnakeYAML |
| `waf-test-no-networknt` | `openapivalidator-NO-NETWORKNT.jar` | Networknt JSON Schema |
| `waf-test-no-swagger-parser` | `openapivalidator-NO-SWAGGER-PARSER.jar` | Swagger Parser v3 |
| `waf-test-no-swagger-core` | `openapivalidator-NO-SWAGGER-CORE.jar` | Swagger Core v3 |
| `waf-test-no-atlassian` | `openapivalidator-NO-ATLASSIAN.jar` | Atlassian Validator |

**Phase 2 - Include only one:**

| Profile | JAR Name | What's Included |
|---------|----------|-----------------|
| `waf-test-only-snakeyaml` | `openapivalidator-ONLY-SNAKEYAML.jar` | Your code + SnakeYAML only |
| `waf-test-only-networknt` | `openapivalidator-ONLY-NETWORKNT.jar` | Your code + Networknt only |
| `waf-test-only-swagger-parser` | `openapivalidator-ONLY-SWAGGER-PARSER.jar` | Your code + Swagger Parser only |
| `waf-test-only-swagger-core` | `openapivalidator-ONLY-SWAGGER-CORE.jar` | Your code + Swagger Core only |
| `waf-test-only-atlassian` | `openapivalidator-ONLY-ATLASSIAN.jar` | Your code + Atlassian only |

#### Testing Procedure

**Step 1: Test MINIMAL**
```
Deploy openapivalidator-MINIMAL.jar
├── BLOCKED → Your code triggers WAF (unlikely)
└── PASSES → Continue to Step 2
```

**Step 2: Test ONLY-* profiles (add one dependency at a time)**
```
Deploy each ONLY-*.jar and record results:

openapivalidator-ONLY-SNAKEYAML.jar     → PASS / FAIL
openapivalidator-ONLY-NETWORKNT.jar     → PASS / FAIL
openapivalidator-ONLY-SWAGGER-PARSER.jar → PASS / FAIL
openapivalidator-ONLY-SWAGGER-CORE.jar  → PASS / FAIL
openapivalidator-ONLY-ATLASSIAN.jar     → PASS / FAIL

Any FAIL = that dependency triggers WAF
```

**Step 3: Fix the problematic dependency**
- Once identified, we can add specific class exclusions for that dependency

#### Phase 3: Incremental Combinations (when all ONLY-* pass)

If all individual dependencies pass but the full JAR fails, the issue is a combination of dependencies or transitive deps.

```bash
mvn clean package -Pwaf-test-combo-1 -DskipTests
mvn clean package -Pwaf-test-combo-2 -DskipTests
mvn clean package -Pwaf-test-combo-3 -DskipTests
mvn clean package -Pwaf-test-combo-4 -DskipTests
mvn clean package -Pwaf-test-combo-5 -DskipTests
```

**Phase 3 - Incremental combinations:**

| Profile | JAR Name | What's Included |
|---------|----------|-----------------|
| `waf-test-combo-1` | `openapivalidator-COMBO1-SNAKE-NET.jar` | SnakeYAML + Networknt |
| `waf-test-combo-2` | `openapivalidator-COMBO2-SNAKE-NET-CORE.jar` | + Swagger Core |
| `waf-test-combo-3` | `openapivalidator-COMBO3-SNAKE-NET-CORE-PARSER.jar` | + Swagger Parser |
| `waf-test-combo-4` | `openapivalidator-COMBO4-ALL.jar` | + Atlassian (all deps) |
| `waf-test-combo-5` | `openapivalidator-COMBO5-ATLASSIAN-PARSER.jar` | Atlassian + Parser only |

**Testing Procedure:**
```
COMBO1 (Snake+Net)           → PASS / FAIL
COMBO2 (Snake+Net+Core)      → PASS / FAIL
COMBO3 (Snake+Net+Core+Parser) → PASS / FAIL
COMBO4 (All deps)            → PASS / FAIL
COMBO5 (Atlassian+Parser)    → PASS / FAIL

First FAIL = that combination brings problematic transitive dependencies
```

#### Phase 4: Deep Analysis (when Atlassian + Parser fails)

Since COMBO4 and COMBO5 fail but individual deps pass, test with aggressive filtering:

```bash
mvn clean package -Pwaf-test-phase4-no-commons -DskipTests
mvn clean package -Pwaf-test-phase4-no-parser-core -DskipTests
mvn clean package -Pwaf-test-phase4-filtered -DskipTests
mvn clean package -Pwaf-test-phase4-atlassian-only-classes -DskipTests
mvn clean package -Pwaf-test-phase4-parser-only-classes -DskipTests
```

**Phase 4 - Deep filtering:**

| Profile | JAR Name | What's Different |
|---------|----------|------------------|
| `waf-test-phase4-no-commons` | `openapivalidator-PHASE4-NO-COMMONS.jar` | Atlassian+Parser, exclude commons-lang |
| `waf-test-phase4-no-parser-core` | `openapivalidator-PHASE4-NO-PARSER-CORE.jar` | Exclude swagger-parser-core submodule |
| `waf-test-phase4-filtered` | `openapivalidator-PHASE4-FILTERED.jar` | Aggressive class filtering (Remote*, Url*, etc.) |
| `waf-test-phase4-atlassian-only-classes` | `openapivalidator-PHASE4-ATLASSIAN-CLASSES.jar` | Only Atlassian .class files |
| `waf-test-phase4-parser-only-classes` | `openapivalidator-PHASE4-PARSER-CLASSES.jar` | Only Parser .class files |

**Testing Procedure:**
```
PHASE4-NO-COMMONS          → PASS / FAIL  (commons-lang triggers WAF?)
PHASE4-NO-PARSER-CORE      → PASS / FAIL  (parser-core triggers WAF?)
PHASE4-FILTERED            → PASS / FAIL  (URL/Remote classes trigger WAF?)
PHASE4-ATLASSIAN-CLASSES   → PASS / FAIL  (Atlassian resources trigger WAF?)
PHASE4-PARSER-CLASSES      → PASS / FAIL  (Parser resources trigger WAF?)

PASS = that exclusion fixed it, we found the trigger
```

#### Quick Test Script

```bash
#!/bin/bash
# Build all WAF test JARs - Phase 2 (ONLY-* profiles)
cd apigee-validator

profiles=(
    "waf-test-only-snakeyaml"
    "waf-test-only-networknt"
    "waf-test-only-swagger-parser"
    "waf-test-only-swagger-core"
    "waf-test-only-atlassian"
)

for profile in "${profiles[@]}"; do
    echo "Building $profile..."
    mvn clean package -P$profile -DskipTests -q
done

echo ""
echo "Generated JARs:"
ls -lh target/*.jar
```
