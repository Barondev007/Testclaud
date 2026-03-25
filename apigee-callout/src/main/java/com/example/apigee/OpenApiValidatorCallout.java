package com.example.apigee;

import com.apigee.flow.execution.ExecutionContext;
import com.apigee.flow.execution.ExecutionResult;
import com.apigee.flow.execution.spi.Execution;
import com.apigee.flow.message.MessageContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.networknt.schema.SchemaValidatorsConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Apigee Java Callout for OpenAPI Request/Response Validation
 *
 * Uses lightweight networknt/json-schema-validator library (~1MB)
 * instead of swagger-request-validator (~15MB).
 *
 * Features:
 * - Spec content from property, variable, URL, or resource file
 * - Thread-safe validator caching (one validator per unique spec + validation level)
 * - Configurable validation levels
 * - Support for both request and response validation
 * - OpenAPI 3.0+ support
 *
 * Spec Source (one of these is required, checked in order):
 * 1. specfile     : The OpenAPI spec content as a String (YAML or JSON), or variable reference {varName}
 * 2. spec-url     : URL to fetch the spec from (e.g., "https://storage.googleapis.com/bucket/spec.yaml")
 * 3. spec-resource: Path to spec file bundled inside the JAR (e.g., "openapi/petstore.yaml")
 *
 * Optional Properties:
 * - validation-type : Type of validation (default: "request")
 *     - "request"  : Validate incoming request
 *     - "response" : Validate outgoing response
 * - validation-level : Validation level (default: "strict")
 *     - "light"    : All errors stored in variable, flow NOT blocked
 *     - "lenient"  : Additional properties allowed
 *     - "moderate" : Additional properties allowed at root level only
 *     - "strict"   : All errors block the flow
 *
 * Output Variables:
 * - openapi.validation.failed : "true" or "false"
 * - openapi.validation.error : Error message(s) if validation failed
 * - openapi.validation.errors.count : Number of validation errors
 * - openapi.validation.errors.all : All error messages (for light mode)
 * - openapi.validation.type : "request" or "response"
 */
public class OpenApiValidatorCallout implements Execution {

    // ========================================================================
    // VALIDATION TYPES
    // ========================================================================

    private static final String TYPE_REQUEST = "request";
    private static final String TYPE_RESPONSE = "response";

    // ========================================================================
    // VALIDATION LEVELS
    // ========================================================================

    private static final String LEVEL_LIGHT = "light";
    private static final String LEVEL_LENIENT = "lenient";
    private static final String LEVEL_MODERATE = "moderate";
    private static final String LEVEL_STRICT = "strict";

    // ========================================================================
    // SPEC CACHE
    // ========================================================================

    private static final ConcurrentHashMap<String, ParsedSpec> SPEC_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> URL_CACHE = new ConcurrentHashMap<>();

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private final Map<String, String> properties;
    private String lastResourceError = null;

    public OpenApiValidatorCallout(Map<String, String> properties) {
        this.properties = properties;
    }

    // ========================================================================
    // MAIN EXECUTION
    // ========================================================================

