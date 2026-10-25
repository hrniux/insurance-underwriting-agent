package com.hrniux.underwriting.session;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hrniux.underwriting.shared.error.ResourceNotFoundException;

@Service
public class SessionService {

    private final SessionRepository repository;
    private final Clock clock;
    private final Supplier<String> idSupplier;

    @Autowired
    public SessionService(SessionRepository repository) {
        this(repository, Clock.systemUTC(), () -> "SES-" + UUID.randomUUID().toString().replace("-", ""));
    }

    SessionService(SessionRepository repository, Clock clock, Supplier<String> idSupplier) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier must not be null");
    }

    public UnderwritingSession createSession() {
        Instant now = clock.instant();
        return repository.save(new UnderwritingSession(idSupplier.get(), List.of(), now, now));
    }

    public UnderwritingSession getSession(String sessionId) {
        return repository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("SESSION_NOT_FOUND", sessionId));
    }

    public UnderwritingSession appendMessage(String sessionId, SessionRole role, String content) {
        SessionMessage message = new SessionMessage(role, content, clock.instant());
        return repository.appendMessage(sessionId, message)
                .orElseThrow(() -> new ResourceNotFoundException("SESSION_NOT_FOUND", sessionId));
    }
}
