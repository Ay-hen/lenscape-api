package photography.photography.notification;

/** Matches the front-end `NotificationType` union (serialised in lower case). */
public enum NotificationType {
    BOOKING, MESSAGE, REVIEW, SYSTEM, APPROVAL;

    public String wire() {
        return name().toLowerCase();
    }
}
