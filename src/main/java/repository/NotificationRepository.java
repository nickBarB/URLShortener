package repository;

import model.Notification;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository {

    void add(Notification notification);

    List<Notification> takeAll(UUID ownerId);
}
