package photography.photography.chat;

import java.security.Principal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

import photography.photography.chat.ChatDtos.ErrorEvent;
import photography.photography.chat.ChatDtos.FocusRequest;
import photography.photography.chat.ChatDtos.ReadRequest;
import photography.photography.chat.ChatDtos.SendMessageRequest;
import photography.photography.chat.ChatDtos.TypingRequest;

/**
 * STOMP endpoints (prefix /app). The sender identity ALWAYS comes from the authenticated Principal set during CONNECT -
 * never from the payload - so a client cannot impersonate another user.
 */
@Controller
public class ChatWsController {
    private static final Logger log = LoggerFactory.getLogger(ChatWsController.class);
    private final ChatService chat;

    public ChatWsController(ChatService chat) {
        this.chat = chat;
    }

    /** SEND /app/chat.send  {conversationId, text, clientMessageId}. The result arrives on /user/queue/messages (also to the sender, as the ack). */
    @MessageMapping("/chat.send")
    public void send(@Payload SendMessageRequest request, Principal principal) {
        chat.send(requireUser(principal), request);
    }

    /** SEND /app/chat.read  {conversationId} */
    @MessageMapping("/chat.read")
    public void read(@Payload ReadRequest request, Principal principal) {
        chat.markRead(requireUser(principal), request == null ? null : request.conversationId());
    }

    /** SEND /app/chat.focus  {conversationId, active}: which conversation is open on screen. Opening it also marks it read. */
    @MessageMapping("/chat.focus")
    public void focus(@Payload FocusRequest request, Principal principal, SimpMessageHeaderAccessor headers) {
        if (request != null) {
            chat.focus(requireUser(principal), headers.getSessionId(), request.conversationId(), request.active());
        }
    }

    /** SEND /app/chat.typing  {conversationId, typing}. Relayed to the other participant only; nothing is stored. */
    @MessageMapping("/chat.typing")
    public void typing(@Payload TypingRequest request, Principal principal) {
        if (request != null) {
            chat.typing(requireUser(principal), request.conversationId(), request.typing());
        }
    }

    /** Errors go only to the user who caused them, on /user/queue/errors. */
    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ErrorEvent onError(Exception ex) {
        if (ex instanceof ChatException ce) {
            return new ErrorEvent(ce.code(), ce.getMessage());
        }
        log.warn("Unexpected error handling a STOMP message", ex);
        return new ErrorEvent("INTERNAL", "Something went wrong. Please try again.");
    }

    private static String requireUser(Principal principal) {
        if (principal == null) {
            throw ChatException.forbidden("Not authenticated.");
        }
        return principal.getName();
    }
}