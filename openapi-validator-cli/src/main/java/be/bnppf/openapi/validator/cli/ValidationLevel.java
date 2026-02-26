package be.bnppf.openapi.validator.cli;

/**
 * Validation levels for OpenAPI validation.
 *
 * LIGHT   - All errors are collected but flow is not blocked
 * LENIENT - Additional properties are allowed, other errors block the flow
 * STRICT  - All validation errors block the flow
 */
public enum ValidationLevel {
    LIGHT("light"),
    LENIENT("lenient"),
    STRICT("strict");

    private final String value;

    ValidationLevel(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static ValidationLevel fromString(String text) {
        if (text == null || text.isEmpty()) {
            return STRICT;
        }
        for (ValidationLevel level : ValidationLevel.values()) {
            if (level.value.equalsIgnoreCase(text.trim())) {
                return level;
            }
        }
        return STRICT;
    }
}
