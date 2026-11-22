package model;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShortLinkTest {
    private static final Instant CREATED = Instant.parse("2026-11-08T10:00:00Z");
    private static final Instant EXPIRES = CREATED.plusSeconds(3600);

    private ShortLink link(int limit, int count) {
        return new ShortLink("abc123", "https://example.com", UUID.randomUUID(), CREATED, EXPIRES, limit, count);
    }

    @Test
    void statusUsesExactExpiryBoundaryAndExpiryHasPriorityOverLimit() {
        assertEquals(ShortLinkStatus.ACTIVE, link(5, 0).status(EXPIRES.minusNanos(1)));
        assertEquals(ShortLinkStatus.LIMIT_REACHED, link(5, 5).status(CREATED));
        assertEquals(ShortLinkStatus.EXPIRED, link(5, 0).status(EXPIRES));
        assertEquals(ShortLinkStatus.EXPIRED, link(5, 5).status(EXPIRES));
    }

    @Test
    void updatesKeepOriginalImmutableAndRejectCountersOutsideLimit() {
        ShortLink original = link(5, 0);
        ShortLink updated = original.withClickRegistered().withClickLimit(10);
        assertEquals(0, original.clickCount());
        assertEquals(5, original.clickLimit());
        assertEquals(1, updated.clickCount());
        assertEquals(10, updated.clickLimit());
        assertEquals(original.ownerId(), updated.ownerId());
        assertEquals(original.expiresAt(), updated.expiresAt());
        assertThrows(IllegalArgumentException.class, () -> link(1, 1).withClickRegistered());
        assertThrows(IllegalArgumentException.class, () -> link(5, 2).withClickLimit(1));
    }
}
