package photography.photography.chat;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import photography.photography.chat.ChatDtos.ChatEvent;
import photography.photography.chat.ChatDtos.ContactView;
import photography.photography.chat.ChatDtos.ConversationView;
import photography.photography.chat.ChatDtos.MessageView;
import photography.photography.chat.ChatDtos.SendMessageRequest;
import photography.photography.model.User;
import photography.photography.notification.Notification;
import photography.photography.notification.NotificationType;
import photography.photography.notification.Notifier;
import photography.photography.respository.ReservationRepo;
import photography.photography.respository.UserRepo;

/**
 * All chat rules live here so they are identical for REST and WebSocket entry points:
 * <ul>
 *   <li>only a customer and a photographer can talk, both accounts must be active;</li>
 *   <li>a customer may start a chat with any photographer; a photographer only with a customer they share a reservation with
 *       (prevents unsolicited messages to strangers);</li>
 *   <li>only the two participants can read or write a conversation (non-participants get "not found");</li>
 *   <li>a client-generated message id makes retries idempotent; sending is rate limited per user.</li>
 * </ul>
 */
@Service
public class ChatService {
    public static final String USER_QUEUE = "/queue/messages";
    static final int PAGE = 50;

    private final ConversationRepo conversations;
    private final ChatMessageRepo messages;
    private final UserRepo users;
    private final ReservationRepo reservations;
    /** Bookings of the new booking module also entitle a photographer to message their customer. Optional so the class stays easy to construct in tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private photography.photography.respository.BookingRepo bookingRepo;
    /** Who has which chat open right now. Optional so the class stays easy to construct in tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ChatPresence presence;
    private final MongoTemplate mongo;
    private final SimpMessagingTemplate ws;
    private final Notifier notifier;
    private final int maxLength;
    private final RateLimiter limiter;

    public ChatService(ConversationRepo conversations, ChatMessageRepo messages, UserRepo users, ReservationRepo reservations,
                       MongoTemplate mongo, SimpMessagingTemplate ws, Notifier notifier,
                       @Value("${app.chat.max-message-length:2000}") int maxLength,
                       @Value("${app.chat.rate-limit.messages:30}") int rateMessages,
                       @Value("${app.chat.rate-limit.window-seconds:60}") int rateWindowSeconds) {
        this.conversations = conversations;
        this.messages = messages;
        this.users = users;
        this.reservations = reservations;
        this.mongo = mongo;
        this.ws = ws;
        this.notifier = notifier;
        this.maxLength = maxLength;
        this.limiter = new RateLimiter(rateMessages, rateWindowSeconds * 1000L);
    }

    // ------------------------------------------------------------------ conversations

    public ConversationView open(String me, String otherUsername, String bookingId) {
        if (otherUsername == null || otherUsername.isBlank() || otherUsername.equals(me)) {
            throw ChatException.invalid("Choose who you want to message.");
        }
        User self = activeUser(me);
        User other = users.findByUsername(otherUsername).orElseThrow(() -> ChatException.notFound("User not found."));
        requireActive(other);
        boolean selfIsClient = "client".equals(self.getRole());
        boolean selfIsPhotographer = "photographer".equals(self.getRole());
        if (!(selfIsClient && "photographer".equals(other.getRole())) && !(selfIsPhotographer && "client".equals(other.getRole()))) {
            throw ChatException.forbidden("Messaging is only available between a customer and a photographer.");
        }
        User client = selfIsClient ? self : other;
        User photographer = selfIsClient ? other : self;

        Conversation existing = conversations.findByClientUsernameAndPhotographerUsername(client.getUsername(), photographer.getUsername()).orElse(null);
        if (existing != null) {
            if (existing.hiddenFor(me)) { // re-opening a chat I deleted: it returns, without the old history
                boolean iAmClient = me.equals(existing.getClientUsername());
                mongo.updateFirst(Query.query(Criteria.where("_id").is(existing.getId())), new Update().set(iAmClient ? "clientHidden" : "photographerHidden", false), Conversation.class);
                if (iAmClient) {
                    existing.setClientHidden(false);
                } else {
                    existing.setPhotographerHidden(false);
                }
            }
            return view(existing, latest(existing, me));
        }
        if (selfIsPhotographer && !reservations.existsByUserAndPhotographer(client, photographer)
                && (bookingRepo == null || !bookingRepo.existsByCustomerIdAndPhotographerId(client.getUsername(), photographer.getUsername()))) {
            throw ChatException.forbidden("You can message a customer after they have booked with you.");
        }
        Conversation created = Conversation.builder().clientUsername(client.getUsername()).clientName(displayName(client))
                .photographerUsername(photographer.getUsername()).photographerName(displayName(photographer))
                .bookingId(blankToNull(bookingId)).lastMessageText("").updatedAt(Instant.now()).build();
        try {
            created = conversations.insert(created);
        } catch (DuplicateKeyException race) { // both sides started at the same instant
            created = conversations.findByClientUsernameAndPhotographerUsername(client.getUsername(), photographer.getUsername()).orElseThrow();
        }
        return view(created, List.of());
    }

    public List<ConversationView> list(String me) {
        List<ConversationView> out = new ArrayList<>();
        for (Conversation c : conversations.findByClientUsernameOrPhotographerUsernameOrderByUpdatedAtDesc(me, me)) {
            if (c.hiddenFor(me)) {
                continue;
            }
            if (out.size() == 50) {
                break;
            }
            out.add(view(c, latest(c, me)));
        }
        return out;
    }

    /** One page of history, newest {@code limit} messages older than {@code before}; returned oldest-first. */
    public ConversationView get(String me, String id, Instant before, int limit) {
        Conversation c = participantConversation(me, id);
        int size = Math.max(1, Math.min(limit, 100));
        List<ChatMessage> page = before == null
                ? messages.findByConversationIdOrderBySentAtDesc(id, PageRequest.of(0, size))
                : messages.findByConversationIdAndSentAtBeforeOrderBySentAtDesc(id, before, PageRequest.of(0, size));
        List<ChatMessage> asc = new ArrayList<>(page);
        Collections.reverse(asc);
        Instant cleared = c.clearedAtFor(me);
        if (cleared != null) {
            asc.removeIf(m -> !m.getSentAt().isAfter(cleared));
        }
        return view(c, asc);
    }

