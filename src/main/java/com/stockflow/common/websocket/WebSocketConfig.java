package com.stockflow.common.websocket;

import com.stockflow.common.security.CorsProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.thread.Threading;
import org.springframework.boot.task.SimpleAsyncTaskSchedulerBuilder;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * The application's STOMP socket: endpoint {@code /ws}, an in-memory broker for {@code /topic} and {@code /queue},
 * application destinations under {@code /app}, user destinations under {@code /user}. Authentication and the
 * subscription checks are {@link StompAuthenticationInterceptor}'s.
 *
 * <h2>One instance, in-memory broker</h2>
 *
 * <p>The application runs as one instance on the VPS, so the simple broker is enough: everything published reaches
 * every socket of this process. A second instance would need a relay - Redis pub/sub (already in the stack) carrying
 * what each instance publishes to the others, or a STOMP broker such as RabbitMQ behind
 * {@code enableStompBrokerRelay} - and the chat's presence, which is in memory too, would move to Redis with it.</p>
 *
 * <h2>Origins</h2>
 *
 * <p>The handshake accepts the same origins as REST ({@code stockflow.security.cors.allowed-origin-patterns},
 * {@code CORS_ALLOWED_ORIGIN_PATTERNS}): a socket that any website could open would let it read a signed-in user's
 * conversations with a token it obtained elsewhere.</p>
 *
 * <h2>The schedulers and executors this brings</h2>
 *
 * <p>The broker configuration declares a task scheduler and channel executors as beans. Spring Boot backs off building
 * its own scheduler and executor when such beans exist, which would move every {@code @Scheduled} job onto the
 * broker's scheduler and every {@code @Async} listener onto a bare thread per call. {@link #taskScheduler} therefore
 * declares Boot's scheduler under the name {@code @Scheduled} looks for, built as Boot would build it, and
 * {@code spring.task.execution.mode=force} keeps Boot's executor (see {@code AsyncConfig}). The broker's heartbeats run
 * on a small scheduler of their own, which is no bean at all.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer, DisposableBean {

    /** The STOMP endpoint. */
    public static final String ENDPOINT = "/ws";
    /** Heartbeats both ways, every 10 s: what keeps a proxy from closing an idle socket, and finds a dead one. */
    private static final long HEARTBEAT_MS = 10_000;

    private final CorsProperties cors;
    private final StompAuthenticationInterceptor authentication;
    private final ThreadPoolTaskScheduler heartbeats = new ThreadPoolTaskScheduler();

    public WebSocketConfig(CorsProperties cors, StompAuthenticationInterceptor authentication) {
        this.cors = cors;
        this.authentication = authentication;
        heartbeats.setPoolSize(1);
        heartbeats.setThreadNamePrefix("stomp-heartbeat-");
        heartbeats.initialize();
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(ENDPOINT).setAllowedOriginPatterns(cors.allowedOriginPatterns().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {HEARTBEAT_MS, HEARTBEAT_MS})
                .setTaskScheduler(heartbeats);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authentication);
    }

    /** Small frames only: a typing signal or a heartbeat. Messages and files go over REST. */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(16 * 1024).setSendTimeLimit(15_000).setSendBufferSizeLimit(512 * 1024);
    }

    /**
     * Spring Boot's scheduler for {@code @Scheduled} jobs, as Boot would have built it (virtual threads when they are
     * on, its pool settings otherwise), under the name the scheduling post-processor picks when there are several -
     * see the class javadoc.
     */
    @Bean(name = "taskScheduler")
    public TaskScheduler taskScheduler(Environment environment,
                                       ObjectProvider<SimpleAsyncTaskSchedulerBuilder> virtualThreads,
                                       ObjectProvider<ThreadPoolTaskSchedulerBuilder> pool) {
        if (Threading.VIRTUAL.isActive(environment)) {
            return virtualThreads.getIfAvailable(() -> new SimpleAsyncTaskSchedulerBuilder().virtualThreads(true))
                    .build();
        }
        return pool.getIfAvailable(ThreadPoolTaskSchedulerBuilder::new).build();
    }

    @Override
    public void destroy() {
        heartbeats.shutdown();
    }
}
