package com.example.events;
import com.example.contract.EventEnvelope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
public class AuditConsumer {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public AuditConsumer(JdbcTemplate jdbc,ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }
    @Transactional
    @KafkaListener(topics={"fx-rate-updated","fx-settlement-completed","fx-system-alert"})
    public void receive(ConsumerRecord<String,String> record) throws Exception {
        EventEnvelope event=mapper.readValue(record.value(),EventEnvelope.class);
        if(!record.topic().equals(event.eventType())) throw new IllegalArgumentException("Topic/type mismatch");
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(event)));
        try {
            jdbc.update("INSERT INTO fx_inbox(consumer_name,event_id,body_hash,processed_at) VALUES('fx-audit-v1',?,?,SYSTIMESTAMP)",
                    event.eventId().toString(),hash);
        } catch(DuplicateKeyException duplicate) {
            String old=jdbc.queryForObject("SELECT body_hash FROM fx_inbox WHERE consumer_name='fx-audit-v1' AND event_id=?",String.class,event.eventId().toString());
            if(!hash.equals(old)) throw new IllegalStateException("Event ID reused with different content: "+event.eventId());
            return;
        }
        // Dedup marker and effect commit together. Offset is committed only after the proxied listener returns.
        jdbc.update("""
            INSERT INTO fx_audit(event_id,event_type,aggregate_id,source,correlation_id,occurred_at,
                received_at,kafka_partition,kafka_offset,envelope)
            VALUES(?,?,?,?,?,?,SYSTIMESTAMP,?,?,?)
            """,event.eventId().toString(),event.eventType(),event.aggregateId(),event.source(),event.correlationId(),
            java.sql.Timestamp.from(event.occurredAt()),record.partition(),record.offset(),record.value());
    }
}
