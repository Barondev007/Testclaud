# OpenAPI Validator for Axway API Gateway

## Documentation Guide

**Version:** 1.0.0
**Package:** `be.bnppf.openapi.validator`
**Last Updated:** January 2026

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [What Problem Does It Solve?](#2-what-problem-does-it-solve)
3. [Architecture Overview](#3-architecture-overview)
4. [How It Works](#4-how-it-works)
5. [Validation Levels](#5-validation-levels)
6. [Installation Guide](#6-installation-guide)
7. [Configuration](#7-configuration)
8. [Usage Examples](#8-usage-examples)
9. [Troubleshooting](#9-troubleshooting)
10. [Glossary](#10-glossary)

---

## 1. Introduction

### What is the OpenAPI Validator?

The **OpenAPI Validator** is a tool that checks if API requests follow the rules defined in an API specification (OpenAPI/Swagger). Think of it as a **security guard** that verifies incoming requests match what the API expects before allowing them through.

### Who is this for?

| Audience | What you'll learn |
|----------|-------------------|
| **Business Users** | Why this tool matters and what it protects |
| **API Managers** | How to configure validation levels |
| **Developers** | Technical implementation details |
| **Operations** | Installation and troubleshooting |

---

## 2. What Problem Does It Solve?

### The Challenge

When APIs receive requests, they need to verify that:
- The request goes to a valid endpoint (URL path)
- The HTTP method is allowed (GET, POST, PUT, DELETE, etc.)
- Required parameters are present
- Data formats are correct

Without validation, invalid requests can cause errors, security issues, or unexpected behavior.

### The Solution

```
┌─────────────────────────────────────────────────────────────────────┐
│                        WITHOUT VALIDATOR                            │
│                                                                     │
│   Client ──────────────────────────────────────────────► API        │
│            Invalid requests pass through                            │
│            causing errors and security risks                        │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                         WITH VALIDATOR                              │
│                                                                     │
│   Client ────► OpenAPI Validator ────► API                          │
│                      │                                              │
│                      ▼                                              │
│               ┌─────────────┐                                       │
│               │ Valid? Yes  │────────────────────► Request proceeds │
│               │ Valid? No   │────► 400 Bad Request (blocked)        │
│               └─────────────┘                                       │
└─────────────────────────────────────────────────────────────────────┘
```

### Benefits

| Benefit | Description |
|---------|-------------|
| **Security** | Blocks malformed or malicious requests |
| **Consistency** | Ensures all requests follow API contract |
| **Early Detection** | Catches errors before they reach backend |
| **Documentation** | API spec serves as single source of truth |

---

## 3. Architecture Overview

### High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│                           AXWAY API GATEWAY                              │
│                                                                          │
│  ┌─────────┐    ┌──────────────────────────┐    ┌─────────────────────┐  │
│  │         │    │   Groovy Script Filter   │    │                     │  │
│  │ Client  │───►│  (SwaggerValidatorV3)    │───►│   Backend API       │  │
│  │ Request │    │           │              │    │                     │  │
│  │         │    │           ▼              │    │                     │  │
│  └─────────┘    │  ┌─────────────────┐     │    └─────────────────────┘  │
│                 │  │ BnppfOpenAPIValidator│     │                             │
│                 │  │   (Java JAR)    │     │                             │
│                 │  └─────────────────┘     │                             │
│                 └──────────────────────────┘                             │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

### Component Details

```
┌────────────────────────────────────────────────────────────────────────┐
│                        OPENAPI VALIDATOR JAR                           │
│                     (be.bnppf.openapi.validator)                       │
│                                                                        │
│   ┌────────────────────┐  ┌──────────────────┐  ┌──────────────────┐   │
│   │  BnppfOpenAPIValidator  │  │ ValidationLevel  │  │ ValidationResult │   │
│   │  ────────────────  │  │ ───────────────  │  │ ───────────────  │   │
│   │  • Parses spec     │  │ • LIGHT          │  │ • isValid()      │   │
│   │  • Validates req   │  │ • LENIENT        │  │ • isBlocked()    │   │
│   │  • Caches results  │  │ • STRICT         │  │ • getErrors()    │   │
│   └────────────────────┘  └──────────────────┘  └──────────────────┘   │
│                                                                        │
│   ┌────────────────────────────────────────────────────────────────┐   │
│   │                         Utils                                  │   │
│   │  • Logging and tracing                                         │   │
│   │  • Header extraction                                           │   │
│   └────────────────────────────────────────────────────────────────┘   │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 4. How It Works

### Request Flow Diagram

```
                                    REQUEST VALIDATION FLOW

    ┌─────────┐                                                    ┌─────────┐
    │         │                                                    │         │
    │ CLIENT  │                                                    │ BACKEND │
    │         │                                                    │   API   │
    └────┬────┘                                                    └────▲────┘
         │                                                              │
         │ 1. HTTP Request                                              │
         │    (GET /api/users?id=123)                                   │
         ▼                                                              │
    ┌─────────────────────────────────────────────────────────────┐    │
    │                    AXWAY API GATEWAY                         │    │
    │                                                              │    │
    │  ┌───────────────────────────────────────────────────────┐  │    │
    │  │              GROOVY SCRIPT FILTER                      │  │    │
    │  │                                                        │  │    │
    │  │   2. Extract from request:                             │  │    │
    │  │      • HTTP Method (GET)                               │  │    │
    │  │      • Path (/api/users)                               │  │    │
    │  │      • Query Parameters (?id=123)                      │  │    │
    │  │      • Headers                                         │  │    │
    │  │      • Body (if present)                               │  │    │
    │  │                                                        │  │    │
    │  │   3. Load OpenAPI Specification                        │  │    │
    │  │      (from message attribute)                          │  │    │
    │  │                          │                             │  │    │
    │  └──────────────────────────┼─────────────────────────────┘  │    │
    │                             ▼                                │    │
    │  ┌───────────────────────────────────────────────────────┐  │    │
    │  │              OPENAPI VALIDATOR (Java)                  │  │    │
    │  │                                                        │  │    │
    │  │   4. Parse OpenAPI spec                                │  │    │
    │  │                                                        │  │    │
    │  │   5. Build validation request:                         │  │    │
    │  │      SimpleRequest.Builder                             │  │    │
    │  │        .withMethod("GET")                              │  │    │
    │  │        .withPath("/api/users")                         │  │    │
    │  │        .withQueryParam("id", "123")                    │  │    │
    │  │                                                        │  │    │
    │  │   6. Validate against spec                             │  │    │
    │  │                                                        │  │    │
    │  │   7. Return ValidationResult                           │  │    │
    │  │                          │                             │  │    │
    │  └──────────────────────────┼─────────────────────────────┘  │    │
    │                             ▼                                │    │
    │  ┌───────────────────────────────────────────────────────┐  │    │
    │  │              DECISION POINT                            │  │    │
    │  │                                                        │  │    │
    │  │   ┌─────────────────┐       ┌─────────────────────┐   │  │    │
    │  │   │   VALID = YES   │       │    VALID = NO       │   │  │    │
    │  │   │                 │       │                     │   │  │    │
    │  │   │ 8a. Continue to │       │ 8b. Return error    │   │  │    │
    │  │   │     backend ────┼───────┼──────────────────────┼──┼──┘    │
    │  │   │                 │       │     400 Bad Request │   │       │
    │  │   └─────────────────┘       └──────────┬──────────┘   │       │
    │  │                                        │              │       │
    │  └────────────────────────────────────────┼──────────────┘       │
    │                                           │                      │
    └───────────────────────────────────────────┼──────────────────────┘
                                                │
                                                ▼
                                         ┌─────────────┐
                                         │   CLIENT    │
                                         │  (Error)    │
                                         └─────────────┘
```

### Validation Process Steps

| Step | Component | Action |
|------|-----------|--------|
| 1 | Client | Sends HTTP request to API Gateway |
| 2 | Groovy Script | Extracts request details (method, path, headers, body) |
| 3 | Groovy Script | Retrieves OpenAPI specification |
| 4 | Validator | Parses and caches the OpenAPI specification |
| 5 | Validator | Builds internal request representation |
| 6 | Validator | Compares request against specification rules |
| 7 | Validator | Returns validation result with any errors |
| 8 | Groovy Script | Routes valid requests forward, blocks invalid ones |

---

## 5. Validation Levels

The validator supports three levels of strictness. Choose based on your needs:

### Comparison Chart

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     VALIDATION LEVELS COMPARISON                        │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│   STRICTNESS  ◄────────────────────────────────────────────► FLEXIBILITY│
│                                                                         │
│   ┌─────────────┐         ┌─────────────┐         ┌─────────────┐      │
│   │   STRICT    │         │   LENIENT   │         │    LIGHT    │      │
│   │             │         │             │         │             │      │
│   │  Most rigid │         │  Balanced   │         │ Most flexible│      │
│   │  All errors │         │ Warnings OK │         │ Errors only │      │
│   │  reported   │         │ Extra props │         │ Block severe│      │
│   │             │         │   allowed   │         │             │      │
│   └─────────────┘         └─────────────┘         └─────────────┘      │
│                                                                         │
│   Use for:               Use for:                Use for:              │
│   • New APIs             • Most APIs             • Legacy APIs          │
│   • High security        • Standard use          • Migration            │
│   • Strict contracts     • Production            • Lenient validation   │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

### Detailed Level Descriptions

#### LIGHT Level
```
┌────────────────────────────────────────────────────────────┐
│  LIGHT - Maximum Flexibility                               │
├────────────────────────────────────────────────────────────┤
│                                                            │
│  ✓ Validates basic structure                               │
│  ✓ Checks HTTP method exists                               │
│  ✓ Verifies path is defined                                │
│                                                            │
│  ✗ Ignores extra properties in body                        │
│  ✗ Ignores missing optional fields                         │
│  ✗ Ignores type mismatches (warnings only)                 │
│                                                            │
│  Best for: Legacy APIs, migration phases, flexible specs   │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

#### LENIENT Level (Recommended)
```
┌────────────────────────────────────────────────────────────┐
│  LENIENT - Balanced Approach (RECOMMENDED)                 │
├────────────────────────────────────────────────────────────┤
│                                                            │
│  ✓ All LIGHT validations                                   │
│  ✓ Validates required fields present                       │
│  ✓ Checks basic type compatibility                         │
│  ✓ Allows additional properties in objects                 │
│                                                            │
│  ✗ Does not block on extra properties                      │
│  ✗ Warnings for minor issues                               │
│                                                            │
│  Best for: Production APIs, standard validation            │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

#### STRICT Level
```
┌────────────────────────────────────────────────────────────┐
│  STRICT - Maximum Enforcement                              │
├────────────────────────────────────────────────────────────┤
│                                                            │
│  ✓ All LENIENT validations                                 │
│  ✓ Enforces exact type matching                            │
│  ✓ Blocks extra/unknown properties                         │
│  ✓ Validates all constraints (min, max, pattern)           │
│  ✓ Every spec violation is an error                        │
│                                                            │
│  Best for: New APIs, high security, strict contracts       │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

### Level Selection Decision Tree

```
                            START
                              │
                              ▼
                   ┌──────────────────────┐
                   │ Is this a new API?   │
                   └──────────┬───────────┘
                              │
               ┌──────────────┴──────────────┐
               │                             │
              YES                            NO
               │                             │
               ▼                             ▼
        ┌─────────────┐          ┌────────────────────────┐
        │   STRICT    │          │ Are you migrating from │
        │             │          │ an old system?         │
        └─────────────┘          └───────────┬────────────┘
                                             │
                              ┌──────────────┴──────────────┐
                              │                             │
                             YES                            NO
                              │                             │
                              ▼                             ▼
                       ┌─────────────┐              ┌─────────────┐
                       │    LIGHT    │              │   LENIENT   │
                       │             │              │ (Recommended)│
                       └─────────────┘              └─────────────┘
```

---

## 6. Installation Guide

### Prerequisites

| Requirement | Version | Purpose |
|-------------|---------|---------|
| Axway API Gateway | 7.7+ | Runtime environment |
| Java | 8+ | JAR compatibility |
| Maven | 3.6+ | Building the JAR |

### Step-by-Step Installation

```
┌────────────────────────────────────────────────────────────────────────┐
│                        INSTALLATION STEPS                              │
│                                                                        │
│   ┌─────┐    ┌─────┐    ┌─────┐    ┌─────┐    ┌─────┐                 │
│   │  1  │───►│  2  │───►│  3  │───►│  4  │───►│  5  │                 │
│   │Build│    │Copy │    │Copy │    │Config│   │Test │                 │
│   │ JAR │    │ JAR │    │Groovy│   │Policy│   │     │                 │
│   └─────┘    └─────┘    └─────┘    └─────┘    └─────┘                 │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```

#### Step 1: Build the JAR

```bash
cd axway-validator
mvn clean package
```

This creates: `target/openapi-validator-1.0.0.jar`

#### Step 2: Deploy JAR to Axway

Copy the JAR to Axway's extension library folder:

```bash
cp target/openapi-validator-1.0.0.jar $AXWAY_HOME/ext/lib/
```

#### Step 3: Deploy Groovy Script

Copy the Groovy script to your scripts location:

```bash
cp SwaggerValidatorFilterV3.groovy $AXWAY_HOME/scripts/
```

#### Step 4: Configure Policy in Policy Studio

```
┌────────────────────────────────────────────────────────────────────────┐
│                    POLICY STUDIO CONFIGURATION                         │
│                                                                        │
│   1. Open Policy Studio                                                │
│                                                                        │
│   2. Create/Edit your API Policy                                       │
│                                                                        │
│   3. Add "Scripting Filter" to the policy:                            │
│      ┌──────────────────────────────────────────────────────────┐     │
│      │  Filter Type: Scripting Language                          │     │
│      │  Language: Groovy                                         │     │
│      │  Script: SwaggerValidatorFilterV3.groovy                  │     │
│      └──────────────────────────────────────────────────────────┘     │
│                                                                        │
│   4. Set message attributes (before the filter):                       │
│      • openapi.spec = "your OpenAPI specification as string"           │
│      • openapi.validation.level = "LENIENT"                            │
│                                                                        │
│   5. Deploy the configuration                                          │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```

#### Step 5: Test the Installation

Send a test request and verify validation is working:

```bash
# Valid request - should pass
curl -X GET "https://your-gateway/api/users/123"

# Invalid request - should return 400
curl -X GET "https://your-gateway/api/invalid-endpoint"
```

---

## 7. Configuration

### Message Attributes

Configure the validator using Axway message attributes:

| Attribute | Required | Description | Example |
|-----------|----------|-------------|---------|
| `openapi.spec` | Yes | OpenAPI specification as string | `{"openapi": "3.0.0", ...}` |
| `openapi.validation.level` | No | Validation strictness | `LENIENT` (default) |
| `openapi.cache.enabled` | No | Enable spec caching | `true` (default) |

### Configuration Flow

```
┌────────────────────────────────────────────────────────────────────────┐
│                    CONFIGURATION FLOW                                  │
│                                                                        │
│   ┌─────────────────┐                                                  │
│   │  Set Attribute  │    Before Validator Filter                       │
│   │  Filter         │                                                  │
│   │                 │    openapi.spec = "${api.specification}"         │
│   │                 │    openapi.validation.level = "LENIENT"          │
│   └────────┬────────┘                                                  │
│            │                                                           │
│            ▼                                                           │
│   ┌─────────────────┐                                                  │
│   │  Validator      │    Reads attributes and validates                │
│   │  Groovy Script  │                                                  │
│   └────────┬────────┘                                                  │
│            │                                                           │
│            ▼                                                           │
│   ┌─────────────────┐                                                  │
│   │  Continue or    │    Based on validation result                    │
│   │  Return Error   │                                                  │
│   └─────────────────┘                                                  │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```

### Caching Behavior

The validator caches parsed specifications for performance:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        CACHING MECHANISM                               │
│                                                                        │
│   First Request:                                                       │
│   ┌────────┐    ┌──────────────┐    ┌─────────┐    ┌──────────────┐  │
│   │ Spec   │───►│ Parse Spec   │───►│ Cache   │───►│ Validate     │  │
│   │ String │    │ (expensive)  │    │ Store   │    │ Request      │  │
│   └────────┘    └──────────────┘    └─────────┘    └──────────────┘  │
│                                                                        │
│   Subsequent Requests (same spec):                                     │
│   ┌────────┐    ┌──────────────┐    ┌──────────────┐                  │
│   │ Spec   │───►│ Cache Hit!   │───►│ Validate     │                  │
│   │ String │    │ (fast)       │    │ Request      │                  │
│   └────────┘    └──────────────┘    └──────────────┘                  │
│                                                                        │
│   Cache Key: MD5 hash of (spec + validation level)                     │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 8. Usage Examples

### Example 1: Basic GET Request Validation

**OpenAPI Specification:**
```yaml
openapi: 3.0.0
paths:
  /users/{userId}:
    get:
      parameters:
        - name: userId
          in: path
          required: true
          schema:
            type: integer
```

**Valid Request:**
```
GET /users/123
→ PASS (userId is integer)
```

**Invalid Request:**
```
GET /users/abc
→ FAIL (userId should be integer, got string)
```

### Example 2: POST Request with Body Validation

**OpenAPI Specification:**
```yaml
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
```

**Validation Results by Level:**

```
┌────────────────────────────────────────────────────────────────────────┐
│ Request Body: {"name": "John", "email": "john@example.com", "age": 30} │
├────────────────────────────────────────────────────────────────────────┤
│                                                                        │
│   LIGHT:    ✅ PASS - Basic structure valid                            │
│   LENIENT:  ✅ PASS - Extra "age" property allowed                     │
│   STRICT:   ❌ FAIL - "age" property not in schema                     │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────┐
│ Request Body: {"name": "John"}                                         │
├────────────────────────────────────────────────────────────────────────┤
│                                                                        │
│   LIGHT:    ✅ PASS - Warnings ignored                                 │
│   LENIENT:  ❌ FAIL - Missing required "email"                         │
│   STRICT:   ❌ FAIL - Missing required "email"                         │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```

### Example 3: Groovy Script Integration

```groovy
import be.bnppf.openapi.validator.BnppfOpenAPIValidator
import be.bnppf.openapi.validator.ValidationLevel
import be.bnppf.openapi.validator.ValidationResult

// Get configuration from message attributes
def spec = msg.get("openapi.spec")
def levelStr = msg.get("openapi.validation.level") ?: "LENIENT"
def level = ValidationLevel.fromString(levelStr)

// Get or create validator (cached)
def validator = BnppfOpenAPIValidator.getInstance(spec, level, true)

// Get request details
def method = http.getVerb()
def path = http.getRequestURI()

// Validate the request
ValidationResult result = validator.isValidRequest(
    method,
    path,
    msg.getHeaderSet(),      // Headers
    msg.getQueryParams(),    // Query parameters
    bodyAsString             // Request body
)

// Handle result
if (result.isBlocked()) {
    // Return 400 Bad Request
    msg.set("http.response.status", 400)
    msg.set("http.response.body", result.getErrorsAsJson())
    return false
}

return true  // Continue to backend
```

---

## 9. Troubleshooting

### Common Issues and Solutions

```
┌────────────────────────────────────────────────────────────────────────┐
│                    TROUBLESHOOTING GUIDE                               │
├────────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  ISSUE: "ClassNotFoundException: BnppfOpenAPIValidator"                     │
│  ┌──────────────────────────────────────────────────────────────────┐ │
│  │ CAUSE: JAR not in classpath                                      │ │
│  │ FIX:   Copy openapi-validator-1.0.0.jar to $AXWAY_HOME/ext/lib/  │ │
│  │        Restart Axway Gateway                                      │ │
│  └──────────────────────────────────────────────────────────────────┘ │
│                                                                        │
│  ISSUE: "OpenAPI specification is null or empty"                       │
│  ┌──────────────────────────────────────────────────────────────────┐ │
│  │ CAUSE: Message attribute not set                                  │ │
│  │ FIX:   Ensure "openapi.spec" attribute is set before filter      │ │
│  │        Check attribute name spelling                              │ │
│  └──────────────────────────────────────────────────────────────────┘ │
│                                                                        │
│  ISSUE: Valid requests are being blocked                               │
│  ┌──────────────────────────────────────────────────────────────────┐ │
│  │ CAUSE: Validation level too strict                                │ │
│  │ FIX:   Change to LENIENT or LIGHT level                          │ │
│  │        Review OpenAPI spec for accuracy                           │ │
│  └──────────────────────────────────────────────────────────────────┘ │
│                                                                        │
│  ISSUE: Path not found in specification                                │
│  ┌──────────────────────────────────────────────────────────────────┐ │
│  │ CAUSE: Gateway path differs from spec path                        │ │
│  │ FIX:   The validator automatically tries to map paths            │ │
│  │        Ensure spec paths match expected request paths             │ │
│  └──────────────────────────────────────────────────────────────────┘ │
│                                                                        │
│  ISSUE: Slow performance on first request                              │
│  ┌──────────────────────────────────────────────────────────────────┐ │
│  │ CAUSE: Spec parsing overhead (normal)                             │ │
│  │ FIX:   Enable caching (default). Subsequent requests are fast    │ │
│  └──────────────────────────────────────────────────────────────────┘ │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```

### Debug Logging

Enable debug logging by modifying the Groovy script:

```groovy
// Enable verbose logging
Utils.traceMessage("Validating: " + method + " " + path, TraceLevel.DEBUG)
```

### Checking Validation Errors

```groovy
ValidationResult result = validator.isValidRequest(...)

if (!result.isValid()) {
    // Log all validation errors
    for (error in result.getErrors()) {
        Utils.traceMessage("Validation error: " + error, TraceLevel.ERROR)
    }
}
```

---

## 10. Glossary

| Term | Definition |
|------|------------|
| **API** | Application Programming Interface - a way for software to communicate |
| **OpenAPI** | A specification format for describing REST APIs (formerly Swagger) |
| **Swagger** | Original name for OpenAPI specification |
| **Validation** | Checking if data meets specified rules and formats |
| **Axway API Gateway** | Software that manages and secures API traffic |
| **JAR** | Java Archive - a packaged Java application |
| **Groovy** | A programming language that runs on Java (used in Axway) |
| **HTTP Method** | The type of request (GET, POST, PUT, DELETE, etc.) |
| **Path** | The URL endpoint being accessed (e.g., /users/123) |
| **Query Parameter** | Data passed in URL after ? (e.g., ?name=John) |
| **Request Body** | Data sent with POST/PUT requests |
| **Headers** | Metadata sent with HTTP requests |
| **Cache** | Temporary storage for faster access |
| **MD5** | A hashing algorithm used for cache keys |

---

## Appendix A: Quick Reference Card

```
┌────────────────────────────────────────────────────────────────────────┐
│                     QUICK REFERENCE CARD                               │
├────────────────────────────────────────────────────────────────────────┤
│                                                                        │
│  VALIDATION LEVELS:                                                    │
│    LIGHT   → Most flexible, minimal validation                         │
│    LENIENT → Recommended for most use cases                            │
│    STRICT  → Enforces all specification rules                          │
│                                                                        │
│  KEY MESSAGE ATTRIBUTES:                                               │
│    openapi.spec             → OpenAPI specification (required)         │
│    openapi.validation.level → LIGHT | LENIENT | STRICT                 │
│                                                                        │
│  JAVA CLASSES:                                                         │
│    be.bnppf.openapi.validator.BnppfOpenAPIValidator                         │
│    be.bnppf.openapi.validator.ValidationLevel                          │
│    be.bnppf.openapi.validator.ValidationResult                         │
│                                                                        │
│  KEY METHODS:                                                          │
│    BnppfOpenAPIValidator.getInstance(spec, level, cache)                    │
│    validator.isValidRequest(method, path, headers, params, body)       │
│    result.isValid() / result.isBlocked() / result.getErrors()          │
│                                                                        │
│  FILES:                                                                │
│    JAR: openapi-validator-1.0.0.jar → $AXWAY_HOME/ext/lib/             │
│    Script: SwaggerValidatorFilterV3.groovy → Policy Script Filter      │
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
```

---

**Document End**

*For questions or support, contact the API Management team.*
