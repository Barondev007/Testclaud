package be.bnppf.openapi.validator.cli;

import be.bnppf.openapi.validator.OpenAPIValidator;
import be.bnppf.openapi.validator.ValidationLevel;
import be.bnppf.openapi.validator.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Command-line interface for testing OpenAPI specifications and validating requests/responses.
 * Uses the BNPPF OpenAPI Validator library (be.bnppf.openapi.validator).
 *
 * This CLI uses standard Java types only - no Axway dependencies required.
 *
 * Usage:
 *   java -jar openapi-validator-cli.jar [command] [options]
 *
 * Commands:
 *   check    - Check if an OpenAPI specification loads correctly
 *   validate - Validate a request or response against a specification
 *   help     - Show help information
 */
public class ValidatorCLI {

    private static final String VERSION = "1.0.0";
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_BLUE = "\u001B[34m";
    private static final String ANSI_BOLD = "\u001B[1m";

    private static boolean useColors = true;
    private static boolean verbose = false;

    public static void main(String[] args) {
        // Check if running on Windows and disable colors if needed
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            String term = System.getenv("TERM");
            if (term == null || term.isEmpty()) {
                useColors = false;
            }
        }

        if (args.length == 0) {
            printHelp();
            System.exit(0);
        }

        String command = args[0].toLowerCase();

        // Check for global flags
        List<String> argList = new ArrayList<>(Arrays.asList(args));
        if (argList.contains("--no-color")) {
            useColors = false;
            argList.remove("--no-color");
        }
        if (argList.contains("-v") || argList.contains("--verbose")) {
            verbose = true;
            argList.remove("-v");
            argList.remove("--verbose");
        }

        args = argList.toArray(new String[0]);

