package com.example.events;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import static org.mockito.Mockito.*;

class OutboxWorkerTest {
    private final OutboxStore store=mock(OutboxStore.class);
    @SuppressWarnings("unchecked") private final KafkaTemplate<String,String> kafka=mock(KafkaTemplate.class);
    private final OutboxStore.Claimed row=new OutboxStore.Claimed("id","fx-system-alert","system","{}","lease",1);
    @Test void marksPublishedOnlyAfterBrokerAcknowledgement() {
        when(store.claim()).thenReturn(Optional.of(row));
        when(kafka.send(row.topic(),row.key(),row.body())).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        new OutboxWorker(store,kafka).publish();
        var order=inOrder(kafka,store);order.verify(kafka).send(row.topic(),row.key(),row.body());order.verify(store).published(row);
        verify(store,never()).failed(any(),any());
    }
    @Test void kafkaFailureRetainsRecordForRetry() {
        when(store.claim()).thenReturn(Optional.of(row));
        when(kafka.send(row.topic(),row.key(),row.body())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
        new OutboxWorker(store,kafka).publish();
        verify(store).failed(eq(row),any());verify(store,never()).published(any());
    }
    @Test void emptyQueueDoesNotSend() {
        when(store.claim()).thenReturn(Optional.empty());new OutboxWorker(store,kafka).publish();verifyNoInteractions(kafka);
    }
}
