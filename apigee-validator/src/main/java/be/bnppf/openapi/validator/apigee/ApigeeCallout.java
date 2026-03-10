package be.bnppf.openapi.validator.apigee;

import be.bnppf.openapi.validator.apigee.ApigeeOpenApiValidator.ValidationLevel;
import be.bnppf.openapi.validator.apigee.ApigeeOpenApiValidator.ValidationResult;

import java.util.*;

/**
 * Example Apigee Java Callout for OpenAPI validation.
 *
 * To use this in Apigee:
 * 1. Build the shaded JAR: mvn clean package
 * 2. Upload the JAR to your Apigee environment
 * 3. Create a Java Callout policy referencing this class
 *
 * Policy configuration example:
 * <pre>
 * {@code
 * <JavaCallout name="ValidateRequest">
 *     <Properties>
 *         <Property name="spec">{openapi.spec}</Property>
 *         <Property name="level">LENIENT</Property>
 *     </Properties>
 *     <ClassName>be.bnppf.openapi.validator.apigee.ApigeeCallout</ClassName>
 *     <ResourceURL>java://openapi-validator-apigee-1.0.0.jar</ResourceURL>
 * </JavaCallout>
 * }
 * </pre>
 *
 * NOTE: This is a reference implementation. You'll need to adapt it
 * to work with the actual Apigee Java Callout API (com.apigee.flow.*).
 */
public class ApigeeCallout {

    /**
     * Validate a request using flow variables from Apigee context.
     *
     * @param specContent OpenAPI specification content
     * @param levelStr Validation level (STRICT, LENIENT, LIGHT)
     * @param method HTTP method
     * @param path Request path
     * @param body Request body
     * @param queryParams Query parameters
     * @param headers Request headers
     * @return Validation result as a Map (for easy JavaScript access)
     */
    public static Map<String, Object> validateRequest(
            String specContent,
            String levelStr,
            String method,
            String path,
            String body,
            Map<String, String> queryParams,
            Map<String, String> headers) {

        try {
            // Convert single-value maps to multi-value maps
            Map<String, List<String>> queryMultiMap = toMultiValueMap(queryParams);
            Map<String, List<String>> headerMultiMap = toMultiValueMap(headers);

            // Get validator instance
            ApigeeOpenApiValidator validator = ApigeeOpenApiValidator.getInstance(specContent, levelStr);

            // Validate request
            ValidationResult result = validator.validateRequest(body, method, path, queryMultiMap, headerMultiMap);

            return result.toMap();

        } catch (Exception e) {
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("valid", false);
            errorResult.put("blocked", true);
            errorResult.put("errorCount", 1);
            errorResult.put("errors", Collections.singletonList("Validation failed: " + e.getMessage()));
            errorResult.put("warnings", Collections.emptyList());
            errorResult.put("infos", Collections.emptyList());
            return errorResult;
        }
    }

    /**
     * Validate a response.
     */
    public static Map<String, Object> validateResponse(
            String specContent,
            String levelStr,
            String method,
            String path,
            int statusCode,
            String body,
            Map<String, String> headers) {

        try {
            Map<String, List<String>> headerMultiMap = toMultiValueMap(headers);

            ApigeeOpenApiValidator validator = ApigeeOpenApiValidator.getInstance(specContent, levelStr);
            ValidationResult result = validator.validateResponse(body, method, path, statusCode, headerMultiMap);

            return result.toMap();

        } catch (Exception e) {
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("valid", false);
            errorResult.put("blocked", true);
            errorResult.put("errorCount", 1);
            errorResult.put("errors", Collections.singletonList("Validation failed: " + e.getMessage()));
            return errorResult;
        }
    }

    /**
     * Simple request validation (for basic use cases).
     */
    public static String validateRequestSimple(
            String specContent,
            String method,
            String path,
            String body) {

        ApigeeOpenApiValidator validator = ApigeeOpenApiValidator.getInstance(specContent, "LENIENT");
        ValidationResult result = validator.validateRequest(body, method, path);
        return result.toJson();
    }

    /**
     * Check if a spec is valid.
     */
    public static boolean isValidSpec(String specContent) {
        return ApigeeOpenApiValidator.isValidSpec(specContent);
    }

    /**
     * Convert single-value map to multi-value map.
     */
    private static Map<String, List<String>> toMultiValueMap(Map<String, String> singleValueMap) {
        if (singleValueMap == null) {
            return new LinkedHashMap<>();
        }
        Map<String, List<String>> multiValueMap = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : singleValueMap.entrySet()) {
            multiValueMap.put(entry.getKey(), Collections.singletonList(entry.getValue()));
        }
        return multiValueMap;
    }
}
