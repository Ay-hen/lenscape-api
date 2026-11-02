package photography.photography.chat;

import java.security.Principal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import photography.photography.chat.ChatDtos.ContactView;
import photography.photography.chat.ChatDtos.ConversationView;
import photography.photography.chat.ChatDtos.MessageView;
import photography.photography.chat.ChatDtos.SendMessageRequest;
import photography.photography.chat.ChatDtos.StartConversationRequest;

/**
 * REST side of the chat: history, start-a-conversation, contacts, and a send fallback used when the WebSocket is down.
 * Live delivery happens over STOMP (see {@link ChatWsController}).
 */
@RestController
@RequestMapping("/messages")
public class ChatController {
    private final ChatService chat;

    public ChatController(ChatService chat) {
        this.chat = chat;
    }

    @GetMapping("/conversations")
    public List<ConversationView> conversations(Principal me) {
        return chat.list(me.getName());
    }

    /** Get-or-create, idempotent. */
    @PostMapping("/conversations")
    public ConversationView start(Principal me, @RequestBody StartConversationRequest body) {
        return chat.open(me.getName(), body.username(), body.bookingId());
    }

    /** Older history: {@code before} is the ISO timestamp of the oldest message already loaded. */
    @GetMapping("/conversations/{id}")
    public ConversationView one(Principal me, @PathVariable String id, @RequestParam(required = false) String before, @RequestParam(defaultValue = "50") int limit) {
        Instant cursor = null;
        if (before != null && !before.isBlank()) {
            try {
                cursor = Instant.parse(before);
            } catch (DateTimeParseException e) {
                throw ChatException.invalid("Invalid 'before' timestamp.");
            }
        }
        return chat.get(me.getName(), id, cursor, limit);
    }

    @PostMapping("/conversations/{id}/messages")
    public MessageView send(Principal me, @PathVariable String id, @RequestBody SendMessageRequest body) {
        return chat.send(me.getName(), new SendMessageRequest(id, body.text(), body.clientMessageId()));
    }

    @PostMapping("/conversations/{id}/read")
    public Map<String, Boolean> read(Principal me, @PathVariable String id) {
        chat.markRead(me.getName(), id);
        return Map.of("ok", true);
    }

    /** "Delete chat" for the caller only; the other participant keeps the conversation. */
    @DeleteMapping("/conversations/{id}")
    public Map<String, Boolean> delete(Principal me, @PathVariable String id) {
        chat.delete(me.getName(), id);
        return Map.of("ok", true);
    }

    @GetMapping("/contacts")
    public List<ContactView> contacts(Principal me) {
        return chat.contacts(me.getName());
    }
}