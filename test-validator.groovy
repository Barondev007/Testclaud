#!/usr/bin/env groovy

/**
 * Standalone test script for Axway Swagger Validator
 *
 * Prerequisites:
 *   - Groovy installed (https://groovy-lang.org/)
 *   - swagger-request-validator JARs in classpath
 *
 * Usage:
 *   Option 1: Using Grape (auto-downloads dependencies)
 *     groovy test-validator.groovy
 *
 *   Option 2: With manual classpath
 *     groovy -cp "libs/*" test-validator.groovy
 *
 *   Option 3: Using Maven
 *     mvn compile exec:java -Dexec.mainClass="com.example.axway.ValidatorTestRunner"
 */

@Grab('com.atlassian.oai:swagger-request-validator-core:2.30.0')
@Grab('org.slf4j:slf4j-simple:1.7.36')

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.model.Request
import com.atlassian.oai.validator.model.SimpleRequest
import com.atlassian.oai.validator.model.SimpleResponse
import com.atlassian.oai.validator.report.LevelResolver
import com.atlassian.oai.validator.report.LevelResolverFactory
import com.atlassian.oai.validator.report.ValidationReport

// ============================================================================
// MOCK CLASSES
// ============================================================================

class MockMessage {
    Map<String, Object> attrs = [:]
    def get(String key) { attrs.get(key) }
    def put(String key, Object value) { attrs.put(key, value) }
}

class Trace {
    static void error(String msg) { println "[ERROR] $msg" }
    static void warn(String msg) { println "[WARN] $msg" }
    static void info(String msg) { println "[INFO] $msg" }
    static void debug(String msg) { println "[DEBUG] $msg" }
}

// ============================================================================
// VALIDATOR (simplified version for testing)
// ============================================================================

class TestValidator {
    static Map<String, OpenApiInteractionValidator> cache = [:]

    static OpenApiInteractionValidator getValidator(String spec, String level) {
        def key = spec.hashCode() + "|" + level
        if (!cache.containsKey(key)) {
            def builder = OpenApiInteractionValidator.createForInlineApiSpecification(spec)
            switch (level) {
                case "light":
                    builder.withLevelResolver(createLightResolver())
                    break
                case "lenient":
                    builder.withLevelResolver(LevelResolverFactory.withAdditionalPropertiesIgnored())
                    break
            }
            cache[key] = builder.build()
        }
        return cache[key]
    }

    static LevelResolver createLightResolver() {
        LevelResolver.create()
            .withLevel("validation.request.body.schema.additionalProperties", ValidationReport.Level.INFO)
            .withLevel("validation.response.body.schema.additionalProperties", ValidationReport.Level.INFO)
            .withLevel("validation.request.body.schema.required", ValidationReport.Level.INFO)
            .withLevel("validation.response.body.schema.required", ValidationReport.Level.INFO)
            .withLevel("validation.request.body.schema.type", ValidationReport.Level.INFO)
            .build()
    }

    static ValidationReport validateRequest(OpenApiInteractionValidator validator, String method, String path,
                                            String body, String contentType) {
        def builder = new SimpleRequest.Builder(method, path)
        if (body) {
            builder.withBody(body).withContentType(contentType ?: "application/json")
        }
        return validator.validateRequest(builder.build())
    }

    static ValidationReport validateResponse(OpenApiInteractionValidator validator, String method, String path,
                                             int status, String body, String contentType) {
        def response = new SimpleResponse.Builder(status)
        if (body) {
            response.withBody(body).withContentType(contentType ?: "application/json")
        }
        return validator.validateResponse(path, Request.Method.valueOf(method), response.build())
    }
}

// ============================================================================
// SAMPLE OPENAPI SPEC
// ============================================================================

def spec = '''
openapi: "3.0.3"
info:
  title: Test API
  version: "1.0.0"
paths:
  /users:
    post:
      summary: Create user
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
      responses:
        '201':
          description: Created
          content:
            application/json:
              schema:
                type: object
                properties:
                  id:
                    type: integer
                  name:
                    type: string
    get:
      summary: List users
      responses:
        '200':
          description: OK
'''

// ============================================================================
// TEST FUNCTIONS
// ============================================================================

