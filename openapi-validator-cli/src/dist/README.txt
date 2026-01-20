================================================================================
                        OpenAPI Validator CLI
                            Version 1.0.0
================================================================================

A command-line tool for testing OpenAPI/Swagger specifications and validating
API requests before deploying to production.

REQUIREMENTS
------------
- Java 8 or higher (Java 11+ recommended)
- No installation required - just extract and run

QUICK START
-----------

1. Extract the archive to a folder of your choice

2. Run the validator:

   Linux/macOS:
   $ ./bin/openapi-validator help

   Windows:
   > bin\openapi-validator.bat help


COMMANDS
--------

1. CHECK - Verify your OpenAPI specification is valid

   Linux/macOS:
   $ ./bin/openapi-validator check examples/sample-spec.yaml

   Windows:
   > bin\openapi-validator.bat check examples\sample-spec.yaml

   With verbose output (shows all paths):
   $ ./bin/openapi-validator check examples/sample-spec.yaml -v


2. VALIDATE - Test a request against your specification

   Simple GET request:
   $ ./bin/openapi-validator validate \
       --spec examples/sample-spec.yaml \
       --method GET \
       --path /pets

   POST request with body:
   $ ./bin/openapi-validator validate \
       --spec examples/sample-spec.yaml \
       --method POST \
       --path /pets \
       --body '{"name": "Fluffy", "status": "available"}'

   Using a request file:
   $ ./bin/openapi-validator validate \
       --spec examples/sample-spec.yaml \
       --request examples/valid-request.json


VALIDATION LEVELS
-----------------

Choose how strict the validation should be:

  STRICT  - Enforces all specification rules
            Extra properties in body = ERROR
            Use for: New APIs, high security requirements

  LENIENT - Allows additional properties (DEFAULT)
            Extra properties in body = OK
            Use for: Most production APIs

  LIGHT   - Minimal validation, warnings only
            Most issues = WARNING
            Use for: Legacy APIs, migration phases

Example:
  $ ./bin/openapi-validator validate \
      --spec api.yaml \
      --method POST \
      --path /users \
      --level STRICT


REQUEST FILE FORMAT
-------------------

You can define requests in a JSON or YAML file:

{
  "method": "POST",
  "path": "/pets",
  "contentType": "application/json",
  "headers": {
    "Authorization": "Bearer token123"
  },
  "query": {
    "page": "1",
    "limit": "10"
  },
  "body": {
    "name": "Fluffy",
    "status": "available"
  }
}


OPTIONS REFERENCE
-----------------

Global options:
  --no-color       Disable colored output
  -v, --verbose    Show detailed output

Check command:
  check <spec-file>          The specification to check
  --level <STRICT|LENIENT|LIGHT>   Validation level

Validate command:
  --spec, -s <file>          OpenAPI specification file
  --method, -m <METHOD>      HTTP method (GET, POST, PUT, DELETE, etc.)
  --path, -p <path>          Request path (e.g., /users/123)
  --body, -b <json>          Request body as JSON string
  --body-file <file>         Request body from file
  --request, -r <file>       Load full request from file
  --response <file>          Response body to validate
  --status <code>            Response status code (default: 200)
  --header, -H <name:value>  Add header (can repeat)
  --query, -q <key=value>    Add query param (can repeat)
  --content-type, -c <type>  Content-Type (default: application/json)
  --level, -l <level>        Validation level


EXAMPLES
--------

See the 'examples' folder for sample files:

  sample-spec.yaml    - A sample OpenAPI 3.0 specification
  valid-request.json  - An example of a valid request
  invalid-request.json - An example of an invalid request (for testing)

Try validating the examples:

  # This should PASS
  $ ./bin/openapi-validator validate \
      --spec examples/sample-spec.yaml \
      --request examples/valid-request.json

  # This should FAIL (missing required 'name' field)
  $ ./bin/openapi-validator validate \
      --spec examples/sample-spec.yaml \
      --request examples/invalid-request.json


EXIT CODES
----------

  0 - Success (specification valid, request passed validation)
  1 - Error (specification invalid, validation failed, or other error)


TROUBLESHOOTING
---------------

1. "Java is not installed"
   - Install Java 8 or higher
   - Ensure 'java' is in your PATH

2. "Cannot find openapi-validator-cli.jar"
   - Run the script from within the extracted folder
   - Do not move the scripts without the lib folder

3. "Specification failed to load"
   - Check your YAML/JSON syntax
   - Validate your spec at https://editor.swagger.io

4. Colors not showing on Windows
   - Use Windows Terminal or run with --no-color
   - Set TERM=xterm in environment


SUPPORT
-------

For issues or questions, contact the API Management team.


================================================================================
