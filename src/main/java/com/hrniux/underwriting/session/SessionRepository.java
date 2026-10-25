package com.hrniux.underwriting.session;

import java.util.Optional;

public interface SessionRepository {

    UnderwritingSession save(UnderwritingSession session);

    Optional<UnderwritingSession> findById(String id);

    /** Appends to the latest stored snapshot atomically; an unknown session stays absent. */
    Optional<UnderwritingSession> appendMessage(String id, SessionMessage message);
}