        try {
            switch (command) {
                case "check":
                    handleCheck(args);
                    break;
                case "validate":
                    handleValidate(args);
                    break;
                case "help":
                case "--help":
                case "-h":
                    printHelp();
                    break;
                case "version":
                case "--version":
                    printVersion();
                    break;
                default:
                    printError("Unknown command: " + command);
                    printHelp();
                    System.exit(1);
            }
        } catch (Exception e) {
            printError("Error: " + e.getMessage());
            if (verbose) {
                e.printStackTrace();
            }
            System.exit(1);
        }
    }

    /**
     * Handle the 'check' command - validates that an OpenAPI spec can be loaded
     */
    private static void handleCheck(String[] args) throws IOException {
        if (args.length < 2) {
            printError("Missing specification file path");
            System.out.println("Usage: check <spec-file> [--level STRICT|LENIENT|LIGHT]");
            System.exit(1);
        }

        String specFile = args[1];
        String levelStr = "LENIENT";

        // Parse options
        for (int i = 2; i < args.length; i++) {
            if ("--level".equals(args[i]) && i + 1 < args.length) {
                levelStr = args[++i].toUpperCase();
            }
        }

        ValidationLevel level = ValidationLevel.fromString(levelStr);

        printHeader("OpenAPI Specification Check");
        printInfo("File: " + specFile);
        printInfo("Validation Level: " + level);
        System.out.println();

        // Load and validate spec
        String specContent = loadSpecFile(specFile);

        printInfo("Loading specification...");

        long startTime = System.currentTimeMillis();

        try {
            // Use OpenAPIValidator from axway-validator
            OpenAPIValidator validator = OpenAPIValidator.getInstance(specContent, level);
            long elapsed = System.currentTimeMillis() - startTime;

            printSuccess("Specification loaded successfully!");
            printInfo("Load time: " + elapsed + "ms");
            printInfo("Validation Level: " + validator.getValidationLevel());

            // Print spec info
            printSpecInfo(specContent);

        } catch (Exception e) {
            printError("Failed to load specification!");
            printError("Error: " + e.getMessage());

            if (verbose) {
                System.out.println("\nStack trace:");
                e.printStackTrace();
            }

            System.exit(1);
        }
    }

    /**
     * Handle the 'validate' command - validates a request or response
     */
    private static void handleValidate(String[] args) throws IOException {
        if (args.length < 2) {
            printError("Missing arguments");
            printValidateUsage();
            System.exit(1);
        }

        String specFile = null;
        String requestFile = null;
        String responseFile = null;
        String method = null;
        String path = null;
        String body = null;
        String contentType = "application/json";
        String levelStr = "LENIENT";
        int statusCode = 200;
        Map<String, List<String>> headers = new LinkedHashMap<>();
        Map<String, List<String>> queryParams = new LinkedHashMap<>();

        // Parse arguments
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--spec":
                case "-s":
                    specFile = args[++i];
                    break;
                case "--request":
                case "-r":
                    requestFile = args[++i];
                    break;
                case "--response":
                    responseFile = args[++i];
                    break;
                case "--method":
                case "-m":
                    method = args[++i].toUpperCase();
                    break;
                case "--path":
                case "-p":
                    path = args[++i];
                    break;
                case "--body":
                case "-b":
                    body = args[++i];
                    break;
                case "--body-file":
                    body = new String(Files.readAllBytes(Paths.get(args[++i])), StandardCharsets.UTF_8);
                    break;
                case "--content-type":
                case "-c":
                    contentType = args[++i];
                    break;
                case "--header":
                case "-H":
                    String[] headerParts = args[++i].split(":", 2);
                    if (headerParts.length == 2) {
                        addToMultiMap(headers, headerParts[0].trim(), headerParts[1].trim());
                    }
                    break;
                case "--query":
                case "-q":
                    String[] queryParts = args[++i].split("=", 2);
                    if (queryParts.length == 2) {
                        addToMultiMap(queryParams, queryParts[0].trim(), queryParts[1].trim());
                    }
                    break;
                case "--status":
                    statusCode = Integer.parseInt(args[++i]);
                    break;
                case "--level":
                case "-l":
                    levelStr = args[++i].toUpperCase();
                    break;
                default:
                    // If no flag, assume it's the spec file (first positional arg)
                    if (specFile == null && !args[i].startsWith("-")) {
                        specFile = args[i];
                    }
            }
        }

        // Validate required arguments
        if (specFile == null) {
            printError("Missing specification file");
            printValidateUsage();
            System.exit(1);
        }

        // Load request from file if provided
        if (requestFile != null) {
            Map<String, Object> requestData = loadRequestFile(requestFile);
            if (method == null) method = (String) requestData.get("method");
            if (path == null) path = (String) requestData.get("path");
            if (body == null) body = (String) requestData.get("body");
            if (requestData.containsKey("headers")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> reqHeaders = (Map<String, Object>) requestData.get("headers");
                for (Map.Entry<String, Object> entry : reqHeaders.entrySet()) {
                    addToMultiMap(headers, entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
            if (requestData.containsKey("query")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> reqQuery = (Map<String, Object>) requestData.get("query");
                for (Map.Entry<String, Object> entry : reqQuery.entrySet()) {
                    addToMultiMap(queryParams, entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
            if (requestData.containsKey("contentType")) {
                contentType = (String) requestData.get("contentType");
            }
        }

        // Check required fields
        if (method == null || path == null) {
            printError("Missing required fields: --method and --path are required");
            printValidateUsage();
            System.exit(1);
        }

        ValidationLevel level = ValidationLevel.fromString(levelStr);

        printHeader("OpenAPI Request Validation");
        printInfo("Specification: " + specFile);
        printInfo("Validation Level: " + level);
        printInfo("Method: " + method);
        printInfo("Path: " + path);
        if (!queryParams.isEmpty()) {
            printInfo("Query Parameters: " + queryParams);
        }
        if (!headers.isEmpty()) {
            printInfo("Headers: " + headers);
        }
        if (body != null) {
            printInfo("Content-Type: " + contentType);
            if (verbose) {
                printInfo("Body: " + (body.length() > 200 ? body.substring(0, 200) + "..." : body));
            }
        }
        System.out.println();

        // Load spec and create validator
        String specContent = loadSpecFile(specFile);

        printInfo("Loading specification...");
        OpenAPIValidator validator = OpenAPIValidator.getInstance(specContent, level);

        if (verbose) {
            validator.setDebugEnabled(true);
        }

        printSuccess("Specification loaded");
        System.out.println();

        // Add Content-Type header
        addToMultiMap(headers, "Content-Type", contentType);

        // Validate request using OpenAPIValidator with standard Java types
        printInfo("Validating request...");
        ValidationResult result = validator.validateRequest(body, method, path, queryParams, headers);

        printValidationResult(result, "Request");

        // Validate response if provided
        if (responseFile != null) {
            System.out.println();
            printInfo("Validating response...");

            String responseBody = new String(Files.readAllBytes(Paths.get(responseFile)), StandardCharsets.UTF_8);

            // Build response headers
            Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
            addToMultiMap(responseHeaders, "Content-Type", contentType);

            ValidationResult responseResult = validator.validateResponse(
                responseBody, method, path, statusCode, responseHeaders);

            printValidationResult(responseResult, "Response");
        }
    }

    /**
     * Helper method to add a value to a multi-value map
     */
    private static void addToMultiMap(Map<String, List<String>> map, String key, String value) {
        map.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }

    /**
     * Load specification file (JSON or YAML)
     */
    private static String loadSpecFile(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("File not found: " + filePath);
        }

        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

        // If YAML, convert to JSON for consistent processing
        if (filePath.endsWith(".yaml") || filePath.endsWith(".yml")) {
            ObjectMapper yamlReader = new ObjectMapper(new YAMLFactory());
            ObjectMapper jsonWriter = new ObjectMapper();
            Object obj = yamlReader.readValue(content, Object.class);
            content = jsonWriter.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        }

        return content;
    }

    /**
     * Load request from JSON/YAML file
     */
    private static Map<String, Object> loadRequestFile(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("Request file not found: " + filePath);
        }

        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        ObjectMapper mapper;

        if (filePath.endsWith(".yaml") || filePath.endsWith(".yml")) {
            mapper = new ObjectMapper(new YAMLFactory());
        } else {
            mapper = new ObjectMapper();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = mapper.readValue(content, Map.class);

        // Convert body to string if it's an object
        if (data.containsKey("body") && !(data.get("body") instanceof String)) {
            data.put("body", new ObjectMapper().writeValueAsString(data.get("body")));
        }

        return data;
    }

    /**
     * Print validation result from OpenAPIValidator
     */
    private static void printValidationResult(ValidationResult result, String type) {
        List<String> errors = result.getErrors();
        List<String> warnings = result.getWarnings();
        List<String> infos = result.getInfos();

        if (!result.isBlocked() && errors.isEmpty() && warnings.isEmpty()) {
            printSuccess(type + " validation PASSED");
        } else if (!result.isBlocked() && errors.isEmpty()) {
            printWarning(type + " validation passed with warnings");
        } else {
            printError(type + " validation FAILED");
        }

        System.out.println();
        printInfo("Summary: " + errors.size() + " errors, " + warnings.size() + " warnings, " + infos.size() + " info");
        printInfo("Blocked: " + result.isBlocked());
        System.out.println();

        if (!errors.isEmpty()) {
            System.out.println(colorize("Errors:", ANSI_RED));
            for (String error : errors) {
                System.out.println("  " + colorize("[ERROR]", ANSI_RED) + " " + error);
            }
            System.out.println();
        }

        if (!warnings.isEmpty()) {
            System.out.println(colorize("Warnings:", ANSI_YELLOW));
            for (String warning : warnings) {
                System.out.println("  " + colorize("[WARN]", ANSI_YELLOW) + " " + warning);
            }
            System.out.println();
        }

        if (verbose && !infos.isEmpty()) {
            System.out.println(colorize("Info:", ANSI_BLUE));
            for (String info : infos) {
                System.out.println("  " + colorize("[INFO]", ANSI_BLUE) + " " + info);
            }
            System.out.println();
        }

        // Print debug info if available
        if (verbose && result.getDebugInfo() != null && !result.getDebugInfo().isEmpty()) {
            System.out.println(colorize("Debug Info:", ANSI_BLUE));
            System.out.println(result.getDebugInfo());
        }

        // Exit with error if validation failed
        if (result.isBlocked()) {
            System.exit(1);
        }
    }

    /**
     * Print specification info
     */
    private static void printSpecInfo(String specContent) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(specContent);

            System.out.println();
            printInfo("Specification Details:");

            // OpenAPI version
            if (root.has("openapi")) {
                System.out.println("  OpenAPI Version: " + root.get("openapi").asText());
            } else if (root.has("swagger")) {
                System.out.println("  Swagger Version: " + root.get("swagger").asText());
            }

            // Info section
            if (root.has("info")) {
                JsonNode info = root.get("info");
                if (info.has("title")) {
                    System.out.println("  Title: " + info.get("title").asText());
                }
                if (info.has("version")) {
                    System.out.println("  API Version: " + info.get("version").asText());
                }
                if (info.has("description")) {
                    String desc = info.get("description").asText();
                    if (desc.length() > 100) {
                        desc = desc.substring(0, 100) + "...";
                    }
                    System.out.println("  Description: " + desc);
                }
            }

            // Count paths
            if (root.has("paths")) {
                int pathCount = root.get("paths").size();
                System.out.println("  Paths: " + pathCount);

                if (verbose) {
                    System.out.println("\n  Available paths:");
                    Iterator<String> paths = root.get("paths").fieldNames();
                    while (paths.hasNext()) {
                        String path = paths.next();
                        JsonNode pathNode = root.get("paths").get(path);
                        List<String> methods = new ArrayList<>();
                        Iterator<String> methodNames = pathNode.fieldNames();
                        while (methodNames.hasNext()) {
                            String m = methodNames.next();
                            if (!m.startsWith("x-") && !m.equals("parameters")) {
                                methods.add(m.toUpperCase());
                            }
                        }
                        System.out.println("    " + path + " [" + String.join(", ", methods) + "]");
                    }
                }
            }

        } catch (Exception e) {
            // Ignore errors when printing spec info
            if (verbose) {
                printWarning("Could not parse spec details: " + e.getMessage());
            }
        }
    }

    // ==================== Output Helpers ====================

    /**
     * Java 8 compatible string repeat method
     */
    private static String repeatString(String str, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(str);
        }
        return sb.toString();
    }

    private static String colorize(String text, String color) {
        if (useColors) {
            return color + text + ANSI_RESET;
        }
        return text;
    }

    private static void printHeader(String text) {
        System.out.println();
        System.out.println(colorize(repeatString("=", 60), ANSI_BLUE));
        System.out.println(colorize(ANSI_BOLD + "  " + text, ANSI_BLUE));
        System.out.println(colorize(repeatString("=", 60), ANSI_BLUE));
        System.out.println();
    }

    private static void printSuccess(String text) {
        System.out.println(colorize("✓ " + text, ANSI_GREEN));
    }

    private static void printError(String text) {
        System.out.println(colorize("✗ " + text, ANSI_RED));
    }

    private static void printWarning(String text) {
        System.out.println(colorize("⚠ " + text, ANSI_YELLOW));
    }

    private static void printInfo(String text) {
        System.out.println(colorize("ℹ " + text, ANSI_BLUE));
    }

    private static void printVersion() {
        System.out.println("OpenAPI Validator CLI v" + VERSION);
        System.out.println("Using BNPPF OpenAPI Validator Library");
    }

    private static void printValidateUsage() {
        System.out.println();
        System.out.println("Usage: validate --spec <file> --method <METHOD> --path <path> [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --spec, -s <file>       OpenAPI specification file (JSON or YAML)");
        System.out.println("  --method, -m <method>   HTTP method (GET, POST, PUT, DELETE, etc.)");
        System.out.println("  --path, -p <path>       Request path (e.g., /users/123)");
        System.out.println("  --body, -b <json>       Request body as JSON string");
        System.out.println("  --body-file <file>      Request body from file");
        System.out.println("  --request, -r <file>    Load request from JSON/YAML file");
        System.out.println("  --response <file>       Response body file to validate");
        System.out.println("  --status <code>         Response status code (default: 200)");
        System.out.println("  --header, -H <h:v>      Add header (can be repeated)");
        System.out.println("  --query, -q <k=v>       Add query parameter (can be repeated)");
        System.out.println("  --content-type, -c      Content-Type (default: application/json)");
        System.out.println("  --level, -l <level>     Validation level: STRICT, LENIENT, LIGHT");
        System.out.println();
    }

    private static void printHelp() {
        System.out.println();
        System.out.println(colorize(ANSI_BOLD + "OpenAPI Validator CLI", ANSI_BLUE) + " v" + VERSION);
        System.out.println("Using BNPPF OpenAPI Validator Library (be.bnppf.openapi.validator)");
        System.out.println();
        System.out.println("A tool for testing OpenAPI specifications and validating API requests.");
        System.out.println();
        System.out.println(colorize("USAGE:", ANSI_BOLD));
        System.out.println("  openapi-validator <command> [options]");
        System.out.println();
        System.out.println(colorize("COMMANDS:", ANSI_BOLD));
        System.out.println("  check <spec-file>     Check if a specification loads correctly");
        System.out.println("  validate              Validate a request against a specification");
        System.out.println("  help                  Show this help message");
        System.out.println("  version               Show version information");
        System.out.println();
        System.out.println(colorize("GLOBAL OPTIONS:", ANSI_BOLD));
        System.out.println("  --no-color            Disable colored output");
        System.out.println("  -v, --verbose         Enable verbose output and debug info");
        System.out.println();
        System.out.println(colorize("EXAMPLES:", ANSI_BOLD));
        System.out.println();
        System.out.println("  # Check if a specification is valid");
        System.out.println("  openapi-validator check api-spec.yaml");
        System.out.println();
        System.out.println("  # Check with verbose output");
        System.out.println("  openapi-validator check api-spec.yaml -v");
        System.out.println();
        System.out.println("  # Validate a GET request");
        System.out.println("  openapi-validator validate --spec api-spec.yaml --method GET --path /users/123");
        System.out.println();
        System.out.println("  # Validate a POST request with body");
        System.out.println("  openapi-validator validate --spec api-spec.yaml \\");
        System.out.println("    --method POST --path /users \\");
        System.out.println("    --body '{\"name\":\"John\",\"email\":\"john@example.com\"}'");
        System.out.println();
        System.out.println("  # Validate using a request file");
        System.out.println("  openapi-validator validate --spec api-spec.yaml --request request.json");
        System.out.println();
        System.out.println("  # Use different validation levels");
        System.out.println("  openapi-validator validate --spec api-spec.yaml --method GET --path /users --level STRICT");
        System.out.println();
        System.out.println(colorize("VALIDATION LEVELS:", ANSI_BOLD));
        System.out.println("  STRICT   - Enforce all specification rules strictly");
        System.out.println("  LENIENT  - Allow additional properties (default)");
        System.out.println("  LIGHT    - Minimal validation, most issues reported as info");
        System.out.println();
        System.out.println(colorize("REQUEST FILE FORMAT:", ANSI_BOLD));
        System.out.println("  {");
        System.out.println("    \"method\": \"POST\",");
        System.out.println("    \"path\": \"/users\",");
        System.out.println("    \"contentType\": \"application/json\",");
        System.out.println("    \"headers\": { \"Authorization\": \"Bearer token\" },");
        System.out.println("    \"query\": { \"page\": \"1\" },");
        System.out.println("    \"body\": { \"name\": \"John\" }");
        System.out.println("  }");
        System.out.println();
    }
}