def runTest(String name, Closure test) {
    println "\n" + "=" * 60
    println "TEST: $name"
    println "=" * 60
    try {
        def result = test()
        println "RESULT: ${result ? 'PASSED' : 'FAILED'}"
        return result
    } catch (Exception e) {
        println "ERROR: ${e.message}"
        e.printStackTrace()
        return false
    }
}

def printReport(ValidationReport report) {
    println "Has errors: ${report.hasErrors()}"
    println "Messages:"
    report.getMessages().each { msg ->
        println "  [${msg.level}] ${msg.key}: ${msg.message}"
    }
}

// ============================================================================
// RUN TESTS
// ============================================================================

println """
╔══════════════════════════════════════════════════════════════╗
║     SWAGGER VALIDATOR TEST SUITE (Outside Axway)             ║
╚══════════════════════════════════════════════════════════════╝
"""

def results = []

// Test 1: Valid request
results << runTest("Valid request (should PASS)") {
    def validator = TestValidator.getValidator(spec, "strict")
    def report = TestValidator.validateRequest(validator, "POST", "/users",
        '{"name": "John", "email": "john@example.com"}', "application/json")
    printReport(report)
    return !report.hasErrors()
}

// Test 2: Missing required field (strict)
results << runTest("Missing required field - STRICT (should FAIL)") {
    def validator = TestValidator.getValidator(spec, "strict")
    def report = TestValidator.validateRequest(validator, "POST", "/users",
        '{"name": "John"}', "application/json")  // Missing email
    printReport(report)
    return report.hasErrors()  // Expected to have errors
}

// Test 3: Additional properties (strict)
results << runTest("Additional properties - STRICT (should FAIL)") {
    def validator = TestValidator.getValidator(spec, "strict")
    def report = TestValidator.validateRequest(validator, "POST", "/users",
        '{"name": "John", "email": "john@example.com", "extra": "field"}', "application/json")
    printReport(report)
    return report.hasErrors()  // Expected to have errors
}

// Test 4: Additional properties (lenient)
results << runTest("Additional properties - LENIENT (should PASS)") {
    def validator = TestValidator.getValidator(spec, "lenient")
    def report = TestValidator.validateRequest(validator, "POST", "/users",
        '{"name": "John", "email": "john@example.com", "extra": "allowed"}', "application/json")
    printReport(report)
    return !report.hasErrors()
}

// Test 5: Light mode - errors collected but not blocking
results << runTest("Missing required - LIGHT mode (errors collected, not blocking)") {
    def validator = TestValidator.getValidator(spec, "light")
    def report = TestValidator.validateRequest(validator, "POST", "/users",
        '{"name": "John"}', "application/json")
    printReport(report)
    // In light mode, hasErrors() returns false but messages are INFO level
    println "Light mode: hasErrors=${report.hasErrors()}, messageCount=${report.messages.size()}"
    return !report.hasErrors() && report.messages.size() > 0
}

// Test 6: Response validation
results << runTest("Response validation (should PASS)") {
    def validator = TestValidator.getValidator(spec, "strict")
    def report = TestValidator.validateResponse(validator, "POST", "/users", 201,
        '{"id": 1, "name": "John"}', "application/json")
    printReport(report)
    return !report.hasErrors()
}

// Test 7: Response with extra properties (lenient)
results << runTest("Response with extra properties - LENIENT (should PASS)") {
    def validator = TestValidator.getValidator(spec, "lenient")
    def report = TestValidator.validateResponse(validator, "POST", "/users", 201,
        '{"id": 1, "name": "John", "createdAt": "2024-01-01"}', "application/json")
    printReport(report)
    return !report.hasErrors()
}

// ============================================================================
// SUMMARY
// ============================================================================

println """
╔══════════════════════════════════════════════════════════════╗
║                      TEST SUMMARY                            ║
╠══════════════════════════════════════════════════════════════╣
"""
def passed = results.count { it }
def total = results.size()
results.eachWithIndex { result, i ->
    println "║  Test ${i + 1}: ${result ? '✓ PASS' : '✗ FAIL'}".padRight(62) + "║"
}
println """╠══════════════════════════════════════════════════════════════╣
║  Total: ${passed}/${total} tests passed".padRight(52) + "║
╚══════════════════════════════════════════════════════════════╝
"""

System.exit(passed == total ? 0 : 1)
