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

import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight OpenAPI Validator for Apigee Edge
 *
 * Uses networknt/json-schema-validator directly instead of the heavy
 * swagger-request-validator library. Results in a ~2-3MB JAR instead of 15MB+.
 *
 * Features:
 * - OpenAPI 3.0 schema validation
 * - Request body validation
 * - Configurable validation levels (strict/lenient)
 * - Schema caching for performance
 *
 * Properties:
 * - specfile: OpenAPI spec content (YAML or JSON)
 * - spec-url: URL to fetch spec from
 * - validation-level: "strict" or "lenient" (allows additional properties)
 * - path-override: Override the path to validate (optional)
 * - method-override: Override the HTTP method (optional)
 */
public class OpenApiValidatorLite implements Execution {

    private static final String LEVEL_STRICT = "strict";
    private static final String LEVEL_LENIENT = "lenient";

    // Cache parsed schemas
    private static final ConcurrentHashMap<String, ParsedSpec> SPEC_CACHE = new ConcurrentHashMap<>();

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private final Map<String, String> properties;

    public OpenApiValidatorLite(Map<String, String> properties) {
        this.properties = properties;
    }

    @Override
    public ExecutionResult execute(MessageContext messageContext, ExecutionContext executionContext) {
        try {
            // Get spec content
            String specContent = resolveProperty("specfile", messageContext);
            if (specContent == null || specContent.isEmpty()) {
                String specUrl = resolveProperty("spec-url", messageContext);
                if (specUrl != null && !specUrl.isEmpty()) {
                    specContent = fetchUrl(specUrl);
                }
            }

            if (specContent == null || specContent.isEmpty()) {
                setError(messageContext, "OpenAPI spec not provided. Set 'specfile' or 'spec-url' property.");
                return ExecutionResult.ABORT;
            }

            // Get validation level
            String level = resolveProperty("validation-level", messageContext);
            if (level == null) level = LEVEL_STRICT;
            boolean lenient = LEVEL_LENIENT.equalsIgnoreCase(level.trim());

            // Parse spec (cached)
            ParsedSpec spec = getOrParseSpec(specContent, lenient);

            // Get request details
            String method = messageContext.getVariable("request.verb");
            String path = messageContext.getVariable("proxy.pathsuffix");
            String body = messageContext.getVariable("request.content");

            // Allow overrides
            String methodOverride = resolveProperty("method-override", messageContext);
            String pathOverride = resolveProperty("path-override", messageContext);
            if (methodOverride != null && !methodOverride.isEmpty()) method = methodOverride;
            if (pathOverride != null && !pathOverride.isEmpty()) path = pathOverride;

            if (method == null) method = "GET";
            if (path == null || path.isEmpty()) path = "/";

            // Find schema for this path/method
            JsonSchema schema = spec.findSchema(path, method.toLowerCase());

            if (schema == null) {
                // No schema defined for this path/method - pass through
                messageContext.setVariable("openapi.validation.failed", "false");
                messageContext.setVariable("openapi.validation.info", "No schema defined for " + method + " " + path);
                return ExecutionResult.SUCCESS;
            }

            // Validate request body
            if (body == null || body.trim().isEmpty()) {
                // No body to validate
                messageContext.setVariable("openapi.validation.failed", "false");
                return ExecutionResult.SUCCESS;
            }

            JsonNode bodyNode;
            try {
                bodyNode = JSON_MAPPER.readTree(body);
            } catch (Exception e) {
                setError(messageContext, "Invalid JSON in request body: " + e.getMessage());
                return ExecutionResult.ABORT;
            }

            Set<ValidationMessage> errors = schema.validate(bodyNode);

            if (errors.isEmpty()) {
                messageContext.setVariable("openapi.validation.failed", "false");
                return ExecutionResult.SUCCESS;
            } else {
                StringBuilder sb = new StringBuilder();
                for (ValidationMessage msg : errors) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(msg.getMessage());
                }
                setError(messageContext, sb.toString());
                messageContext.setVariable("openapi.validation.errors.count", String.valueOf(errors.size()));
                return ExecutionResult.ABORT;
            }

        } catch (Exception e) {
            setError(messageContext, "Validation error: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return ExecutionResult.ABORT;
        }
    }

