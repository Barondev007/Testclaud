package com.axway.apim.openapi.validator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ValidationLevelTest {

    @Test
    void testEnumValues() {
        assertEquals(3, ValidationLevel.values().length);
        assertNotNull(ValidationLevel.LIGHT);
        assertNotNull(ValidationLevel.LENIENT);
        assertNotNull(ValidationLevel.STRICT);
    }

    @Test
    void testGetValue() {
        assertEquals("light", ValidationLevel.LIGHT.getValue());
        assertEquals("lenient", ValidationLevel.LENIENT.getValue());
        assertEquals("strict", ValidationLevel.STRICT.getValue());
    }

    @ParameterizedTest
    @CsvSource({
        "light, LIGHT",
        "LIGHT, LIGHT",
        "Light, LIGHT",
        "lenient, LENIENT",
        "LENIENT, LENIENT",
        "Lenient, LENIENT",
        "strict, STRICT",
        "STRICT, STRICT",
        "Strict, STRICT"
    })
    void testFromString(String input, ValidationLevel expected) {
        assertEquals(expected, ValidationLevel.fromString(input));
    }

    @ParameterizedTest
    @CsvSource({
        "  light  , LIGHT",
        "  lenient  , LENIENT",
        "  strict  , STRICT"
    })
    void testFromStringWithWhitespace(String input, ValidationLevel expected) {
        assertEquals(expected, ValidationLevel.fromString(input));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void testFromStringNullOrEmpty(String input) {
        assertEquals(ValidationLevel.STRICT, ValidationLevel.fromString(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "unknown", "moderate", "123", "light!", " "})
    void testFromStringInvalidDefaultsToStrict(String input) {
        assertEquals(ValidationLevel.STRICT, ValidationLevel.fromString(input));
    }
}
