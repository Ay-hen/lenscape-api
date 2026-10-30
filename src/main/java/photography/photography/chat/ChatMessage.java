package photography.photography.chat;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("messages")
@CompoundIndex(name = "history", def = "{'conversationId': 1, 'sentAt': -1}")
// Idempotency: a retry of the same client message can never create a second copy.
@CompoundIndex(name = "dedupe", def = "{'conversationId': 1, 'senderUsername': 1, 'clientMessageId': 1}", unique = true)
public class ChatMessage {
    @Id
    private String id;
    private String conversationId;
    private String senderUsername;
    private String senderRole;
    private String text;
    private String clientMessageId;
    private Instant sentAt;
}