    private ParsedSpec getOrParseSpec(String specContent, boolean lenient) throws Exception {
        String hash = hashSpec(specContent) + "|" + lenient;
        return SPEC_CACHE.computeIfAbsent(hash, k -> {
            try {
                return parseSpec(specContent, lenient);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse spec: " + e.getMessage(), e);
            }
        });
    }

    private ParsedSpec parseSpec(String specContent, boolean lenient) throws Exception {
        // Parse YAML or JSON
        JsonNode root;
        specContent = specContent.trim();
        if (specContent.startsWith("{")) {
            root = JSON_MAPPER.readTree(specContent);
        } else {
            root = YAML_MAPPER.readTree(specContent);
        }

        return new ParsedSpec(root, lenient);
    }

    private String resolveProperty(String name, MessageContext ctx) {
        String value = properties.get(name);
        if (value == null) return null;

        // Handle variable reference {varName}
        if (value.startsWith("{") && value.endsWith("}") && !value.startsWith("{\"")) {
            String varName = value.substring(1, value.length() - 1);
            Object resolved = ctx.getVariable(varName);
            return resolved != null ? resolved.toString() : null;
        }
        return value;
    }

    private String fetchUrl(String url) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);

            if (conn.getResponseCode() != 200) {
                return null;
            }

            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void setError(MessageContext ctx, String message) {
        ctx.setVariable("openapi.validation.failed", "true");
        ctx.setVariable("openapi.validation.error", message);
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

    public static void clearCache() {
        SPEC_CACHE.clear();
    }

    /**
     * Holds parsed OpenAPI spec with pre-compiled schemas for each path/method
     */
    private static class ParsedSpec {
        private final JsonNode root;
        private final ConcurrentHashMap<String, JsonSchema> schemas = new ConcurrentHashMap<>();
        private final boolean lenient;
        private final JsonSchemaFactory factory;

        ParsedSpec(JsonNode root, boolean lenient) {
            this.root = root;
            this.lenient = lenient;
            this.factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        }

        JsonSchema findSchema(String path, String method) {
            String key = method + "|" + path;
            return schemas.computeIfAbsent(key, k -> {
                try {
                    return extractSchema(path, method);
                } catch (Exception e) {
                    return null;
                }
            });
        }

        private JsonSchema extractSchema(String path, String method) {
            JsonNode paths = root.get("paths");
            if (paths == null) return null;

            // Try exact match first
            JsonNode pathNode = paths.get(path);

            // Try path parameters (e.g., /users/{id})
            if (pathNode == null) {
                for (java.util.Iterator<String> it = paths.fieldNames(); it.hasNext(); ) {
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

            JsonNode requestBody = methodNode.get("requestBody");
            if (requestBody == null) return null;

            JsonNode content = requestBody.get("content");
            if (content == null) return null;

            // Try application/json first
            JsonNode mediaType = content.get("application/json");
            if (mediaType == null) {
                // Try first available content type
                java.util.Iterator<JsonNode> it = content.elements();
                if (it.hasNext()) {
                    mediaType = it.next();
                }
            }

            if (mediaType == null) return null;

            JsonNode schemaNode = mediaType.get("schema");
            if (schemaNode == null) return null;

            // Resolve $ref if present
            schemaNode = resolveRef(schemaNode);

            // If lenient mode, modify schema to allow additional properties
            if (lenient && schemaNode.isObject()) {
                schemaNode = makeSchemaLenient(schemaNode);
            }

            return factory.getSchema(schemaNode);
        }

        private JsonNode resolveRef(JsonNode node) {
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

            // Allow additional properties
            if (copy.has("type") && "object".equals(copy.get("type").asText())) {
                copy.put("additionalProperties", true);
            }

            // Recursively make nested schemas lenient
            if (copy.has("properties")) {
                ObjectNode props = (ObjectNode) copy.get("properties");
                java.util.Iterator<String> names = props.fieldNames();
                while (names.hasNext()) {
                    String name = names.next();
                    JsonNode propSchema = props.get(name);
                    if (propSchema.isObject()) {
                        props.set(name, makeSchemaLenient(propSchema));
                    }
                }
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
