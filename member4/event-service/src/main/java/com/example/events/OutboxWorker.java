package com.example.events;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component
public class OutboxWorker {
    private static final Logger log=LoggerFactory.getLogger(OutboxWorker.class);
    private final OutboxStore store;
    private final KafkaTemplate<String,String> kafka;
    public OutboxWorker(OutboxStore store,KafkaTemplate<String,String> kafka) { this.store=store; this.kafka=kafka; }
    @Scheduled(fixedDelayString="${fx.poll-ms}")
    public void publish() {
        var next=store.claim();
        if(next.isEmpty()) return;
        var row=next.get();
        try {
            kafka.send(row.topic(),row.key(),row.body()).get(40,TimeUnit.SECONDS);
            // Crash here causes replay after lease expiry. Consumers MUST deduplicate by eventId.
            store.published(row);
        } catch(Exception failure) {
            if(failure instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Publication failed eventId={}",row.id(),failure);
            store.failed(row,failure);
        }
    }
}