    @Override
    public ExecutionResult execute(MessageContext messageContext, ExecutionContext executionContext) {
        try {
            // Get spec content - try specfile first, then spec-url, then spec-resource
            String specContent = resolveProperty("specfile", messageContext);
            String specSource = "specfile";

            // If specfile is not set, try to load from URL
            if (specContent == null || specContent.isEmpty()) {
                String specUrl = resolveProperty("spec-url", messageContext);
                if (specUrl != null && !specUrl.isEmpty()) {
                    specContent = loadFromUrl(specUrl);
                    specSource = "spec-url:" + specUrl;
                }
            }

            // If still not set, try to load from proxy resource file
            if (specContent == null || specContent.isEmpty()) {
                String resourcePath = resolveProperty("spec-resource", messageContext);
                if (resourcePath != null && !resourcePath.isEmpty()) {
                    specContent = loadProxyResource(resourcePath, messageContext);
                    specSource = "spec-resource:" + resourcePath;
                }
            }

            // Debug variables
            messageContext.setVariable("openapi.debug.spec.source", specSource);

            if (specContent == null || specContent.isEmpty()) {
                String errorMsg = "OpenAPI spec not provided. ";
                if (lastResourceError != null) {
                    errorMsg += lastResourceError;
                } else {
                    errorMsg += "Set 'specfile', 'spec-url', or 'spec-resource' property.";
                }
                setError(messageContext, errorMsg);
                return ExecutionResult.ABORT;
            }

            specContent = specContent.trim();

            // Get validation level (default: strict)
            String validationLevel = resolveProperty("validation-level", messageContext);
            if (validationLevel == null || validationLevel.isEmpty()) {
                validationLevel = LEVEL_STRICT;
            }
            validationLevel = validationLevel.toLowerCase().trim();

            // Validate level parameter
            if (!LEVEL_LIGHT.equals(validationLevel) &&
                !LEVEL_LENIENT.equals(validationLevel) &&
                !LEVEL_MODERATE.equals(validationLevel) &&
                !LEVEL_STRICT.equals(validationLevel)) {
                validationLevel = LEVEL_STRICT;
            }

            messageContext.setVariable("openapi.debug.validation.level", validationLevel);

            // Get validation type (default: request)
            String validationType = resolveProperty("validation-type", messageContext);
            if (validationType == null || validationType.isEmpty()) {
                validationType = TYPE_REQUEST;
            }
            validationType = validationType.toLowerCase().trim();

            // Validate type parameter
            if (!TYPE_REQUEST.equals(validationType) && !TYPE_RESPONSE.equals(validationType)) {
                validationType = TYPE_REQUEST;
            }

            messageContext.setVariable("openapi.validation.type", validationType);

            // Parse spec (cached)
            ParsedSpec spec;
            try {
                spec = getOrParseSpec(specContent, validationLevel);
            } catch (Exception e) {
                setError(messageContext, "Failed to parse OpenAPI spec: " + e.getMessage());
                return ExecutionResult.ABORT;
            }

            // Get request/response details
            String httpMethod = messageContext.getVariable("request.verb");
            String requestPath = messageContext.getVariable("proxy.pathsuffix");

            if (httpMethod == null) httpMethod = "GET";
            if (requestPath == null || requestPath.isEmpty()) requestPath = "/";

            // Get body based on validation type
            String body;
            if (TYPE_RESPONSE.equals(validationType)) {
                body = messageContext.getVariable("response.content");
            } else {
                body = messageContext.getVariable("request.content");
            }

            // Find schema for this path/method
            JsonSchema schema = spec.findSchema(requestPath, httpMethod.toLowerCase(), validationType);

            if (schema == null) {
                // No schema defined for this path/method - pass through
                messageContext.setVariable("openapi.validation.failed", "false");
                messageContext.setVariable("openapi.validation.info",
                    "No schema defined for " + httpMethod + " " + requestPath);
                return ExecutionResult.SUCCESS;
            }

            // If no body to validate, pass through
            if (body == null || body.trim().isEmpty()) {
                messageContext.setVariable("openapi.validation.failed", "false");
                return ExecutionResult.SUCCESS;
            }

            // Parse body as JSON
            JsonNode bodyNode;
            try {
                bodyNode = JSON_MAPPER.readTree(body);
            } catch (Exception e) {
                setError(messageContext, "Invalid JSON in " + validationType + " body: " + e.getMessage());
                return ExecutionResult.ABORT;
            }

            // Validate
            Set<ValidationMessage> errors = schema.validate(bodyNode);

            // Collect error messages
            List<String> allMessages = new ArrayList<>();
            for (ValidationMessage msg : errors) {
                allMessages.add(msg.getMessage());
            }
            int errorCount = allMessages.size();

            // Store all messages
            messageContext.setVariable("openapi.validation.errors.all", String.join("; ", allMessages));
            messageContext.setVariable("openapi.validation.errors.count", String.valueOf(errorCount));

            // Handle based on validation level
            if (LEVEL_LIGHT.equals(validationLevel)) {
                // Light mode: Store errors but don't block
                if (errorCount > 0) {
                    messageContext.setVariable("openapi.validation.error", String.join("; ", allMessages));
                    messageContext.setVariable("openapi.validation.failed", "true");
                } else {
                    messageContext.setVariable("openapi.validation.failed", "false");
                }
                // Always return SUCCESS in light mode (non-blocking)
                return ExecutionResult.SUCCESS;

            } else {
                // Other modes: Block on errors
                if (errorCount > 0) {
                    setError(messageContext, String.join("; ", allMessages));
                    return ExecutionResult.ABORT;
                }
                messageContext.setVariable("openapi.validation.failed", "false");
                return ExecutionResult.SUCCESS;
            }

        } catch (Exception e) {
            setError(messageContext, "Internal error: " + e.getClass().getName() + " - " + e.getMessage());
            return ExecutionResult.ABORT;
        }
    }

    // ========================================================================
    // SPEC PARSING AND CACHING
    // ========================================================================