    public List<ContactView> contacts(String me) {
        User self = activeUser(me);
        List<ContactView> out = new ArrayList<>();
        if ("client".equals(self.getRole())) {
            for (User p : users.findByRole("photographer")) {
                if (!Boolean.TRUE.equals(p.getSuspended())) {
                    out.add(new ContactView(p.getUsername(), displayName(p), "photographer", p.getCity()));
                }
            }
        } else if ("photographer".equals(self.getRole())) {
            Map<String, ContactView> byName = new LinkedHashMap<>();
            for (Conversation c : conversations.findByClientUsernameOrPhotographerUsernameOrderByUpdatedAtDesc(me, me)) {
                byName.putIfAbsent(c.getClientUsername(), new ContactView(c.getClientUsername(), c.getClientName(), "client", null));
            }
            if (self.getReservations() != null) {
                self.getReservations().stream().filter(r -> r != null && r.getUser() != null).forEach(r ->
                        byName.putIfAbsent(r.getUser().getUsername(), new ContactView(r.getUser().getUsername(), displayName(r.getUser()), "client", r.getUser().getCity())));
            }
            out.addAll(byName.values());
        }
        return out;
    }

    // ------------------------------------------------------------------ messages

    public MessageView send(String me, SendMessageRequest req) {
        if (req == null || req.conversationId() == null) {
            throw ChatException.invalid("Missing conversation.");
        }
        String text = req.text() == null ? "" : req.text().strip();
        if (text.isEmpty()) {
            throw ChatException.invalid("Write something first.");
        }
        if (text.length() > maxLength) {
            throw ChatException.invalid("Messages are limited to " + maxLength + " characters.");
        }
        text = text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", ""); // strip control characters, keep line breaks
        String clientMessageId = req.clientMessageId() == null || req.clientMessageId().isBlank() ? UUID.randomUUID().toString() : req.clientMessageId();
        if (clientMessageId.length() > 80) {
            throw ChatException.invalid("Invalid message id.");
        }

        User sender = activeUser(me);
        Conversation c = participantConversation(me, req.conversationId());
        User recipient = users.findByUsername(c.otherOf(me)).orElseThrow(() -> ChatException.notFound("User not found."));
        requireActive(recipient);

        ChatMessage duplicate = messages.findByConversationIdAndSenderUsernameAndClientMessageId(c.getId(), me, clientMessageId).orElse(null);
        if (duplicate != null) {
            return toView(duplicate); // retry of a message we already stored: same answer, no second copy
        }
        limiter.check(me);

        ChatMessage saved;
        try {
            saved = messages.insert(ChatMessage.builder().conversationId(c.getId()).senderUsername(me).senderRole(sender.getRole())
                    .text(text).clientMessageId(clientMessageId).sentAt(Instant.now()).build());
        } catch (DuplicateKeyException race) {
            return toView(messages.findByConversationIdAndSenderUsernameAndClientMessageId(c.getId(), me, clientMessageId).orElseThrow());
        }

        boolean recipientIsClient = recipient.getUsername().equals(c.getClientUsername());
        boolean viewing = presence != null && presence.isViewing(recipient.getUsername(), c.getId());
        Update upd = new Update().set("lastMessageText", preview(text)).set("updatedAt", saved.getSentAt())
                .set("clientHidden", false).set("photographerHidden", false); // a new message revives a deleted chat
        if (!viewing) {
            upd.inc(recipientIsClient ? "clientUnread" : "photographerUnread", 1); // viewing = read instantly, nothing to count
        }
        Conversation before = mongo.findAndModify(Query.query(Criteria.where("_id").is(c.getId())), upd,
                FindAndModifyOptions.options().returnNew(false), Conversation.class);
        int unreadBefore = before == null ? 0 : (recipientIsClient ? before.getClientUnread() : before.getPhotographerUnread());
        Conversation fresh = conversations.findById(c.getId()).orElse(c);

        ChatEvent event = new ChatEvent("message", c.getId(), toView(saved), unread(fresh), me, saved.getSentAt());
        ws.convertAndSendToUser(c.getClientUsername(), USER_QUEUE, event);
        ws.convertAndSendToUser(c.getPhotographerUsername(), USER_QUEUE, event);

        // Only the FIRST unread message of a burst notifies; nothing is raised while the recipient has the chat open.
        if (!viewing && unreadBefore == 0) {
            notifier.notify(recipient.getUsername(), NotificationType.MESSAGE, "New message from " + displayName(sender), preview(text),
                    recipientIsClient ? "/account/messages" : "/studio/messages", "conversation:" + c.getId());
        }
        return toView(saved);
    }

