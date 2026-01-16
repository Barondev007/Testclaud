package com.example.validator;

import com.atlassian.oai.validator.report.ValidationReport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class demonstrating the difference between strict and lenient validation
 * when handling additional properties in request/response bodies.
 */
class AdditionalPropertiesValidationTest {

    private static final String SPEC_PATH = "openapi.yaml";
    private static final String CONTENT_TYPE_JSON = "application/json";

    private static ApiValidationService strictService;
    private static ApiValidationService lenientService;

    @BeforeAll
    static void setUp() {
        // Create both strict and lenient validation services
        strictService = ApiValidationService.createStrict(SPEC_PATH);
        lenientService = ApiValidationService.createLenient(SPEC_PATH);
    }

    @Nested
    @DisplayName("Request Validation Tests")
    class RequestValidationTests {

        @Test
        @DisplayName("Strict validator should REJECT request with additional properties")
        void strictValidator_shouldRejectAdditionalProperties() {
            // Request body with an additional property "nickname" not defined in the spec
            String requestBody = "{\n" +
                    "    \"name\": \"John Doe\",\n" +
                    "    \"email\": \"john.doe@example.com\",\n" +
                    "    \"age\": 30,\n" +
                    "    \"nickname\": \"Johnny\"\n" +
                    "}";

            ValidationReport report = strictService.validateRequest(
                    "POST", "/users", requestBody, CONTENT_TYPE_JSON);

            // Strict validator should report errors for additional properties
            assertTrue(report.hasErrors(),
                    "Strict validator should reject additional properties. Report: "
                            + ApiValidationService.formatReport(report));

            System.out.println("=== Strict Validation (with additional property) ===");
            System.out.println(ApiValidationService.formatReport(report));
        }

        @Test
        @DisplayName("Lenient validator should ACCEPT request with additional properties")
        void lenientValidator_shouldAcceptAdditionalProperties() {
            // Same request body with additional property "nickname"
            String requestBody = "{\n" +
                    "    \"name\": \"John Doe\",\n" +
                    "    \"email\": \"john.doe@example.com\",\n" +
                    "    \"age\": 30,\n" +
                    "    \"nickname\": \"Johnny\",\n" +
                    "    \"customField\": \"any value\",\n" +
                    "    \"anotherExtra\": 12345\n" +
                    "}";

            ValidationReport report = lenientService.validateRequest(
                    "POST", "/users", requestBody, CONTENT_TYPE_JSON);

            // Lenient validator should NOT report errors for additional properties
            assertFalse(report.hasErrors(),
                    "Lenient validator should accept additional properties. Report: "
                            + ApiValidationService.formatReport(report));

            System.out.println("=== Lenient Validation (with additional properties) ===");
            System.out.println(ApiValidationService.formatReport(report));
        }

        @Test
        @DisplayName("Both validators should accept valid request without additional properties")
        void bothValidators_shouldAcceptValidRequest() {
            // Valid request body without additional properties
            String requestBody = "{\n" +
                    "    \"name\": \"John Doe\",\n" +
                    "    \"email\": \"john.doe@example.com\",\n" +
                    "    \"age\": 30\n" +
                    "}";

            ValidationReport strictReport = strictService.validateRequest(
                    "POST", "/users", requestBody, CONTENT_TYPE_JSON);
            ValidationReport lenientReport = lenientService.validateRequest(
                    "POST", "/users", requestBody, CONTENT_TYPE_JSON);

            assertFalse(strictReport.hasErrors(),
                    "Strict validator should accept valid request: "
                            + ApiValidationService.formatReport(strictReport));
            assertFalse(lenientReport.hasErrors(),
                    "Lenient validator should accept valid request: "
                            + ApiValidationService.formatReport(lenientReport));

            System.out.println("=== Both validators accept valid request ===");
            System.out.println("Strict: " + ApiValidationService.formatReport(strictReport));
            System.out.println("Lenient: " + ApiValidationService.formatReport(lenientReport));
        }

        @Test
        @DisplayName("Both validators should reject request missing required fields")
        void bothValidators_shouldRejectMissingRequiredFields() {
            // Request body missing required "email" field
            String requestBody = "{\n" +
                    "    \"name\": \"John Doe\"\n" +
                    "}";

            ValidationReport strictReport = strictService.validateRequest(
                    "POST", "/users", requestBody, CONTENT_TYPE_JSON);
            ValidationReport lenientReport = lenientService.validateRequest(
                    "POST", "/users", requestBody, CONTENT_TYPE_JSON);

            // Both should reject due to missing required field
            assertTrue(strictReport.hasErrors(),
                    "Strict validator should reject missing required field");
            assertTrue(lenientReport.hasErrors(),
                    "Lenient validator should still reject missing required field");

            System.out.println("=== Missing required field validation ===");
            System.out.println("Strict: " + ApiValidationService.formatReport(strictReport));
            System.out.println("Lenient: " + ApiValidationService.formatReport(lenientReport));
        }
    }

