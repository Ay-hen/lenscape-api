package photography.photography.chat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Which conversation each live WebSocket session has open on screen. In memory (per instance), like the rest of the chat plumbing.
 * A message that arrives while its recipient is "viewing" the conversation is read at once and raises no notification.
 */
@Component
public class ChatPresence {
    private record Viewing(String username, String conversationId) {
    }

    private final Map<String, Viewing> bySession = new ConcurrentHashMap<>();

    /** conversationId == null removes the session's entry. */
    public void set(String sessionId, String username, String conversationId) {
        if (conversationId == null) {
            bySession.remove(sessionId);
        } else {
            bySession.put(sessionId, new Viewing(username, conversationId));
        }
    }

    public boolean isViewing(String username, String conversationId) {
        return bySession.values().stream().anyMatch(v -> v.username().equals(username) && v.conversationId().equals(conversationId));
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent e) {
        bySession.remove(e.getSessionId());
    }
}