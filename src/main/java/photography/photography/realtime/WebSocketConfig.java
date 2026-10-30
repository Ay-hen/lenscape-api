package photography.photography.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over a plain WebSocket at {@code /ws}.
 * <pre>
 *   client -> /app/chat.send, /app/chat.read          (application destinations)
 *   server -> /user/queue/messages                    (chat events, private per user)
 *   server -> /user/queue/notifications               (notification push, private per user)
 *   server -> /user/queue/errors                      (errors for the sender only)
 * </pre>
 * The built-in simple broker keeps subscriptions in memory, so run ONE instance, or put a STOMP-capable broker
 * (RabbitMQ / ActiveMQ via {@code enableStompBrokerRelay}) in front when scaling out. See BACKEND-README.md.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor authInterceptor;
    private final String[] allowedOrigins;

    public WebSocketConfig(StompAuthChannelInterceptor authInterceptor, @Value("${app.cors.allowed-origins:http://localhost:4200}") String[] allowedOrigins) {
        this.authInterceptor = authInterceptor;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Origin check on the handshake blocks cross-site WebSocket hijacking from other websites.
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.enableSimpleBroker("/queue").setHeartbeatValue(new long[] {10_000, 10_000}).setTaskScheduler(wsHeartbeatScheduler());
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.setMessageSizeLimit(16 * 1024);      // a chat message is <= 2000 chars
        registry.setSendBufferSizeLimit(512 * 1024);  // slow consumers are disconnected instead of exhausting memory
        registry.setSendTimeLimit(20_000);
    }

    @Bean
    public ThreadPoolTaskScheduler wsHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        return scheduler;
    }
}
