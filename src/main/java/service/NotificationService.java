package service;

import model.Notification;
import repository.NotificationRepository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class NotificationService {
    private final NotificationRepository notifications;
    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    public List<Notification> takePending(UUID ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        return notifications.takeAll(ownerId);
    }
}