    private ParsedSpec getOrParseSpec(String specContent, String validationLevel) throws Exception {
        String hash = hashSpec(specContent) + "|" + validationLevel;
        return SPEC_CACHE.computeIfAbsent(hash, k -> {
            try {
                return parseSpec(specContent, validationLevel);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse spec: " + e.getMessage(), e);
            }
        });
    }

    private ParsedSpec parseSpec(String specContent, String validationLevel) throws Exception {
        // Parse YAML or JSON
        JsonNode root;
        if (specContent.startsWith("{")) {
            root = JSON_MAPPER.readTree(specContent);
        } else {
            root = YAML_MAPPER.readTree(specContent);
        }

        boolean allowAdditionalProperties = LEVEL_LENIENT.equals(validationLevel) ||
                                            LEVEL_MODERATE.equals(validationLevel);

        return new ParsedSpec(root, allowAdditionalProperties);
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private String loadFromUrl(String specUrl) {
        // Check cache first
        String cached = URL_CACHE.get(specUrl);
        if (cached != null) {
            return cached;
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL(specUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept", "application/yaml, application/json, text/yaml, text/plain");

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                lastResourceError = "Failed to fetch spec from URL '" + specUrl + "': HTTP " + responseCode;
                return null;
            }

            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
            }

            String specContent = content.toString();
            URL_CACHE.put(specUrl, specContent);
            lastResourceError = null;

            return specContent;

        } catch (Exception e) {
            lastResourceError = "Error fetching spec from URL '" + specUrl + "': " + e.getMessage();
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String loadProxyResource(String resourcePath, MessageContext messageContext) {
        // Normalize resource path - remove oas:// prefix if present
        String normalizedPath = resourcePath;
        if (resourcePath.startsWith("oas://")) {
            normalizedPath = resourcePath.substring(6);
        }

        try {
            // Try classloader
            java.io.InputStream inputStream = getClass().getClassLoader().getResourceAsStream(normalizedPath);
            if (inputStream == null) {
                inputStream = getClass().getResourceAsStream("/" + normalizedPath);
            }

            if (inputStream == null) {
                lastResourceError = "Resource '" + resourcePath + "' not found in classpath";
                return null;
            }

            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
            }
            lastResourceError = null;
            return content.toString();

        } catch (Exception e) {
            lastResourceError = "Error loading resource '" + resourcePath + "': " + e.getMessage();
            return null;
        }
    }

    private String resolveProperty(String propertyName, MessageContext messageContext) {
        String value = properties.get(propertyName);
        if (value == null) {
            return null;
        }

        // Check for variable reference: {varName}
        if (value.startsWith("{") && value.endsWith("}") && !value.startsWith("{\"")) {
            String varName = value.substring(1, value.length() - 1);
            Object resolved = messageContext.getVariable(varName);
            return resolved != null ? resolved.toString() : null;
        }

        return value;
    }

    private void setError(MessageContext messageContext, String errorMessage) {
        messageContext.setVariable("openapi.validation.error", errorMessage);
        messageContext.setVariable("openapi.validation.failed", "true");
    }

    private static String hashSpec(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(content.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(content.hashCode());
        }
    }

    // ========================================================================
    // CACHE MANAGEMENT
    // ========================================================================

    public static void clearCache() {
        SPEC_CACHE.clear();
        URL_CACHE.clear();
    }

    public static int getCacheSize() {
        return SPEC_CACHE.size();
    }

    // ========================================================================
    // PARSED SPEC CLASS
    // ========================================================================

    /**
     * Holds parsed OpenAPI spec with pre-compiled JSON schemas for each path/method
     */
    private static class ParsedSpec {
        private final JsonNode root;
        private final ConcurrentHashMap<String, JsonSchema> schemas = new ConcurrentHashMap<>();
        private final boolean allowAdditionalProperties;
        private final JsonSchemaFactory factory;

        ParsedSpec(JsonNode root, boolean allowAdditionalProperties) {
            this.root = root;
            this.allowAdditionalProperties = allowAdditionalProperties;
            this.factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        }

        JsonSchema findSchema(String path, String method, String validationType) {
            String key = validationType + "|" + method + "|" + path;
            return schemas.computeIfAbsent(key, k -> {
                try {
                    return extractSchema(path, method, validationType);
                } catch (Exception e) {
                    return null;
                }
            });
        }

        private JsonSchema extractSchema(String path, String method, String validationType) {
            JsonNode paths = root.get("paths");
            if (paths == null) return null;

            // Try exact match first
            JsonNode pathNode = paths.get(path);

            // Try path parameters (e.g., /users/{id})
            if (pathNode == null) {
                for (Iterator<String> it = paths.fieldNames(); it.hasNext(); ) {
                    String template = it.next();
                    if (pathMatches(path, template)) {
                        pathNode = paths.get(template);
                        break;
                    }
                }
            }

            if (pathNode == null) return null;

            JsonNode methodNode = pathNode.get(method);
            if (methodNode == null) return null;

            JsonNode schemaNode;

            if (TYPE_RESPONSE.equals(validationType)) {
                // Response validation - get from responses section
                JsonNode responses = methodNode.get("responses");
                if (responses == null) return null;

                // Try 200, then 201, then default
                JsonNode responseNode = responses.get("200");
                if (responseNode == null) responseNode = responses.get("201");
                if (responseNode == null) responseNode = responses.get("default");
                if (responseNode == null) return null;

                JsonNode content = responseNode.get("content");
                if (content == null) return null;

                JsonNode mediaType = content.get("application/json");
                if (mediaType == null) {
                    Iterator<JsonNode> it = content.elements();
                    if (it.hasNext()) mediaType = it.next();
                }
                if (mediaType == null) return null;

                schemaNode = mediaType.get("schema");
            } else {
                // Request validation - get from requestBody
                JsonNode requestBody = methodNode.get("requestBody");
                if (requestBody == null) return null;

                JsonNode content = requestBody.get("content");
                if (content == null) return null;

                JsonNode mediaType = content.get("application/json");
                if (mediaType == null) {
                    Iterator<JsonNode> it = content.elements();
                    if (it.hasNext()) mediaType = it.next();
                }
                if (mediaType == null) return null;

                schemaNode = mediaType.get("schema");
            }

            if (schemaNode == null) return null;

            // Resolve $ref if present
            schemaNode = resolveRef(schemaNode);

            // If lenient mode, modify schema to allow additional properties
            if (allowAdditionalProperties && schemaNode.isObject()) {
                schemaNode = makeSchemaLenient(schemaNode);
            }

            // Configure schema validator
            SchemaValidatorsConfig config = new SchemaValidatorsConfig();
            config.setTypeLoose(false);

            return factory.getSchema(schemaNode, config);
        }

        private JsonNode resolveRef(JsonNode node) {
            if (node == null) return null;

            if (node.has("$ref")) {
                String ref = node.get("$ref").asText();
                // Handle #/components/schemas/Name format
                if (ref.startsWith("#/")) {
                    String[] parts = ref.substring(2).split("/");
                    JsonNode current = root;
                    for (String part : parts) {
                        current = current.get(part);
                        if (current == null) return node;
                    }
                    return resolveRef(current); // Recursively resolve
                }
            }
            return node;
        }

        private JsonNode makeSchemaLenient(JsonNode schema) {
            if (!schema.isObject()) return schema;

            ObjectNode copy = schema.deepCopy();

            // Allow additional properties for object types
            if (copy.has("type") && "object".equals(copy.get("type").asText())) {
                copy.put("additionalProperties", true);
            }

            // If no type but has properties, treat as object
            if (!copy.has("type") && copy.has("properties")) {
                copy.put("additionalProperties", true);
            }

            // Recursively make nested schemas lenient
            if (copy.has("properties")) {
                ObjectNode props = (ObjectNode) copy.get("properties");
                Iterator<String> names = props.fieldNames();
                while (names.hasNext()) {
                    String name = names.next();
                    JsonNode propSchema = props.get(name);
                    if (propSchema.isObject()) {
                        props.set(name, makeSchemaLenient(resolveRef(propSchema)));
                    }
                }
            }

            // Handle allOf, anyOf, oneOf
            for (String keyword : new String[]{"allOf", "anyOf", "oneOf"}) {
                if (copy.has(keyword) && copy.get(keyword).isArray()) {
                    for (int i = 0; i < copy.get(keyword).size(); i++) {
                        JsonNode item = copy.get(keyword).get(i);
                        if (item.isObject()) {
                            ((com.fasterxml.jackson.databind.node.ArrayNode) copy.get(keyword))
                                .set(i, makeSchemaLenient(resolveRef(item)));
                        }
                    }
                }
            }

            // Handle items for arrays
            if (copy.has("items") && copy.get("items").isObject()) {
                copy.set("items", makeSchemaLenient(resolveRef(copy.get("items"))));
            }

            return copy;
        }

        private boolean pathMatches(String actualPath, String template) {
            // Simple path matching with {param} placeholders
            String regex = template.replaceAll("\\{[^}]+\\}", "[^/]+");
            return actualPath.matches("^" + regex + "$");
        }
    }
}
