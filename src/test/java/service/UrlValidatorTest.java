package service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator();

    @Test
    void acceptsValidHttpsUrl() {
        String url = "https://www.baeldung.com/java-9-http-client";

        assertEquals(url, validator.validate(url));
    }

    @Test
    void trimsSpacesAroundUrl() {
        assertEquals("http://example.com/a?b=1", validator.validate("  http://example.com/a?b=1  "));
    }

    @Test
    void rejectsNull() {
        assertThrows(BusinessException.class, () -> validator.validate(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "example.com", "ftp://example.com",
        "javascript:alert(1)", "https://", "https://exa mple.com"})
    void rejectsInvalidUrls(String url) {
        assertThrows(BusinessException.class, () -> validator.validate(url));
    }

    @Test
    void rejectsTooLongUrl() {
        String longUrl = "https://example.com/" + "a".repeat(2100);

        assertThrows(BusinessException.class, () -> validator.validate(longUrl));
    }
    @ParameterizedTest
    @ValueSource(strings = {"https://example.com:99999/path", "https://example.com:0/path"})
    void rejectsInvalidPorts(String url) {
        assertThrows(BusinessException.class, () -> validator.validate(url));
    }

}
