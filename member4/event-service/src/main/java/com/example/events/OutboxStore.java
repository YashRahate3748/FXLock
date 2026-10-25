package com.example.events;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OutboxStore {
    public record Claimed(String id, String topic, String key, String body, String token, int attempts) {}
    private final JdbcTemplate jdbc;
    public OutboxStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** Oracle SKIP LOCKED + JDBC maxRows avoids unsupported FETCH FIRST ... FOR UPDATE combinations.
     * One row per short transaction, no DB lock held while waiting on Kafka. */
    @Transactional
    public Optional<Claimed> claim() {
        return jdbc.query(connection -> {
            var ps = connection.prepareStatement("""
                SELECT event_id,topic,event_key,envelope,attempts FROM fx_outbox
                WHERE (status='PENDING' AND next_attempt_at<=SYSTIMESTAMP)
                   OR (status='IN_FLIGHT' AND lease_until<SYSTIMESTAMP)
                ORDER BY created_at FOR UPDATE SKIP LOCKED
                """);
            ps.setMaxRows(1); ps.setFetchSize(1); return ps;
        }, rs -> {
            if (!rs.next()) return Optional.empty();
            String token = UUID.randomUUID().toString();
            var row = new Claimed(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),token,rs.getInt(5)+1);
            jdbc.update("""
                UPDATE fx_outbox SET status='IN_FLIGHT',claim_token=?,
                    lease_until=SYSTIMESTAMP+NUMTODSINTERVAL(120,'SECOND'),attempts=attempts+1
                WHERE event_id=?
                """, token,row.id());
            return Optional.of(row);
        });
    }
    public void published(Claimed row) {
        jdbc.update("""
            UPDATE fx_outbox SET status='PUBLISHED',published_at=SYSTIMESTAMP,lease_until=NULL,
                claim_token=NULL,last_error=NULL WHERE event_id=? AND claim_token=? AND status='IN_FLIGHT'
            """,row.id(),row.token());
    }
    public void failed(Claimed row, Exception failure) {
        String error = failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage());
        jdbc.update("""
            UPDATE fx_outbox SET status='PENDING',claim_token=NULL,lease_until=NULL,last_error=?,
                next_attempt_at=SYSTIMESTAMP+NUMTODSINTERVAL(?,'SECOND')
            WHERE event_id=? AND claim_token=? AND status='IN_FLIGHT'
            """,error.substring(0,Math.min(1900,error.length())),Math.min(300,1L << Math.min(row.attempts(),8)),row.id(),row.token());
    }
}
