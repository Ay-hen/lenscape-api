package photography.photography.chat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ChatMessageRepo extends MongoRepository<ChatMessage, String> {
    List<ChatMessage> findByConversationIdOrderBySentAtDesc(String conversationId, Pageable pageable);

    List<ChatMessage> findByConversationIdAndSentAtBeforeOrderBySentAtDesc(String conversationId, Instant before, Pageable pageable);

    void deleteByConversationId(String conversationId);

    Optional<ChatMessage> findByConversationIdAndSenderUsernameAndClientMessageId(String conversationId, String senderUsername, String clientMessageId);
}