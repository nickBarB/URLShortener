package repository;

import model.ShortLink;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShortLinkRepository {
    boolean add(ShortLink link);
    Optional<ShortLink> findByCode(String code);
    List<ShortLink> findByOwner(UUID ownerId);
    List<ShortLink> findExpired(Instant now);
    void updateClickCount(String code, int clickCount);
    void updateClickLimit(String code, int clickLimit);
    boolean delete(String code);
}
