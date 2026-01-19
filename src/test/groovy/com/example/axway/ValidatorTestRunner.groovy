package com.example.axway

import groovy.lang.GroovyShell
import groovy.lang.Binding

/**
 * Test runner for Axway Swagger Validator scripts.
 *
 * Usage:
 *   groovy ValidatorTestRunner.groovy
 *
 * Or from Java/Maven:
 *   mvn test -Dtest=ValidatorTestRunner
 */
class ValidatorTestRunner {

    private GroovyShell shell
    private String scriptContent
    private MockMessage message

    ValidatorTestRunner(String scriptPath) {
        // Read the script file
        def scriptFile = new File(scriptPath)
        if (!scriptFile.exists()) {
            throw new FileNotFoundException("Script not found: ${scriptPath}")
        }
        this.scriptContent = scriptFile.text

        // Initialize mock message
        this.message = new MockMessage()

        // Create binding with mock objects
        def binding = new Binding()
        binding.setVariable("msg", message)
        binding.setVariable("Message", MockMessage)
        binding.setVariable("Trace", Trace)

        this.shell = new GroovyShell(binding)
    }

    /**
     * Set an attribute on the mock message
     */
    ValidatorTestRunner setAttribute(String key, Object value) {
        message.put(key, value)
        return this
    }

    /**
     * Get an attribute from the mock message
     */
    Object getAttribute(String key) {
        return message.get(key)
    }

    /**
     * Get all attributes
     */
    Map<String, Object> getAllAttributes() {
        return message.getAll()
    }

    /**
     * Run the validation script
     */
    boolean runValidation() {
        Trace.clearLogs()

        // Inject the mock Message class into the script
        def modifiedScript = """
            // Mock classes injected for testing
            class Message {
                private static msg
                static void setInstance(m) { msg = m }
                static def get(String key) { return msg.get(key) }
                static void put(String key, Object value) { msg.put(key, value) }
            }
            Message.setInstance(msg)

            ${scriptContent}
        """

        try {
            def result = shell.evaluate(modifiedScript)
            return result as boolean
        } catch (Exception e) {
            println "Script execution error: ${e.message}"
            e.printStackTrace()
            return false
        }
    }

    /**
     * Clear the message and logs for a new test
     */
    ValidatorTestRunner reset() {
        message.clear()
        Trace.clearLogs()
        return this
    }

    /**
     * Print test results
     */
    void printResults() {
        println "\n========== VALIDATION RESULTS =========="
        println "validation.type:         ${getAttribute('openapi.validation.type')}"
        println "validation.failed:       ${getAttribute('openapi.validation.failed')}"
        println "validation.errors.count: ${getAttribute('openapi.validation.errors.count')}"
        println "validation.error:        ${getAttribute('openapi.validation.error')}"
        println "validation.errors.all:   ${getAttribute('openapi.validation.errors.all')}"
        println "========================================\n"
    }

    // ========================================================================
    // MAIN - Run tests
    // ========================================================================

