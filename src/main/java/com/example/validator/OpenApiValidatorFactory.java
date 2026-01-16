package com.example.validator;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;

/**
 * Factory class for creating OpenAPI validators with custom configurations.
 *
 * This factory provides methods to create validators that allow additional properties
 * in request and response bodies, making the validation less strict.
 */
public class OpenApiValidatorFactory {

    /**
     * Message key for additional properties validation errors in request body.
     */
    private static final String REQUEST_ADDITIONAL_PROPERTIES =
            "validation.request.body.schema.additionalProperties";

    /**
     * Message key for additional properties validation errors in response body.
     */
    private static final String RESPONSE_ADDITIONAL_PROPERTIES =
            "validation.response.body.schema.additionalProperties";

    /**
     * Creates a validator that allows additional properties in both request and response bodies.
     * This is useful when your API clients may send extra fields that are not defined in the spec,
     * or when your backend returns additional fields.
     *
     * @param specPath Path to the OpenAPI specification file (classpath or file system)
     * @return OpenApiInteractionValidator configured to allow additional properties
     */
    public static OpenApiInteractionValidator createLenientValidator(String specPath) {
        LevelResolver levelResolver = LevelResolver.create()
                // Ignore additional properties errors for request body
                .withLevel(REQUEST_ADDITIONAL_PROPERTIES, ValidationReport.Level.IGNORE)
                // Ignore additional properties errors for response body
                .withLevel(RESPONSE_ADDITIONAL_PROPERTIES, ValidationReport.Level.IGNORE)
                .build();

        return OpenApiInteractionValidator
                .createForSpecificationUrl(specPath)
                .withLevelResolver(levelResolver)
                .build();
    }

    /**
     * Creates a validator that allows additional properties only in request bodies.
     * Response bodies will still be strictly validated.
     *
     * @param specPath Path to the OpenAPI specification file
     * @return OpenApiInteractionValidator configured to allow additional properties in requests only
     */
    public static OpenApiInteractionValidator createLenientRequestValidator(String specPath) {
        LevelResolver levelResolver = LevelResolver.create()
                .withLevel(REQUEST_ADDITIONAL_PROPERTIES, ValidationReport.Level.IGNORE)
                .build();

        return OpenApiInteractionValidator
                .createForSpecificationUrl(specPath)
                .withLevelResolver(levelResolver)
                .build();
    }

    /**
     * Creates a validator that allows additional properties only in response bodies.
     * Request bodies will still be strictly validated.
     *
     * @param specPath Path to the OpenAPI specification file
     * @return OpenApiInteractionValidator configured to allow additional properties in responses only
     */
    public static OpenApiInteractionValidator createLenientResponseValidator(String specPath) {
        LevelResolver levelResolver = LevelResolver.create()
                .withLevel(RESPONSE_ADDITIONAL_PROPERTIES, ValidationReport.Level.IGNORE)
                .build();

        return OpenApiInteractionValidator
                .createForSpecificationUrl(specPath)
                .withLevelResolver(levelResolver)
                .build();
    }

    /**
     * Creates a strict validator that does not allow additional properties.
     * This is the default behavior.
     *
     * @param specPath Path to the OpenAPI specification file
     * @return OpenApiInteractionValidator with strict validation
     */
    public static OpenApiInteractionValidator createStrictValidator(String specPath) {
        return OpenApiInteractionValidator
                .createForSpecificationUrl(specPath)
                .build();
    }

    /**
     * Creates a highly lenient validator that ignores multiple validation issues.
     * Use this when you want minimal validation.
     *
     * @param specPath Path to the OpenAPI specification file
     * @return OpenApiInteractionValidator with minimal validation
     */
    public static OpenApiInteractionValidator createMinimalValidator(String specPath) {
        LevelResolver levelResolver = LevelResolver.create()
                // Ignore additional properties
                .withLevel(REQUEST_ADDITIONAL_PROPERTIES, ValidationReport.Level.IGNORE)
                .withLevel(RESPONSE_ADDITIONAL_PROPERTIES, ValidationReport.Level.IGNORE)
                // Optionally ignore other common validation issues
                .withLevel("validation.request.body.schema.required", ValidationReport.Level.WARN)
                .withLevel("validation.response.body.schema.required", ValidationReport.Level.WARN)
                .build();

        return OpenApiInteractionValidator
                .createForSpecificationUrl(specPath)
                .withLevelResolver(levelResolver)
                .build();
    }
}
