package be.bnppf.openapi.validator.cli;

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

    // ANSI Color codes
    private static final String ANSI_RESET = "\u001B[0m";
    // Foreground colors
    private static final String ANSI_BLACK = "\u001B[30m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_BLUE = "\u001B[34m";
    private static final String ANSI_MAGENTA = "\u001B[35m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_WHITE = "\u001B[37m";

    // Bright/Bold colors
    private static final String ANSI_BRIGHT_RED = "\u001B[91m";
    private static final String ANSI_BRIGHT_GREEN = "\u001B[92m";
    private static final String ANSI_BRIGHT_YELLOW = "\u001B[93m";
    private static final String ANSI_BRIGHT_BLUE = "\u001B[94m";
    private static final String ANSI_BRIGHT_MAGENTA = "\u001B[95m";
    private static final String ANSI_BRIGHT_CYAN = "\u001B[96m";

    // Background colors
    private static final String ANSI_BG_RED = "\u001B[41m";
    private static final String ANSI_BG_GREEN = "\u001B[42m";
    private static final String ANSI_BG_YELLOW = "\u001B[43m";
    private static final String ANSI_BG_BLUE = "\u001B[44m";

    // Box drawing characters
    private static final String BOX_TL = "┌";  // top-left
    private static final String BOX_TR = "┐";  // top-right
    private static final String BOX_BL = "└";  // bottom-left
    private static final String BOX_BR = "┘";  // bottom-right
    private static final String BOX_H = "─";   // horizontal
    private static final String BOX_V = "│";   // vertical
    private static final String BOX_LT = "├";  // left-tee
    private static final String BOX_RT = "┤";  // right-tee

    // Symbols
    private static final String SYM_CHECK = "✓";
    private static final String SYM_CROSS = "✗";
    private static final String SYM_WARN = "⚠";
    private static final String SYM_INFO = "ℹ";
    private static final String SYM_ARROW = "→";
    private static final String SYM_BULLET = "●";
    private static final String SYM_CIRCLE = "○";

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
        printDetail("File", specFile);
        printDetail("Validation Level", level.toString());
        System.out.println();

        // Load and validate spec
        String specContent = loadSpecFile(specFile);

        System.out.println(colorize("  " + SYM_CIRCLE + " Loading specification...", ANSI_WHITE));

        long startTime = System.currentTimeMillis();

        try {
            // Use OpenApiValidator from axway-validator
            OpenApiValidator validator = OpenApiValidator.getInstance(specContent, level);
            long elapsed = System.currentTimeMillis() - startTime;

            printSuccess("Specification loaded successfully!");
            printDetail("Load time", elapsed + "ms");

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

        // Load method/path from response file if not already set
        if (responseFile != null && (method == null || path == null)) {
            try {
                Map<String, Object> responseData = loadResponseFile(responseFile);
                if (responseData != null) {
                    if (method == null && responseData.containsKey("method")) {
                        method = ((String) responseData.get("method")).toUpperCase();
                    }
                    if (path == null && responseData.containsKey("path")) {
                        path = (String) responseData.get("path");
                    }
                }
            } catch (IOException e) {
                // Will be handled later when validating response
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
        printDetail("Specification", specFile);
        printDetail("Validation Level", level.toString());
        printDetail("Method", colorizeMethod(method));
        printDetail("Path", path);
        if (!queryParams.isEmpty()) {
            printDetail("Query Params", formatParams(queryParams));
        }
        if (!headers.isEmpty()) {
            printDetail("Headers", String.valueOf(headers.size()) + " header(s)");
        }
        if (body != null) {
            printDetail("Content-Type", contentType);
            if (verbose) {
                printDetail("Body", body.length() > 100 ? body.substring(0, 100) + "..." : body);
            }
        }
        System.out.println();

        // Load spec and create validator
        String specContent = loadSpecFile(specFile);

        System.out.println(colorize("  " + SYM_CIRCLE + " Loading specification...", ANSI_WHITE));
        OpenApiValidator validator = OpenApiValidator.getInstance(specContent, level);

        if (verbose) {
            validator.setDebugEnabled(true);
        }

        printSuccess("Specification loaded");
        System.out.println();

        // Add Content-Type header
        addToMultiMap(headers, "Content-Type", contentType);

        // Validate request using OpenApiValidator with standard Java types
        System.out.println(colorize("  " + SYM_CIRCLE + " Validating request...", ANSI_WHITE));
        ValidationResult result = validator.validateRequest(body, method, path, queryParams, headers);

        printValidationResult(result, "Request");

        // Validate response if provided
        if (responseFile != null) {
            System.out.println();
            printSubHeader("Response Validation");

            String responseBody;
            String responseContentType = contentType;
            int responseStatusCode = statusCode;
            Map<String, List<String>> responseHeaders = new LinkedHashMap<>();

            // Try to load as structured file (like request file)
            Map<String, Object> responseData = loadResponseFile(responseFile);
            if (responseData != null) {
                // Structured response file with body, status, headers
                if (responseData.containsKey("body")) {
                    Object bodyObj = responseData.get("body");
                    if (bodyObj instanceof String) {
                        responseBody = (String) bodyObj;
                    } else {
                        responseBody = new ObjectMapper().writeValueAsString(bodyObj);
                    }
                } else {
                    responseBody = "";
                }
                if (responseData.containsKey("status")) {
                    responseStatusCode = ((Number) responseData.get("status")).intValue();
                }
                if (responseData.containsKey("statusCode")) {
                    responseStatusCode = ((Number) responseData.get("statusCode")).intValue();
                }
                if (responseData.containsKey("contentType")) {
                    responseContentType = (String) responseData.get("contentType");
                }
                if (responseData.containsKey("headers")) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> respHeaders = (Map<String, Object>) responseData.get("headers");
                    for (Map.Entry<String, Object> entry : respHeaders.entrySet()) {
                        addToMultiMap(responseHeaders, entry.getKey(), String.valueOf(entry.getValue()));
                    }
                }
            } else {
                // Plain body file - read as-is
                responseBody = new String(Files.readAllBytes(Paths.get(responseFile)), StandardCharsets.UTF_8);
            }

            // Add Content-Type header if not already set
            if (!responseHeaders.containsKey("Content-Type")) {
                addToMultiMap(responseHeaders, "Content-Type", responseContentType);
            }

            printDetail("Response File", responseFile);
            printDetail("Status Code", String.valueOf(responseStatusCode));
            printDetail("Content-Type", responseContentType);
            System.out.println();

            System.out.println(colorize("  " + SYM_CIRCLE + " Validating response...", ANSI_WHITE));

            ValidationResult responseResult = validator.validateResponse(
                responseBody, method, path, responseStatusCode, responseHeaders);

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
     * Load response from JSON/YAML file.
     * Supports two formats:
     * 1. Structured: { "status": 200, "body": {...}, "headers": {...} }
     * 2. Plain body: just the response body content
     *
     * Returns null if the file is plain body (not structured).
     */
    private static Map<String, Object> loadResponseFile(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("Response file not found: " + filePath);
        }

        String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        ObjectMapper mapper;

        if (filePath.endsWith(".yaml") || filePath.endsWith(".yml")) {
            mapper = new ObjectMapper(new YAMLFactory());
        } else {
            mapper = new ObjectMapper();
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = mapper.readValue(content, Map.class);

            // Check if this looks like a structured response file
            // (has status, statusCode, body, headers, method, or path keys)
            if (data.containsKey("status") || data.containsKey("statusCode") ||
                data.containsKey("body") || data.containsKey("headers") ||
                data.containsKey("method") || data.containsKey("path")) {
                return data;
            }

            // Not a structured file, return null to indicate plain body
            return null;

        } catch (Exception e) {
            // If parsing fails, treat as plain body file
            return null;
        }
    }

    /**
     * Print validation result from OpenApiValidator
     */
    private static void printValidationResult(ValidationResult result, String type) {
        List<String> errors = result.getErrors();
        List<String> warnings = result.getWarnings();
        List<String> infos = result.getInfos();

        // Print errors section
        if (!errors.isEmpty()) {
            printSubHeader("Errors");
            for (int i = 0; i < errors.size(); i++) {
                System.out.println(colorize("  " + (i + 1) + ". ", ANSI_RED) +
                        colorize(SYM_CROSS + " ", ANSI_BRIGHT_RED) +
                        colorize(errors.get(i), ANSI_RED));
            }
        }

        // Print warnings section
        if (!warnings.isEmpty()) {
            printSubHeader("Warnings");
            for (int i = 0; i < warnings.size(); i++) {
                System.out.println(colorize("  " + (i + 1) + ". ", ANSI_YELLOW) +
                        colorize(SYM_WARN + " ", ANSI_BRIGHT_YELLOW) +
                        colorize(warnings.get(i), ANSI_YELLOW));
            }
        }

        // Print info section (only in verbose mode)
        if (verbose && !infos.isEmpty()) {
            printSubHeader("Info");
            for (int i = 0; i < infos.size(); i++) {
                System.out.println(colorize("  " + (i + 1) + ". ", ANSI_CYAN) +
                        colorize(SYM_INFO + " ", ANSI_BRIGHT_CYAN) +
                        colorize(infos.get(i), ANSI_CYAN));
            }
        }

        // Print debug info if available
        if (verbose && result.getDebugInfo() != null && !result.getDebugInfo().isEmpty()) {
            printSubHeader("Debug Info");
            System.out.println(colorize(result.getDebugInfo(), ANSI_WHITE));
        }

        // Print the summary bar
        printSummaryBar(errors.size(), warnings.size(), infos.size(), result.isBlocked());

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

            printSubHeader("Specification Details");

            // OpenAPI version
            if (root.has("openapi")) {
                printDetail("OpenAPI Version", root.get("openapi").asText());
            } else if (root.has("swagger")) {
                printDetail("Swagger Version", root.get("swagger").asText());
            }

            // Info section
            if (root.has("info")) {
                JsonNode info = root.get("info");
                if (info.has("title")) {
                    printDetail("Title", info.get("title").asText());
                }
                if (info.has("version")) {
                    printDetail("API Version", info.get("version").asText());
                }
                if (info.has("description")) {
                    String desc = info.get("description").asText();
                    if (desc.length() > 80) {
                        desc = desc.substring(0, 80) + "...";
                    }
                    printDetail("Description", desc);
                }
            }

            // Count paths
            if (root.has("paths")) {
                int pathCount = root.get("paths").size();
                printDetail("Endpoints", String.valueOf(pathCount));

                if (verbose) {
                    System.out.println();
                    System.out.println(colorize("  Available endpoints:", ANSI_WHITE));
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
                        // Color-code HTTP methods
                        StringBuilder methodStr = new StringBuilder();
                        for (int i = 0; i < methods.size(); i++) {
                            if (i > 0) methodStr.append(" ");
                            methodStr.append(colorizeMethod(methods.get(i)));
                        }
                        System.out.println(colorize("    " + SYM_CIRCLE + " ", ANSI_WHITE) +
                                colorize(path, ANSI_CYAN) + " " + methodStr);
                    }
                }
            }
            System.out.println();

        } catch (Exception e) {
            // Ignore errors when printing spec info
            if (verbose) {
                printWarning("Could not parse spec details: " + e.getMessage());
            }
        }
    }

    /**
     * Colorize HTTP method names
     */
    private static String colorizeMethod(String method) {
        String color;
        switch (method) {
            case "GET":
                color = ANSI_BRIGHT_GREEN;
                break;
            case "POST":
                color = ANSI_BRIGHT_YELLOW;
                break;
            case "PUT":
                color = ANSI_BRIGHT_BLUE;
                break;
            case "PATCH":
                color = ANSI_BRIGHT_MAGENTA;
                break;
            case "DELETE":
                color = ANSI_BRIGHT_RED;
                break;
            default:
                color = ANSI_WHITE;
        }
        return colorize("[" + method + "]", color);
    }

    /**
     * Format query parameters for display
     */
    private static String formatParams(Map<String, List<String>> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(entry.getKey()).append("=").append(String.join(",", entry.getValue()));
            first = false;
        }
        return sb.toString();
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
        int width = 60;
        String line = repeatString(BOX_H, width - 2);
        System.out.println();
        System.out.println(colorize(BOX_TL + line + BOX_TR, ANSI_BRIGHT_CYAN));
        System.out.println(colorize(BOX_V + " " + centerText(text, width - 4) + " " + BOX_V, ANSI_BRIGHT_CYAN));
        System.out.println(colorize(BOX_BL + line + BOX_BR, ANSI_BRIGHT_CYAN));
        System.out.println();
    }

    private static void printSubHeader(String text) {
        System.out.println();
        System.out.println(colorize(SYM_ARROW + " " + text, ANSI_BRIGHT_BLUE));
        System.out.println(colorize(repeatString(BOX_H, text.length() + 3), ANSI_BLUE));
    }

    private static String centerText(String text, int width) {
        if (text.length() >= width) return text;
        int padding = (width - text.length()) / 2;
        return repeatString(" ", padding) + text + repeatString(" ", width - text.length() - padding);
    }

    private static void printSuccess(String text) {
        System.out.println(colorize(SYM_CHECK + " " + text, ANSI_BRIGHT_GREEN));
    }

    private static void printError(String text) {
        System.out.println(colorize(SYM_CROSS + " " + text, ANSI_BRIGHT_RED));
    }

    private static void printWarning(String text) {
        System.out.println(colorize(SYM_WARN + " " + text, ANSI_BRIGHT_YELLOW));
    }

    private static void printInfo(String text) {
        System.out.println(colorize(SYM_INFO + " " + text, ANSI_CYAN));
    }

    private static void printDetail(String label, String value) {
        System.out.println(colorize("  " + SYM_BULLET + " ", ANSI_WHITE) +
                colorize(label + ": ", ANSI_WHITE) +
                colorize(value, ANSI_BRIGHT_CYAN));
    }

    private static void printSummaryBar(int errors, int warnings, int infos, boolean blocked) {
        int width = 60;
        String line = repeatString(BOX_H, width - 2);

        System.out.println();
        if (blocked) {
            // Red failure bar
            System.out.println(colorize(BOX_TL + line + BOX_TR, ANSI_RED));
            System.out.println(colorize(BOX_V, ANSI_RED) +
                    colorize(centerText(SYM_CROSS + " VALIDATION FAILED", width - 4), ANSI_BRIGHT_RED) +
                    colorize(BOX_V, ANSI_RED));
        } else if (errors == 0 && warnings == 0) {
            // Green success bar
            System.out.println(colorize(BOX_TL + line + BOX_TR, ANSI_GREEN));
            System.out.println(colorize(BOX_V, ANSI_GREEN) +
                    colorize(centerText(SYM_CHECK + " VALIDATION PASSED", width - 4), ANSI_BRIGHT_GREEN) +
                    colorize(BOX_V, ANSI_GREEN));
        } else {
            // Yellow warning bar
            System.out.println(colorize(BOX_TL + line + BOX_TR, ANSI_YELLOW));
            System.out.println(colorize(BOX_V, ANSI_YELLOW) +
                    colorize(centerText(SYM_WARN + " PASSED WITH WARNINGS", width - 4), ANSI_BRIGHT_YELLOW) +
                    colorize(BOX_V, ANSI_YELLOW));
        }

        // Stats line
        String stats = String.format("%d errors  %s  %d warnings  %s  %d info",
                errors, SYM_BULLET, warnings, SYM_BULLET, infos);
        String coloredStats =
                colorize(errors + " errors", errors > 0 ? ANSI_BRIGHT_RED : ANSI_WHITE) + "  " +
                        colorize(SYM_BULLET, ANSI_WHITE) + "  " +
                        colorize(warnings + " warnings", warnings > 0 ? ANSI_BRIGHT_YELLOW : ANSI_WHITE) + "  " +
                        colorize(SYM_BULLET, ANSI_WHITE) + "  " +
                        colorize(infos + " info", ANSI_WHITE);

        // Calculate padding for centering (approximate since we have color codes)
        int statsLen = stats.length();
        int leftPad = (width - 4 - statsLen) / 2;

        String barColor = blocked ? ANSI_RED : (errors == 0 && warnings == 0 ? ANSI_GREEN : ANSI_YELLOW);
        System.out.println(colorize(BOX_V, barColor) + " " +
                repeatString(" ", leftPad) + coloredStats +
                repeatString(" ", width - 4 - statsLen - leftPad) + " " +
                colorize(BOX_V, barColor));

        System.out.println(colorize(BOX_BL + line + BOX_BR, barColor));
        System.out.println();
    }

    private static void printVersion() {
        System.out.println(colorize("OpenAPI Validator CLI", ANSI_BRIGHT_CYAN) +
                colorize(" v" + VERSION, ANSI_WHITE));
        System.out.println(colorize("Using BNPPF OpenAPI Validator Library", ANSI_WHITE));
    }

    private static void printValidateUsage() {
        System.out.println();
        System.out.println(colorize("VALIDATE USAGE", ANSI_BRIGHT_YELLOW));
        System.out.println(colorize(repeatString(BOX_H, 50), ANSI_WHITE));
        System.out.println("  " + colorize("validate", ANSI_BRIGHT_GREEN) +
                colorize(" --spec <file> --method <METHOD> --path <path>", ANSI_CYAN));
        System.out.println();

        System.out.println(colorize("OPTIONS", ANSI_BRIGHT_YELLOW));
        System.out.println(colorize(repeatString(BOX_H, 50), ANSI_WHITE));

        printValidateOption("--spec, -s", "<file>", "OpenAPI specification file (JSON/YAML)");
        printValidateOption("--method, -m", "<method>", "HTTP method (GET, POST, PUT, DELETE)");
        printValidateOption("--path, -p", "<path>", "Request path (e.g., /users/123)");
        printValidateOption("--body, -b", "<json>", "Request body as JSON string");
        printValidateOption("--body-file", "<file>", "Request body from file");
        printValidateOption("--request, -r", "<file>", "Load request from JSON/YAML file");
        printValidateOption("--response", "<file>", "Response body file to validate");
        printValidateOption("--status", "<code>", "Response status code (default: 200)");
        printValidateOption("--header, -H", "<h:v>", "Add header (repeatable)");
        printValidateOption("--query, -q", "<k=v>", "Add query parameter (repeatable)");
        printValidateOption("--content-type, -c", "<type>", "Content-Type (default: application/json)");
        printValidateOption("--level, -l", "<level>", "Validation level: STRICT, LENIENT, LIGHT");
        System.out.println();
    }

    private static void printValidateOption(String flag, String arg, String desc) {
        System.out.println("  " + colorize(flag, ANSI_BRIGHT_MAGENTA) + " " +
                colorize(arg, ANSI_CYAN));
        System.out.println("      " + colorize(desc, ANSI_WHITE));
    }

    private static void printHelp() {
        System.out.println();

        // Title banner
        String title = "OpenAPI Validator CLI v" + VERSION;
        int bannerWidth = 50;
        String bannerLine = repeatString(BOX_H, bannerWidth - 2);

        System.out.println(colorize(BOX_TL + bannerLine + BOX_TR, ANSI_BRIGHT_CYAN));
        System.out.println(colorize(BOX_V + centerText(title, bannerWidth - 2) + BOX_V, ANSI_BRIGHT_CYAN));
        System.out.println(colorize(BOX_BL + bannerLine + BOX_BR, ANSI_BRIGHT_CYAN));

        System.out.println();
        System.out.println(colorize("  A tool for testing OpenAPI specifications and validating API requests.", ANSI_WHITE));
        System.out.println(colorize("  Using BNPPF OpenAPI Validator Library", ANSI_WHITE));
        System.out.println();

        // Usage
        System.out.println(colorize("USAGE", ANSI_BRIGHT_YELLOW));
        System.out.println(colorize(repeatString(BOX_H, 50), ANSI_WHITE));
        System.out.println("  " + colorize("openapi-validator", ANSI_BRIGHT_GREEN) +
                colorize(" <command> ", ANSI_CYAN) +
                colorize("[options]", ANSI_WHITE));
        System.out.println();

        // Commands
        System.out.println(colorize("COMMANDS", ANSI_BRIGHT_YELLOW));
        System.out.println(colorize(repeatString(BOX_H, 50), ANSI_WHITE));
        printHelpCommand("check", "<spec-file>", "Check if a specification loads correctly");
        printHelpCommand("validate", "[options]", "Validate a request against a specification");
        printHelpCommand("help", "", "Show this help message");
        printHelpCommand("version", "", "Show version information");
        System.out.println();

        // Global Options
        System.out.println(colorize("GLOBAL OPTIONS", ANSI_BRIGHT_YELLOW));
        System.out.println(colorize(repeatString(BOX_H, 50), ANSI_WHITE));
        printHelpOption("--no-color", "Disable colored output");
        printHelpOption("-v, --verbose", "Enable verbose output and debug info");
        System.out.println();

        // Validation Levels
        System.out.println(colorize("VALIDATION LEVELS", ANSI_BRIGHT_YELLOW));
        System.out.println(colorize(repeatString(BOX_H, 50), ANSI_WHITE));
        System.out.println("  " + colorize(SYM_BULLET + " STRICT  ", ANSI_BRIGHT_RED) +
                colorize("Enforce all specification rules strictly", ANSI_WHITE));
        System.out.println("  " + colorize(SYM_BULLET + " LENIENT ", ANSI_BRIGHT_YELLOW) +
                colorize("Allow additional properties (default)", ANSI_WHITE));
        System.out.println("  " + colorize(SYM_BULLET + " LIGHT   ", ANSI_BRIGHT_GREEN) +
                colorize("Minimal validation, issues reported as info", ANSI_WHITE));
        System.out.println();

        // Examples
        System.out.println(colorize("EXAMPLES", ANSI_BRIGHT_YELLOW));
        System.out.println(colorize(repeatString(BOX_H, 50), ANSI_WHITE));
        System.out.println();

        printExample("Check if a specification is valid",
                "openapi-validator check api-spec.yaml");

        printExample("Validate a GET request",
                "openapi-validator validate --spec api-spec.yaml \\\n" +
                        "    --method GET --path /users/123");

        printExample("Validate a POST request with body",
                "openapi-validator validate --spec api-spec.yaml \\\n" +
                        "    --method POST --path /users \\\n" +
                        "    --body '{\"name\":\"John\",\"email\":\"john@example.com\"}'");

        printExample("Use STRICT validation level",
                "openapi-validator validate --spec api-spec.yaml \\\n" +
                        "    --method GET --path /users --level STRICT");
        System.out.println();
    }

    private static void printHelpCommand(String cmd, String args, String desc) {
        System.out.println("  " + colorize(cmd, ANSI_BRIGHT_GREEN) +
                (args.isEmpty() ? "" : " " + colorize(args, ANSI_CYAN)) +
                "\n      " + colorize(desc, ANSI_WHITE));
    }

    private static void printHelpOption(String option, String desc) {
        System.out.println("  " + colorize(option, ANSI_BRIGHT_MAGENTA) +
                "\n      " + colorize(desc, ANSI_WHITE));
    }

    private static void printExample(String title, String command) {
        System.out.println(colorize("  " + SYM_ARROW + " " + title, ANSI_WHITE));
        for (String line : command.split("\n")) {
            System.out.println(colorize("    $ " + line, ANSI_GREEN));
        }
        System.out.println();
    }
}
