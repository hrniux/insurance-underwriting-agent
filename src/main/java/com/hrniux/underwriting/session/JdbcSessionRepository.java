package com.hrniux.underwriting.session;

import java.sql.Timestamp;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hrniux.underwriting.shared.persistence.PersistenceJsonCodec;

@Repository
@Profile("persistent-demo")
public class JdbcSessionRepository implements SessionRepository {

    private static final String UPSERT = """
            MERGE INTO underwriting_session (id, created_at, updated_at, payload)
            KEY (id) VALUES (?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;
    private final PersistenceJsonCodec codec;
    private final TransactionTemplate transactions;

    public JdbcSessionRepository(
            JdbcTemplate jdbc, PersistenceJsonCodec codec, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public UnderwritingSession save(UnderwritingSession session) {
        jdbc.update(
                UPSERT,
                session.id(),
                Timestamp.from(session.createdAt()),
                Timestamp.from(session.updatedAt()),
                codec.write(session));
        return session;
    }

    @Override
    public Optional<UnderwritingSession> appendMessage(String id, SessionMessage message) {
        return transactions.execute(status -> jdbc.query(
                "SELECT payload FROM underwriting_session WHERE id = ? FOR UPDATE",
                (result, row) -> codec.read(result.getString("payload"), UnderwritingSession.class),
                id).stream().findFirst()
                .map(current -> save(current.append(message, message.createdAt()))));
    }

    @Override
    public Optional<UnderwritingSession> findById(String id) {
        return jdbc.query(
                "SELECT payload FROM underwriting_session WHERE id = ?",
                (result, row) -> codec.read(result.getString("payload"), UnderwritingSession.class),
                id).stream().findFirst();
    }
}
