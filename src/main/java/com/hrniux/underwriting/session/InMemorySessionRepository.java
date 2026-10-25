package com.hrniux.underwriting.session;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!persistent-demo")
public class InMemorySessionRepository implements SessionRepository {

    private final ConcurrentHashMap<String, UnderwritingSession> sessions = new ConcurrentHashMap<>();

    @Override
    public UnderwritingSession save(UnderwritingSession session) {
        sessions.put(session.id(), session);
        return session;
    }

    @Override
    public Optional<UnderwritingSession> appendMessage(String id, SessionMessage message) {
        return Optional.ofNullable(sessions.computeIfPresent(id,
                (key, current) -> current.append(message, message.createdAt())));
    }

    @Override
    public Optional<UnderwritingSession> findById(String id) {
        return Optional.ofNullable(sessions.get(id));
    }
}