    @Nested
    @DisplayName("Response Validation Tests")
    class ResponseValidationTests {

        @Test
        @DisplayName("Strict validator should REJECT response with additional properties")
        void strictValidator_shouldRejectResponseWithAdditionalProperties() {
            // Response body with additional properties not defined in the spec
            String responseBody = "{\n" +
                    "    \"id\": \"123e4567-e89b-12d3-a456-426614174000\",\n" +
                    "    \"name\": \"John Doe\",\n" +
                    "    \"email\": \"john.doe@example.com\",\n" +
                    "    \"age\": 30,\n" +
                    "    \"createdAt\": \"2024-01-15T10:30:00Z\",\n" +
                    "    \"internalField\": \"secret\",\n" +
                    "    \"debugInfo\": {\"server\": \"prod-1\"}\n" +
                    "}";

            ValidationReport report = strictService.validateResponse(
                    "POST", "/users", 201, responseBody, CONTENT_TYPE_JSON);

            assertTrue(report.hasErrors(),
                    "Strict validator should reject response with additional properties. Report: "
                            + ApiValidationService.formatReport(report));

            System.out.println("=== Strict Response Validation (with additional properties) ===");
            System.out.println(ApiValidationService.formatReport(report));
        }

        @Test
        @DisplayName("Lenient validator should ACCEPT response with additional properties")
        void lenientValidator_shouldAcceptResponseWithAdditionalProperties() {
            // Same response body with additional properties
            String responseBody = "{\n" +
                    "    \"id\": \"123e4567-e89b-12d3-a456-426614174000\",\n" +
                    "    \"name\": \"John Doe\",\n" +
                    "    \"email\": \"john.doe@example.com\",\n" +
                    "    \"age\": 30,\n" +
                    "    \"createdAt\": \"2024-01-15T10:30:00Z\",\n" +
                    "    \"internalField\": \"secret\",\n" +
                    "    \"debugInfo\": {\"server\": \"prod-1\"},\n" +
                    "    \"extraData\": [1, 2, 3]\n" +
                    "}";

            ValidationReport report = lenientService.validateResponse(
                    "POST", "/users", 201, responseBody, CONTENT_TYPE_JSON);

            assertFalse(report.hasErrors(),
                    "Lenient validator should accept response with additional properties. Report: "
                            + ApiValidationService.formatReport(report));

            System.out.println("=== Lenient Response Validation (with additional properties) ===");
            System.out.println(ApiValidationService.formatReport(report));
        }
    }

    @Nested
    @DisplayName("Full Interaction Tests")
    class FullInteractionTests {

        @Test
        @DisplayName("Lenient validator should accept full interaction with additional properties")
        void lenientValidator_shouldAcceptFullInteractionWithAdditionalProperties() {
            String requestBody = "{\n" +
                    "    \"name\": \"Jane Smith\",\n" +
                    "    \"email\": \"jane.smith@example.com\",\n" +
                    "    \"department\": \"Engineering\",\n" +
                    "    \"metadata\": {\"source\": \"api\"}\n" +
                    "}";

            String responseBody = "{\n" +
                    "    \"id\": \"456e7890-e89b-12d3-a456-426614174001\",\n" +
                    "    \"name\": \"Jane Smith\",\n" +
                    "    \"email\": \"jane.smith@example.com\",\n" +
                    "    \"createdAt\": \"2024-01-16T14:00:00Z\",\n" +
                    "    \"lastModified\": \"2024-01-16T14:00:00Z\",\n" +
                    "    \"version\": 1\n" +
                    "}";

            ValidationReport report = lenientService.validateInteraction(
                    "POST", "/users",
                    requestBody, CONTENT_TYPE_JSON,
                    201, responseBody, CONTENT_TYPE_JSON);

            assertFalse(report.hasErrors(),
                    "Lenient validator should accept full interaction with additional properties. Report: "
                            + ApiValidationService.formatReport(report));

            System.out.println("=== Full Interaction Validation (lenient) ===");
            System.out.println(ApiValidationService.formatReport(report));
        }
    }
}
