package com.fxlock.events;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
@Component
public class OutboxSchedule {
 private final OutboxPublisher publisher;private final int batch;
 public OutboxSchedule(OutboxPublisher publisher,@Value("${fxlock.events.batch-size:20}") int batch) {
  this.publisher=publisher;this.batch=batch;
 }
 @Scheduled(fixedDelayString="${fxlock.events.poll-ms:2000}")
 public void tick() {
  try { for(int i=0;i<batch && publisher.publishOne();i++) {} }
  catch(Exception e) { LoggerFactory.getLogger(getClass()).warn("Outbox cycle failed: {}",e.getClass().getSimpleName()); }
 }
}