    /** Live "is typing" signal. Delivered to the other participant only (never echoed back, never stored). Failures are ignored: it is cosmetic. */
    public void typing(String me, String conversationId, boolean typing) {
        if (conversationId == null) {
            return;
        }
        Conversation c = conversations.findById(conversationId).orElse(null);
        if (c == null || !c.hasParticipant(me)) {
            return;
        }
        if (!limiter.allowTyping(me)) {
            return;
        }
        ws.convertAndSendToUser(c.otherOf(me), USER_QUEUE, new ChatEvent(typing ? "typing" : "typing_stopped", c.getId(), null, null, me, Instant.now()));
    }

    public void markRead(String me, String conversationId) {
        Conversation c = participantConversation(me, conversationId);
        boolean iAmClient = me.equals(c.getClientUsername());
        mongo.updateFirst(Query.query(Criteria.where("_id").is(c.getId())), new Update().set(iAmClient ? "clientUnread" : "photographerUnread", 0), Conversation.class);
        // reading the chat also closes its "new message" notification, so the next message can notify again
        mongo.updateMulti(Query.query(Criteria.where("userId").is(me).and("groupKey").is("conversation:" + c.getId()).and("read").is(false)),
                Update.update("read", true), Notification.class);
        Conversation fresh = conversations.findById(c.getId()).orElse(c);
        ChatEvent event = new ChatEvent("read", c.getId(), null, unread(fresh), me, Instant.now());
        ws.convertAndSendToUser(c.getClientUsername(), USER_QUEUE, event);
        ws.convertAndSendToUser(c.getPhotographerUsername(), USER_QUEUE, event);
    }

    /** The client says which chat is open on screen (or that it no longer is). Opening it also marks it read. */
    public void focus(String me, String sessionId, String conversationId, boolean active) {
        if (conversationId == null || sessionId == null || presence == null) {
            return;
        }
        Conversation c = participantConversation(me, conversationId);
        presence.set(sessionId, me, active ? c.getId() : null);
        if (active) {
            markRead(me, c.getId());
        }
    }

