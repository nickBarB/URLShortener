package service;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RandomShortCodeGeneratorTest {
    @Test
    void codeUsesRequestedLengthAndUrlSafeAlphabet() {
        assertTrue(new RandomShortCodeGenerator(6).generate().matches("[0-9A-Za-z]{6}"));
        assertThrows(IllegalArgumentException.class, () -> new RandomShortCodeGenerator(0));
    }
}
