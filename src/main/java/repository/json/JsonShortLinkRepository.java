package repository.json;

import libs.storage.StorageException;
import model.ShortLink;
import repository.ShortLinkRepository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

public final class JsonShortLinkRepository implements ShortLinkRepository {
    private final JsonStorage storage;

    public JsonShortLinkRepository(JsonStorage storage) {
        this.storage = storage;
    }

    @Override
    public boolean add(ShortLink link) {
        return storage.update(state -> {
            if (state.links().stream().anyMatch(existing -> existing.code().equals(link.code()))) {
                return false;
            }
            state.links().add(link);
            return true;
        });
    }

    @Override
    public Optional<ShortLink> findByCode(String code) {
        return storage.read(state -> state.links().stream().filter(link -> link.code().equals(code)).findFirst());
    }

    @Override
    public List<ShortLink> findByOwner(UUID ownerId) {
        return storage.read(state -> state.links().stream().filter(link -> link.ownerId().equals(ownerId))
            .sorted(Comparator.comparing(ShortLink::createdAt).thenComparing(ShortLink::code)).toList());
    }

    @Override
    public List<ShortLink> findExpired(Instant now) {
        return storage.read(state -> state.links().stream().filter(link -> link.isExpired(now))
            .sorted(Comparator.comparing(ShortLink::expiresAt).thenComparing(ShortLink::code)).toList());
    }

    @Override
    public void updateClickCount(String code, int clickCount) {
        update(code, link -> new ShortLink(link.code(), link.originalUrl(), link.ownerId(), link.createdAt(),
            link.expiresAt(), link.clickLimit(), clickCount));
    }

    @Override
    public void updateClickLimit(String code, int clickLimit) {
        update(code, link -> link.withClickLimit(clickLimit));
    }

    @Override
    public boolean delete(String code) {
        return storage.update(state -> state.links().removeIf(link -> link.code().equals(code)));
    }

    private void update(String code, UnaryOperator<ShortLink> operation) {
        storage.update(state -> {
            for (int i = 0; i < state.links().size(); i++) {
                if (state.links().get(i).code().equals(code)) {
                    state.links().set(i, operation.apply(state.links().get(i)));
                    return null;
                }
            }
            throw new StorageException("Ссылка исчезла во время обновления: " + code, null);
        });
    }
}
