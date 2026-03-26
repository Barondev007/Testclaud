package com.example.apigee;

import com.apigee.flow.execution.ExecutionContext;
import com.apigee.flow.execution.ExecutionResult;
import com.apigee.flow.execution.spi.Execution;
import com.apigee.flow.message.MessageContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Apigee Java Callout to check which dependencies are available in the runtime.
 * Deploy this callout and call it to see what's provided by your Apigee version.
 *
 * Output variable: dependency.check.result (JSON format)
 */
public class DependencyCheckerCallout implements Execution {

    private final Map<String, String> properties;

    public DependencyCheckerCallout(Map<String, String> properties) {
        this.properties = properties;
    }

    @Override
    public ExecutionResult execute(MessageContext messageContext, ExecutionContext executionContext) {
        StringBuilder result = new StringBuilder();
        result.append("{\n");

        // ============================================
        // JACKSON
        // ============================================
        result.append("  \"jackson\": {\n");
        checkClass(result, "    ", "ObjectMapper", "com.fasterxml.jackson.databind.ObjectMapper");
        checkClass(result, "    ", "JsonNode", "com.fasterxml.jackson.databind.JsonNode");
        checkClass(result, "    ", "JsonParser", "com.fasterxml.jackson.core.JsonParser");
        checkClass(result, "    ", "JsonGenerator", "com.fasterxml.jackson.core.JsonGenerator");
        checkClass(result, "    ", "YAMLFactory", "com.fasterxml.jackson.dataformat.yaml.YAMLFactory");
        checkClass(result, "    ", "JsonAnnotation", "com.fasterxml.jackson.annotation.JsonProperty");
        checkClassLast(result, "    ", "Jsr310Module", "com.fasterxml.jackson.datatype.jsr310.JavaTimeModule");
        result.append("  },\n");

        // ============================================
        // GOOGLE GUAVA
        // ============================================
        result.append("  \"guava\": {\n");
        checkClass(result, "    ", "ImmutableList", "com.google.common.collect.ImmutableList");
        checkClass(result, "    ", "ImmutableMap", "com.google.common.collect.ImmutableMap");
        checkClass(result, "    ", "Preconditions", "com.google.common.base.Preconditions");
        checkClassLast(result, "    ", "Strings", "com.google.common.base.Strings");
        result.append("  },\n");

        // ============================================
        // GSON
        // ============================================
        result.append("  \"gson\": {\n");
        checkClassLast(result, "    ", "Gson", "com.google.gson.Gson");
        result.append("  },\n");

        // ============================================
        // SLF4J
        // ============================================
        result.append("  \"slf4j\": {\n");
        checkClass(result, "    ", "Logger", "org.slf4j.Logger");
        checkClassLast(result, "    ", "LoggerFactory", "org.slf4j.LoggerFactory");
        result.append("  },\n");

        // ============================================
        // APACHE COMMONS
        // ============================================
        result.append("  \"apache_commons\": {\n");
        checkClass(result, "    ", "StringUtils", "org.apache.commons.lang3.StringUtils");
        checkClass(result, "    ", "IOUtils", "org.apache.commons.io.IOUtils");
        checkClass(result, "    ", "FileUtils", "org.apache.commons.io.FileUtils");
        checkClass(result, "    ", "Base64_Codec", "org.apache.commons.codec.binary.Base64");
        checkClass(result, "    ", "DigestUtils", "org.apache.commons.codec.digest.DigestUtils");
        checkClass(result, "    ", "CollectionUtils", "org.apache.commons.collections4.CollectionUtils");
        checkClassLast(result, "    ", "CollectionUtils3", "org.apache.commons.collections.CollectionUtils");
        result.append("  },\n");

        // ============================================
        // APACHE HTTP CLIENT
        // ============================================
        result.append("  \"apache_http\": {\n");
        checkClass(result, "    ", "HttpClient", "org.apache.http.client.HttpClient");
        checkClass(result, "    ", "CloseableHttpClient", "org.apache.http.impl.client.CloseableHttpClient");
        checkClass(result, "    ", "HttpGet", "org.apache.http.client.methods.HttpGet");
        checkClassLast(result, "    ", "HttpPost", "org.apache.http.client.methods.HttpPost");
        result.append("  },\n");

        // ============================================
        // JODA TIME
        // ============================================
        result.append("  \"joda_time\": {\n");
        checkClass(result, "    ", "DateTime", "org.joda.time.DateTime");
        checkClassLast(result, "    ", "LocalDate", "org.joda.time.LocalDate");
        result.append("  },\n");

        // ============================================
        // SNAKEYAML
        // ============================================
        result.append("  \"snakeyaml\": {\n");
        checkClassLast(result, "    ", "Yaml", "org.yaml.snakeyaml.Yaml");
        result.append("  },\n");

        // ============================================
        // JAVAX
        // ============================================
        result.append("  \"javax\": {\n");
        checkClass(result, "    ", "ServletRequest", "javax.servlet.ServletRequest");
        checkClass(result, "    ", "HttpServletRequest", "javax.servlet.http.HttpServletRequest");
        checkClass(result, "    ", "Validation", "javax.validation.Validation");
        checkClass(result, "    ", "Valid", "javax.validation.Valid");
        checkClassLast(result, "    ", "Inject", "javax.inject.Inject");
        result.append("  },\n");

        // ============================================
        // BOUNCY CASTLE
        // ============================================
        result.append("  \"bouncy_castle\": {\n");
        checkClassLast(result, "    ", "BouncyCastleProvider", "org.bouncycastle.jce.provider.BouncyCastleProvider");
        result.append("  },\n");

        // ============================================
        // JSON LIBRARIES
        // ============================================
        result.append("  \"json_libs\": {\n");
        checkClass(result, "    ", "JSONObject_org", "org.json.JSONObject");
        checkClass(result, "    ", "JsonObject_javax", "javax.json.JsonObject");
        checkClassLast(result, "    ", "JsonValue", "javax.json.JsonValue");
        result.append("  },\n");

        // ============================================
        // XML
        // ============================================
        result.append("  \"xml\": {\n");
        checkClass(result, "    ", "DocumentBuilder", "javax.xml.parsers.DocumentBuilderFactory");
        checkClass(result, "    ", "SAXParser", "javax.xml.parsers.SAXParserFactory");
        checkClassLast(result, "    ", "XPath", "javax.xml.xpath.XPathFactory");
        result.append("  },\n");

        // ============================================
        // SWAGGER / OPENAPI
        // ============================================
        result.append("  \"swagger_openapi\": {\n");
        checkClass(result, "    ", "SwaggerParser_v3", "io.swagger.v3.parser.OpenAPIV3Parser");
        checkClass(result, "    ", "OpenAPI_v3", "io.swagger.v3.oas.models.OpenAPI");
        checkClass(result, "    ", "SwaggerModels_v2", "io.swagger.models.Swagger");
        checkClassLast(result, "    ", "AtlassianValidator", "com.atlassian.oai.validator.OpenApiInteractionValidator");
        result.append("  },\n");

        // ============================================
        // NETWORKNT JSON SCHEMA
        // ============================================
        result.append("  \"networknt\": {\n");
        checkClassLast(result, "    ", "JsonSchemaFactory", "com.networknt.schema.JsonSchemaFactory");
        result.append("  },\n");

        // ============================================
        // REGEX LIBRARIES
        // ============================================
        result.append("  \"regex\": {\n");
        checkClass(result, "    ", "Rhino", "org.mozilla.javascript.Context");
        checkClass(result, "    ", "Joni", "org.joni.Regex");
        checkClassLast(result, "    ", "RE2J", "com.google.re2j.Pattern");
        result.append("  },\n");

        // ============================================
        // JAVA VERSION INFO
        // ============================================
        result.append("  \"java_info\": {\n");
        result.append("    \"version\": \"").append(escape(System.getProperty("java.version"))).append("\",\n");
        result.append("    \"vendor\": \"").append(escape(System.getProperty("java.vendor"))).append("\",\n");
        result.append("    \"runtime\": \"").append(escape(System.getProperty("java.runtime.name"))).append("\"\n");
        result.append("  }\n");

        result.append("}");

        String jsonResult = result.toString();
        messageContext.setVariable("dependency.check.result", jsonResult);

        // Also set as response for easy viewing
        messageContext.setVariable("response.content", jsonResult);
        messageContext.setVariable("response.header.content-type", "application/json");

        return ExecutionResult.SUCCESS;
    }

    private void checkClass(StringBuilder sb, String indent, String name, String className) {
        sb.append(indent).append("\"").append(name).append("\": ");
        sb.append(isClassAvailable(className) ? "true" : "false");
        sb.append(",\n");
    }

    private void checkClassLast(StringBuilder sb, String indent, String name, String className) {
        sb.append(indent).append("\"").append(name).append("\": ");
        sb.append(isClassAvailable(className) ? "true" : "false");
        sb.append("\n");
    }

    private boolean isClassAvailable(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (NoClassDefFoundError e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private String escape(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
