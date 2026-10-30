package photography.photography.chat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Wire contracts. They deliberately mirror the Angular models `ChatMessage` and `Conversation`. */
public final class ChatDtos {
    private ChatDtos() {
    }

    /** STOMP: SEND /app/chat.send */
    public record SendMessageRequest(String conversationId, String text, String clientMessageId) {
    }

    /** STOMP: SEND /app/chat.read */
    public record ReadRequest(String conversationId) {
    }

    /** STOMP: SEND /app/chat.typing */
    public record TypingRequest(String conversationId, boolean typing) {
    }

    /** REST: POST /messages/conversations */
    public record StartConversationRequest(String username, String bookingId) {
    }

    public record MessageView(String id, String senderId, String senderRole, String text, Instant at, String clientMessageId) {
    }

    public record ConversationView(String id, String bookingId, String customerId, String customerName, String photographerId,
                                   String photographerName, List<MessageView> messages, Map<String, Integer> unreadFor, Instant updatedAt) {
    }

    public record ContactView(String username, String name, String role, String city) {
    }

    /**
     * Pushed on /user/queue/messages.
     * type = "message" (new message, also echoed to the sender as the delivery ack), "read" (the other side read the conversation),
     * "typing" / "typing_stopped" (the other participant is, or stopped, typing - sent to the other participant only, never stored).
     */
    public record ChatEvent(String type, String conversationId, MessageView message, Map<String, Integer> unreadFor, String actor, Instant at) {
    }

    /** Pushed on /user/queue/errors */
    public record ErrorEvent(String code, String message) {
    }
}
