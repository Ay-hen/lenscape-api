package photography.photography.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import photography.photography.chat.ChatDtos.MessageView;
import photography.photography.chat.ChatDtos.SendMessageRequest;
import photography.photography.model.User;
import photography.photography.notification.Notifier;
import photography.photography.respository.ReservationRepo;
import photography.photography.respository.UserRepo;

class ChatServiceTest {
    ConversationRepo conversations = mock(ConversationRepo.class);
    ChatMessageRepo messages = mock(ChatMessageRepo.class);
    UserRepo users = mock(UserRepo.class);
    ReservationRepo reservations = mock(ReservationRepo.class);
    MongoTemplate mongo = mock(MongoTemplate.class);
    SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
    Notifier notifier = mock(Notifier.class);
    ChatService service;

    @BeforeEach
    void setUp() {
        service = new ChatService(conversations, messages, users, reservations, mongo, ws, notifier, 50, 2, 60);
        User alice = User.builder().username("alice").name("Alice").role("client").suspended(false).build();
        User bob = User.builder().username("bob").name("Bob").role("photographer").suspended(false).build();
        User eve = User.builder().username("eve").name("Eve").role("client").suspended(false).build();
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(users.findByUsername("bob")).thenReturn(Optional.of(bob));
        when(users.findByUsername("eve")).thenReturn(Optional.of(eve));
        Conversation c = new Conversation();
        c.setId("c1");
        c.setClientUsername("alice");
        c.setPhotographerUsername("bob");
        when(conversations.findById("c1")).thenReturn(Optional.of(c));
    }

    private static SendMessageRequest req(String text, String id) {
        return new SendMessageRequest("c1", text, id);
    }

    @Test
    void rejectsEmptyAndTooLongText() {
        assertThrows(ChatException.class, () -> service.send("alice", req("   ", "m1")));
        assertThrows(ChatException.class, () -> service.send("alice", req("x".repeat(51), "m2")));
    }

    @Test
    void nonParticipantCannotSend() {
        ChatException e = assertThrows(ChatException.class, () -> service.send("eve", req("hi", "m1")));
        assertEquals("FORBIDDEN", e.code());
        verify(messages, never()).insert(any(ChatMessage.class));
    }

    @Test
    void retryWithSameClientMessageIdDoesNotStoreTwice() {
        ChatMessage stored = ChatMessage.builder().id("x").conversationId("c1").senderUsername("alice").senderRole("client")
                .text("hi").clientMessageId("m1").sentAt(Instant.now()).build();
        when(messages.findByConversationIdAndSenderUsernameAndClientMessageId("c1", "alice", "m1")).thenReturn(Optional.of(stored));
        MessageView v = service.send("alice", req("hi", "m1"));
        assertEquals("x", v.id());
        verify(messages, never()).insert(any(ChatMessage.class));
    }

    @Test
    void rateLimitStopsFlooding() {
        when(messages.findByConversationIdAndSenderUsernameAndClientMessageId(any(), any(), any())).thenReturn(Optional.empty());
        when(messages.insert(any(ChatMessage.class))).thenAnswer(i -> i.getArgument(0));
        service.send("alice", req("one", "a"));
        service.send("alice", req("two", "b"));
        ChatException e = assertThrows(ChatException.class, () -> service.send("alice", req("three", "c")));
        assertEquals("RATE_LIMITED", e.code());
    }
}