    /** "Delete chat" for ONE user. The other participant keeps everything; once both deleted, the data is purged. */
    public void delete(String me, String id) {
        Conversation c = participantConversation(me, id);
        boolean iAmClient = me.equals(c.getClientUsername());
        Instant now = Instant.now();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(c.getId())), new Update()
                .set(iAmClient ? "clientHidden" : "photographerHidden", true)
                .set(iAmClient ? "clientClearedAt" : "photographerClearedAt", now)
                .set(iAmClient ? "clientUnread" : "photographerUnread", 0), Conversation.class);
        mongo.remove(Query.query(Criteria.where("userId").is(me).and("groupKey").is("conversation:" + c.getId())), Notification.class);
        Conversation fresh = conversations.findById(c.getId()).orElse(c);
        if (fresh.isClientHidden() && fresh.isPhotographerHidden()) {
            messages.deleteByConversationId(c.getId());
            conversations.deleteById(c.getId());
        }
        ws.convertAndSendToUser(me, USER_QUEUE, new ChatEvent("deleted", c.getId(), null, null, me, now));
    }

    // ------------------------------------------------------------------ helpers

    private List<ChatMessage> latest(Conversation c, String me) {
        List<ChatMessage> page = new ArrayList<>(messages.findByConversationIdOrderBySentAtDesc(c.getId(), PageRequest.of(0, PAGE)));
        Collections.reverse(page);
        Instant cleared = c.clearedAtFor(me);
        if (cleared != null) {
            page.removeIf(m -> !m.getSentAt().isAfter(cleared));
        }
        return page;
    }

    private Conversation participantConversation(String me, String id) {
        Conversation c = conversations.findById(id).orElseThrow(() -> ChatException.notFound("Conversation not found."));
        if (!c.hasParticipant(me)) {
            throw ChatException.notFound("Conversation not found."); // do not reveal that it exists
        }
        return c;
    }

    private User activeUser(String username) {
        User u = users.findByUsername(username).orElseThrow(() -> ChatException.forbidden("Unknown account."));
        requireActive(u);
        if (!"client".equals(u.getRole()) && !"photographer".equals(u.getRole())) {
            throw ChatException.forbidden("Messaging is only available for customers and photographers.");
        }
        return u;
    }

    private static void requireActive(User u) {
        if (Boolean.TRUE.equals(u.getSuspended())) {
            throw ChatException.forbidden("This account is suspended.");
        }
    }

    private static Map<String, Integer> unread(Conversation c) {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put(c.getClientUsername(), c.getClientUnread());
        m.put(c.getPhotographerUsername(), c.getPhotographerUnread());
        return m;
    }

    private static ConversationView view(Conversation c, List<ChatMessage> msgs) {
        return new ConversationView(c.getId(), c.getBookingId(), c.getClientUsername(), c.getClientName(), c.getPhotographerUsername(),
                c.getPhotographerName(), msgs.stream().map(ChatService::toView).toList(), unread(c), c.getUpdatedAt());
    }

    static MessageView toView(ChatMessage m) {
        return new MessageView(m.getId(), m.getSenderUsername(), m.getSenderRole(), m.getText(), m.getSentAt(), m.getClientMessageId());
    }

    private static String displayName(User u) {
        return u.getName() == null || u.getName().isBlank() ? u.getUsername() : u.getName();
    }

    private static String preview(String text) {
        String single = text.replaceAll("\\s+", " ");
        return single.length() <= 120 ? single : single.substring(0, 119) + "…";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Sliding-window limiter, in memory (per instance). Good enough to stop a runaway client; use a shared store when scaling out. */
    static final class RateLimiter {
        private final int max;
        private final long windowMs;
        private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

        RateLimiter(int max, long windowMs) {
            this.max = max;
            this.windowMs = windowMs;
        }

        private final Map<String, Long> lastTyping = new ConcurrentHashMap<>();

        /** At most ~5 typing signals per second per user; returns false when the signal should be dropped. */
        boolean allowTyping(String key) {
            long now = System.currentTimeMillis();
            Long prev = lastTyping.put(key, now);
            return prev == null || now - prev >= 200;
        }

        void check(String key) {
            long now = System.currentTimeMillis();
            Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
            synchronized (q) {
                while (!q.isEmpty() && now - q.peekFirst() > windowMs) {
                    q.pollFirst();
                }
                if (q.size() >= max) {
                    throw ChatException.tooMany("You are sending messages too quickly. Please wait a moment.");
                }
                q.addLast(now);
            }
        }
    }
}