package com.hrniux.underwriting.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hrniux.underwriting.shared.error.ResourceNotFoundException;

class SessionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-13T06:00:00Z");

    private SessionService service;

    @BeforeEach
    void setUp() {
        service = new SessionService(
                new InMemorySessionRepository(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "SES-TEST-001");
    }

    @Test
    void createsAnEmptySessionWithAStableId() {
        UnderwritingSession created = service.createSession();

        assertThat(created.id()).isEqualTo("SES-TEST-001");
        assertThat(created.messages()).isEmpty();
        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(created.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void appendsAnImmutableMessageSnapshot() {
        service.createSession();

        UnderwritingSession updated = service.appendMessage(
                "SES-TEST-001", SessionRole.USER, "请评估保单 P-1001");

        assertThat(updated.messages())
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.role()).isEqualTo(SessionRole.USER);
                    assertThat(message.content()).isEqualTo("请评估保单 P-1001");
                    assertThat(message.createdAt()).isEqualTo(NOW);
                });
        assertThatThrownBy(() -> updated.messages().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void preservesBothMessagesWhenConcurrentAppendsReadTheSameSession() throws Exception {
        CyclicBarrier bothRead = new CyclicBarrier(2);
        AtomicBoolean concurrentReads = new AtomicBoolean(true);
        InMemorySessionRepository repository = new InMemorySessionRepository() {
            @Override
            public Optional<UnderwritingSession> findById(String id) {
                Optional<UnderwritingSession> snapshot = super.findById(id);
                if (concurrentReads.get()) {
                    try {
                        bothRead.await(5, TimeUnit.SECONDS);
                    }
                    catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                }
                return snapshot;
            }
        };
        SessionService concurrentService = new SessionService(repository);
        String sessionId = concurrentService.createSession().id();

        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> concurrentService.appendMessage(sessionId, SessionRole.USER, "first"));
            var second = pool.submit(() -> concurrentService.appendMessage(sessionId, SessionRole.USER, "second"));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        finally {
            concurrentReads.set(false);
        }

        assertThat(concurrentService.getSession(sessionId).messages())
                .extracting(SessionMessage::content)
                .containsExactlyInAnyOrder("first", "second");
    }

    @Test
    void rejectsBlankMessages() {
        service.createSession();

        assertThatThrownBy(() -> service.appendMessage("SES-TEST-001", SessionRole.USER, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");
    }

    @Test
    void reportsAnUnknownSession() {
        assertThatThrownBy(() -> service.getSession("SES-MISSING"))
                .isInstanceOf(ResourceNotFoundException.class)
                .satisfies(error -> assertThat(((ResourceNotFoundException) error).errorCode())
                        .isEqualTo("SESSION_NOT_FOUND"));
        assertThatThrownBy(() -> service.appendMessage("SES-MISSING", SessionRole.USER, "message"))
                .isInstanceOf(ResourceNotFoundException.class)
                .satisfies(error -> assertThat(((ResourceNotFoundException) error).errorCode())
                        .isEqualTo("SESSION_NOT_FOUND"));
    }
}
