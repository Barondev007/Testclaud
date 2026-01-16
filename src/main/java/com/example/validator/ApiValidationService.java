package com.example.validator;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;

/**
 * Service class that demonstrates API request/response validation
 * using the Atlassian Swagger Request Validator.
 */
public class ApiValidationService {

    private final OpenApiInteractionValidator validator;

    /**
     * Creates a new validation service with the given validator.
     *
     * @param validator The OpenAPI interaction validator to use
     */
    public ApiValidationService(OpenApiInteractionValidator validator) {
        this.validator = validator;
    }

    /**
     * Creates a validation service with a lenient validator that allows additional properties.
     *
     * @param specPath Path to the OpenAPI specification file
     * @return ApiValidationService with lenient validation
     */
    public static ApiValidationService createLenient(String specPath) {
        return new ApiValidationService(OpenApiValidatorFactory.createLenientValidator(specPath));
    }

    /**
     * Creates a validation service with a strict validator.
     *
     * @param specPath Path to the OpenAPI specification file
     * @return ApiValidationService with strict validation
     */
    public static ApiValidationService createStrict(String specPath) {
        return new ApiValidationService(OpenApiValidatorFactory.createStrictValidator(specPath));
    }

    /**
     * Validates a request against the OpenAPI specification.
     *
     * @param method      HTTP method (GET, POST, PUT, DELETE, etc.)
     * @param path        Request path (e.g., "/users")
     * @param body        Request body (can be null for GET requests)
     * @param contentType Content type of the request body
     * @return ValidationReport containing any validation errors
     */
    public ValidationReport validateRequest(String method, String path, String body, String contentType) {
        SimpleRequest.Builder requestBuilder = new SimpleRequest.Builder(method, path);

        if (body != null && contentType != null) {
            requestBuilder.withBody(body).withContentType(contentType);
        }

        return validator.validateRequest(requestBuilder.build());
    }

    /**
     * Validates a response against the OpenAPI specification.
     *
     * @param method      HTTP method of the original request
     * @param path        Request path
     * @param statusCode  HTTP status code of the response
     * @param body        Response body
     * @param contentType Content type of the response body
     * @return ValidationReport containing any validation errors
     */
    public ValidationReport validateResponse(String method, String path, int statusCode,
                                             String body, String contentType) {
        SimpleResponse.Builder responseBuilder = new SimpleResponse.Builder(statusCode);

        if (body != null && contentType != null) {
            responseBuilder.withBody(body).withContentType(contentType);
        }

        return validator.validateResponse(path, com.atlassian.oai.validator.model.Request.Method.valueOf(method),
                responseBuilder.build());
    }

    /**
     * Validates both request and response in one call.
     *
     * @param method          HTTP method
     * @param path            Request path
     * @param requestBody     Request body
     * @param requestContentType Content type of the request
     * @param responseStatus  HTTP status code of the response
     * @param responseBody    Response body
     * @param responseContentType Content type of the response
     * @return ValidationReport containing any validation errors from both request and response
     */
    public ValidationReport validateInteraction(String method, String path,
                                                 String requestBody, String requestContentType,
                                                 int responseStatus, String responseBody,
                                                 String responseContentType) {
        SimpleRequest.Builder requestBuilder = new SimpleRequest.Builder(method, path);
        if (requestBody != null && requestContentType != null) {
            requestBuilder.withBody(requestBody).withContentType(requestContentType);
        }

        SimpleResponse.Builder responseBuilder = new SimpleResponse.Builder(responseStatus);
        if (responseBody != null && responseContentType != null) {
            responseBuilder.withBody(responseBody).withContentType(responseContentType);
        }

        return validator.validate(requestBuilder.build(), responseBuilder.build());
    }

    /**
     * Checks if a validation report has any errors.
     *
     * @param report The validation report to check
     * @return true if there are errors, false otherwise
     */
    public static boolean hasErrors(ValidationReport report) {
        return report.hasErrors();
    }

    /**
     * Formats a validation report as a human-readable string.
     *
     * @param report The validation report to format
     * @return Formatted string representation of the validation errors
     */
    public static String formatReport(ValidationReport report) {
        if (!report.hasErrors()) {
            return "Validation passed - no errors";
        }

        StringBuilder sb = new StringBuilder("Validation errors:\n");
        report.getMessages().forEach(message -> {
            sb.append("  - [").append(message.getLevel()).append("] ");
            sb.append(message.getKey()).append(": ");
            sb.append(message.getMessage()).append("\n");
        });
        return sb.toString();
    }
}
