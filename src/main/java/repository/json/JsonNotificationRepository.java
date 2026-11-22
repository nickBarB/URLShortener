package repository.json;

import model.Notification;
import repository.NotificationRepository;

import java.util.List;
import java.util.UUID;

public final class JsonNotificationRepository implements NotificationRepository {
    private final JsonStorage storage;

    public JsonNotificationRepository(JsonStorage storage) {
        this.storage = storage;
    }

    @Override
    public void add(Notification notification) {
        storage.update(state -> {
            state.notifications().add(notification);
            return null;
        });
    }

    @Override
    public List<Notification> takeAll(UUID ownerId) {
        return storage.update(state -> {
            List<Notification> result = state.notifications().stream()
                .filter(notification -> notification.ownerId().equals(ownerId)).toList();
            state.notifications().removeIf(notification -> notification.ownerId().equals(ownerId));
            return result;
        });
    }
}