    static void main(String[] args) {
        def scriptPath = args.length > 0 ? args[0] :
            "src/main/resources/axway/scripts/SwaggerValidatorFilterV2.groovy"

        println "Loading script: ${scriptPath}"

        def runner = new ValidatorTestRunner(scriptPath)

        // Sample OpenAPI spec for testing
        def sampleSpec = '''
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
                  minLength: 1
                email:
                  type: string
                  format: email
                age:
                  type: integer
                  minimum: 0
      responses:
        '201':
          description: User created
          content:
            application/json:
              schema:
                type: object
                properties:
                  id:
                    type: integer
                  name:
                    type: string
                  email:
                    type: string
        '400':
          description: Bad request
    get:
      summary: List users
      parameters:
        - name: limit
          in: query
          schema:
            type: integer
      responses:
        '200':
          description: Users list
          content:
            application/json:
              schema:
                type: array
                items:
                  type: object
  /users/{id}:
    get:
      summary: Get user by ID
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: integer
      responses:
        '200':
          description: User found
'''

        // ====================================================================
        // TEST 1: Valid request (should pass)
        // ====================================================================
        println "\n>>> TEST 1: Valid request (should pass)"
        runner.reset()
            .setAttribute("specfile", sampleSpec)
            .setAttribute("http.request.verb", "POST")
            .setAttribute("http.request.path", "/users")
            .setAttribute("content.body", '{"name": "John Doe", "email": "john@example.com", "age": 30}')
            .setAttribute("http.header", ["Content-Type": "application/json"])
            .setAttribute("openapi.validation.level", "strict")

        def result1 = runner.runValidation()
        println "Result: ${result1 ? 'PASSED' : 'FAILED'}"
        runner.printResults()

        // ====================================================================
        // TEST 2: Invalid request - missing required field (should fail in strict)
        // ====================================================================
        println "\n>>> TEST 2: Missing required field (should fail in strict)"
        runner.reset()
            .setAttribute("specfile", sampleSpec)
            .setAttribute("http.request.verb", "POST")
            .setAttribute("http.request.path", "/users")
            .setAttribute("content.body", '{"name": "John Doe"}')  // Missing email
            .setAttribute("http.header", ["Content-Type": "application/json"])
            .setAttribute("openapi.validation.level", "strict")

        def result2 = runner.runValidation()
        println "Result: ${result2 ? 'PASSED' : 'FAILED'}"
        runner.printResults()

        // ====================================================================
        // TEST 3: Request with additional properties (strict - should fail)
        // ====================================================================
        println "\n>>> TEST 3: Additional properties (strict mode - should fail)"
        runner.reset()
            .setAttribute("specfile", sampleSpec)
            .setAttribute("http.request.verb", "POST")
            .setAttribute("http.request.path", "/users")
            .setAttribute("content.body", '{"name": "John", "email": "john@example.com", "extraField": "not allowed"}')
            .setAttribute("http.header", ["Content-Type": "application/json"])
            .setAttribute("openapi.validation.level", "strict")

        def result3 = runner.runValidation()
        println "Result: ${result3 ? 'PASSED' : 'FAILED'}"
        runner.printResults()

        // ====================================================================
        // TEST 4: Request with additional properties (lenient - should pass)
        // ====================================================================
        println "\n>>> TEST 4: Additional properties (lenient mode - should pass)"
        runner.reset()
            .setAttribute("specfile", sampleSpec)
            .setAttribute("http.request.verb", "POST")
            .setAttribute("http.request.path", "/users")
            .setAttribute("content.body", '{"name": "John", "email": "john@example.com", "extraField": "allowed in lenient"}')
            .setAttribute("http.header", ["Content-Type": "application/json"])
            .setAttribute("openapi.validation.level", "lenient")

        def result4 = runner.runValidation()
        println "Result: ${result4 ? 'PASSED' : 'FAILED'}"
        runner.printResults()

        // ====================================================================
        // TEST 5: Light mode - errors collected but flow not blocked
        // ====================================================================
        println "\n>>> TEST 5: Light mode (errors stored, flow not blocked)"
        runner.reset()
            .setAttribute("specfile", sampleSpec)
            .setAttribute("http.request.verb", "POST")
            .setAttribute("http.request.path", "/users")
            .setAttribute("content.body", '{"name": "John"}')  // Missing required email
            .setAttribute("http.header", ["Content-Type": "application/json"])
            .setAttribute("openapi.validation.level", "light")

        def result5 = runner.runValidation()
        println "Result: ${result5 ? 'PASSED (flow not blocked)' : 'FAILED'}"
        runner.printResults()

        // ====================================================================
        // TEST 6: Response validation
        // ====================================================================
        println "\n>>> TEST 6: Response validation (should pass)"
        runner.reset()
            .setAttribute("specfile", sampleSpec)
            .setAttribute("http.request.verb", "POST")
            .setAttribute("http.request.path", "/users")
            .setAttribute("http.response.status", 201)  // This triggers response validation
            .setAttribute("content.body", '{"id": 1, "name": "John", "email": "john@example.com"}')
            .setAttribute("http.header", ["Content-Type": "application/json"])
            .setAttribute("openapi.validation.level", "strict")

        def result6 = runner.runValidation()
        println "Result: ${result6 ? 'PASSED' : 'FAILED'}"
        println "Validation type: ${runner.getAttribute('openapi.validation.type')}"
        runner.printResults()

        // ====================================================================
        // TEST 7: Response validation with extra properties (lenient)
        // ====================================================================
        println "\n>>> TEST 7: Response with extra properties (lenient - should pass)"
        runner.reset()
            .setAttribute("specfile", sampleSpec)
            .setAttribute("http.request.verb", "POST")
            .setAttribute("http.request.path", "/users")
            .setAttribute("http.response.status", 201)
            .setAttribute("content.body", '{"id": 1, "name": "John", "email": "john@example.com", "createdAt": "2024-01-01"}')
            .setAttribute("http.header", ["Content-Type": "application/json"])
            .setAttribute("openapi.validation.level", "lenient")

        def result7 = runner.runValidation()
        println "Result: ${result7 ? 'PASSED' : 'FAILED'}"
        runner.printResults()

        // ====================================================================
        // SUMMARY
        // ====================================================================
        println "\n========== TEST SUMMARY =========="
        println "Test 1 (Valid request):              ${result1 ? 'PASS' : 'FAIL'}"
        println "Test 2 (Missing required):           ${!result2 ? 'PASS (expected fail)' : 'FAIL'}"
        println "Test 3 (Extra props strict):         ${!result3 ? 'PASS (expected fail)' : 'FAIL'}"
        println "Test 4 (Extra props lenient):        ${result4 ? 'PASS' : 'FAIL'}"
        println "Test 5 (Light mode):                 ${result5 ? 'PASS' : 'FAIL'}"
        println "Test 6 (Response validation):        ${result6 ? 'PASS' : 'FAIL'}"
        println "Test 7 (Response extra lenient):     ${result7 ? 'PASS' : 'FAIL'}"
        println "===================================="
    }
}
