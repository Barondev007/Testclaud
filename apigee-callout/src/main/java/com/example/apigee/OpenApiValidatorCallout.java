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
 * Uses lightweight networknt/json-schema-validator library.
 *
 * Properties:
 * - specfile: The OpenAPI spec content (YAML or JSON), or variable reference {varName}
 * - validation-type: "request" or "response" (default: "request")
 * - validation-level: "strict", "lenient", "moderate", "light" (default: "strict")
 *
 * Output Variables:
 * - openapi.validation.failed: "true" or "false"
 * - openapi.validation.error: Error message(s) if validation failed
 * - openapi.validation.errors.count: Number of validation errors
 * - openapi.validation.errors.all: All error messages
 * - openapi.validation.type: "request" or "response"
 */
public class OpenApiValidatorCallout implements Execution {

    private static final String TYPE_REQUEST = "request";
    private static final String TYPE_RESPONSE = "response";

    private static final String LEVEL_LIGHT = "light";
    private static final String LEVEL_LENIENT = "lenient";
    private static final String LEVEL_MODERATE = "moderate";
    private static final String LEVEL_STRICT = "strict";

    private static final ConcurrentHashMap<String, ParsedSpec> SPEC_CACHE = new ConcurrentHashMap<>();

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private final Map<String, String> properties;

    public OpenApiValidatorCallout(Map<String, String> properties) {
        this.properties = properties;
    }

    @Override
    public ExecutionResult execute(MessageContext messageContext, ExecutionContext executionContext) {
        try {
            // Get spec content from specfile property
            String specContent = resolveProperty("specfile", messageContext);

            if (specContent == null || specContent.isEmpty()) {
                setError(messageContext, "OpenAPI spec not provided. Set 'specfile' property.");
                return ExecutionResult.ABORT;
            }

            specContent = specContent.trim();

            // Get validation level (default: strict)
            String validationLevel = resolveProperty("validation-level", messageContext);
            if (validationLevel == null || validationLevel.isEmpty()) {
                validationLevel = LEVEL_STRICT;
            }
            validationLevel = validationLevel.toLowerCase().trim();

            if (!LEVEL_LIGHT.equals(validationLevel) &&
                !LEVEL_LENIENT.equals(validationLevel) &&
                !LEVEL_MODERATE.equals(validationLevel) &&
                !LEVEL_STRICT.equals(validationLevel)) {
                validationLevel = LEVEL_STRICT;
            }

            // Get validation type (default: request)
            String validationType = resolveProperty("validation-type", messageContext);
            if (validationType == null || validationType.isEmpty()) {
                validationType = TYPE_REQUEST;
            }
            validationType = validationType.toLowerCase().trim();

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

            List<String> allMessages = new ArrayList<>();
            for (ValidationMessage msg : errors) {
                allMessages.add(msg.getMessage());
            }
            int errorCount = allMessages.size();

            messageContext.setVariable("openapi.validation.errors.all", String.join("; ", allMessages));
            messageContext.setVariable("openapi.validation.errors.count", String.valueOf(errorCount));

            if (LEVEL_LIGHT.equals(validationLevel)) {
                // Light mode: Store errors but don't block
                if (errorCount > 0) {
                    messageContext.setVariable("openapi.validation.error", String.join("; ", allMessages));
                    messageContext.setVariable("openapi.validation.failed", "true");
                } else {
                    messageContext.setVariable("openapi.validation.failed", "false");
                }
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

    private String resolveProperty(String propertyName, MessageContext messageContext) {
        String value = properties.get(propertyName);
        if (value == null) return null;

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

    public static void clearCache() {
        SPEC_CACHE.clear();
    }

    /**
     * Parsed OpenAPI spec with pre-compiled JSON schemas
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
            return schemas.computeIfAbsent(key, k -> extractSchema(path, method, validationType));
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
                JsonNode responses = methodNode.get("responses");
                if (responses == null) return null;

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

            schemaNode = resolveRef(schemaNode);

            if (allowAdditionalProperties && schemaNode.isObject()) {
                schemaNode = makeSchemaLenient(schemaNode);
            }

            SchemaValidatorsConfig config = new SchemaValidatorsConfig();
            config.setTypeLoose(false);

            return factory.getSchema(schemaNode, config);
        }

        private JsonNode resolveRef(JsonNode node) {
            if (node == null) return null;

            if (node.has("$ref")) {
                String ref = node.get("$ref").asText();
                if (ref.startsWith("#/")) {
                    String[] parts = ref.substring(2).split("/");
                    JsonNode current = root;
                    for (String part : parts) {
                        current = current.get(part);
                        if (current == null) return node;
                    }
                    return resolveRef(current);
                }
            }
            return node;
        }

        private JsonNode makeSchemaLenient(JsonNode schema) {
            if (!schema.isObject()) return schema;

            ObjectNode copy = schema.deepCopy();

            if (copy.has("type") && "object".equals(copy.get("type").asText())) {
                copy.put("additionalProperties", true);
            }

            if (!copy.has("type") && copy.has("properties")) {
                copy.put("additionalProperties", true);
            }

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

            if (copy.has("items") && copy.get("items").isObject()) {
                copy.set("items", makeSchemaLenient(resolveRef(copy.get("items"))));
            }

            return copy;
        }

        private boolean pathMatches(String actualPath, String template) {
            String regex = template.replaceAll("\\{[^}]+\\}", "[^/]+");
            return actualPath.matches("^" + regex + "$");
        }
    }
}
