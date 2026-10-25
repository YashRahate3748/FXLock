package com.example.events;

import com.example.contract.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.kafka.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Requires dedicated, empty Oracle test schema initialized using database/001-schema.sql.
 * Do not run against a live app: these tests deliberately expire leases. */
@EnabledIfEnvironmentVariable(named="RUN_INFRA_TESTS",matches="true")
class OracleKafkaTest {
    static AnnotationConfigApplicationContext context;
    static JdbcTemplate jdbc;
    static OutboxStore store;
    static AuditConsumer audit;
    static ObjectMapper mapper;
    static TransactionTemplate tx;
    static KafkaTemplate<String,String> kafka;
    static DefaultKafkaProducerFactory<String,String> factory;
    static final List<String> ids=new ArrayList<>();
    static String env(String name,String fallback) { return System.getenv().getOrDefault(name,fallback); }
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DriverManagerDataSource dataSource() {
            return new DriverManagerDataSource(env("ORACLE_URL","jdbc:oracle:thin:@//localhost:1521/FREEPDB1"),
                    env("ORACLE_USER","fx"),System.getenv("ORACLE_PASSWORD"));
        }
        @Bean JdbcTemplate jdbc(DriverManagerDataSource ds){return new JdbcTemplate(ds);}
        @Bean PlatformTransactionManager manager(DriverManagerDataSource ds){return new DataSourceTransactionManager(ds);}
        @Bean ObjectMapper mapper(){return JsonMapper.builder().build();}
        @Bean OutboxStore store(JdbcTemplate jdbc){return new OutboxStore(jdbc);}
        @Bean AuditConsumer audit(JdbcTemplate jdbc,ObjectMapper mapper){return new AuditConsumer(jdbc,mapper);}
    }
    @BeforeAll static void setup() throws Exception {
        context=new AnnotationConfigApplicationContext(Config.class);
        jdbc=context.getBean(JdbcTemplate.class);store=context.getBean(OutboxStore.class);
        audit=context.getBean(AuditConsumer.class);mapper=context.getBean(ObjectMapper.class);
        tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        String bootstrap=env("KAFKA_BOOTSTRAP_SERVERS","localhost:9092");
        try(var admin=Admin.create(Map.of("bootstrap.servers",bootstrap))) {
            var topics=admin.listTopics().names().get(30,TimeUnit.SECONDS);
            if(!topics.contains("fx-system-alert"))admin.createTopics(List.of(new NewTopic("fx-system-alert",3,(short)1))).all().get(30,TimeUnit.SECONDS);
        }
        factory=new DefaultKafkaProducerFactory<>(Map.of("bootstrap.servers",bootstrap,"acks","all",
                "key.serializer",StringSerializer.class,"value.serializer",StringSerializer.class));
        kafka=new KafkaTemplate<>(factory);
    }
    static EventEnvelope event() {
        String id=UUID.randomUUID().toString();ids.add(id);
        return new EventEnvelope(UUID.fromString(id),1,"fx-system-alert",id,"integration-test",id,
                Instant.now(),mapper.createObjectNode().put("message","acceptance test"));
    }
    static void append(EventEnvelope event) {
        tx.executeWithoutResult(s->new OutboxAppender(jdbc,mapper).append(event));
    }
    @Test void duplicateDeliveryHasOneEffectAndConflictIsRejected() throws Exception {
        var e=event();String json=mapper.writeValueAsString(e);
        var r=new ConsumerRecord<String,String>(e.eventType(),0,1,e.aggregateId(),json);
        audit.receive(r);audit.receive(r);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM fx_audit WHERE event_id=?",Integer.class,e.eventId().toString()));
        var changed=new EventEnvelope(e.eventId(),1,e.eventType(),e.aggregateId(),e.source(),e.correlationId(),e.occurredAt(),mapper.createObjectNode().put("message","different"));
        assertThrows(IllegalStateException.class,()->audit.receive(new ConsumerRecord<>(e.eventType(),0,2,e.aggregateId(),mapper.writeValueAsString(changed))));
    }
    @Test void failedEffectRollsBackDedupMarker() throws Exception {
        var e=event();String json=mapper.writeValueAsString(e);
        // Pre-existing audit row causes effect insert failure AFTER the inbox insert.
        jdbc.update("INSERT INTO fx_audit VALUES(?,?,?,?,?,?,SYSTIMESTAMP,0,0,?)",e.eventId().toString(),e.eventType(),e.aggregateId(),e.source(),e.correlationId(),java.sql.Timestamp.from(e.occurredAt()),json);
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->audit.receive(new ConsumerRecord<>(e.eventType(),0,0,e.aggregateId(),json)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM fx_inbox WHERE event_id=?",Integer.class,e.eventId().toString()));
    }
    @Test void publicationCrashReplaysAndStaleOwnerCannotCompleteNewClaim() throws Exception {
        var e=event();append(e);
        var first=store.claim().orElseThrow();assertEquals(e.eventId().toString(),first.id());
        kafka.send(first.topic(),first.key(),first.body()).get(30,TimeUnit.SECONDS);
        // Simulate death after Kafka acknowledgement but before published().
        jdbc.update("UPDATE fx_outbox SET lease_until=SYSTIMESTAMP-NUMTODSINTERVAL(1,'SECOND') WHERE event_id=?",first.id());
        var second=store.claim().orElseThrow();assertNotEquals(first.token(),second.token());
        store.published(first);
        assertEquals("IN_FLIGHT",jdbc.queryForObject("SELECT status FROM fx_outbox WHERE event_id=?",String.class,first.id()));
        kafka.send(second.topic(),second.key(),second.body()).get(30,TimeUnit.SECONDS);store.published(second);
        int copies=0;
        var properties=new HashMap<String,Object>();
        properties.put("bootstrap.servers",env("KAFKA_BOOTSTRAP_SERVERS","localhost:9092"));
        properties.put("group.id",UUID.randomUUID().toString());properties.put("auto.offset.reset","earliest");
        properties.put("enable.auto.commit",false);
        try(var consumer=new KafkaConsumer<String,String>(properties,new StringDeserializer(),new StringDeserializer())) {
            consumer.subscribe(List.of(e.eventType()));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
            while(copies<2&&System.nanoTime()<deadline)for(var record:consumer.poll(Duration.ofMillis(500))) {
                if(mapper.readValue(record.value(),EventEnvelope.class).eventId().equals(e.eventId())) { audit.receive(record);copies++; }
            }
        }
        assertEquals(2,copies);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM fx_audit WHERE event_id=?",Integer.class,first.id()));
    }
    @Test void concurrentWorkersClaimDifferentRows() throws Exception {
        append(event());append(event());
        try(var pool=Executors.newFixedThreadPool(2)) {
            var tasks=pool.invokeAll(List.<Callable<OutboxStore.Claimed>>of(()->store.claim().orElseThrow(),()->store.claim().orElseThrow()));
            assertNotEquals(tasks.get(0).get().id(),tasks.get(1).get().id());
        }
    }
    @Test void unreachableBrokerRetainsRowAndConfiguredBrokerPublishesOnRetry() throws Exception {
        var e=event();append(e);
        // Simulate a connection failure without stopping the installed broker.
        var offlineFactory=new DefaultKafkaProducerFactory<String,String>(Map.of(
            "bootstrap.servers","127.0.0.1:1","key.serializer",StringSerializer.class,
            "value.serializer",StringSerializer.class,"max.block.ms",1000,
            "request.timeout.ms",1000,"delivery.timeout.ms",3000));
        try {
            new OutboxWorker(store,new KafkaTemplate<>(offlineFactory)).publish();
            assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM fx_outbox WHERE event_id=?",String.class,e.eventId().toString()));
            assertNotNull(jdbc.queryForObject("SELECT last_error FROM fx_outbox WHERE event_id=?",String.class,e.eventId().toString()));
        } finally {
            offlineFactory.destroy();
        }
        jdbc.update("UPDATE fx_outbox SET next_attempt_at=SYSTIMESTAMP WHERE event_id=?",e.eventId().toString());
        new OutboxWorker(store,kafka).publish();
        assertEquals("PUBLISHED",jdbc.queryForObject("SELECT status FROM fx_outbox WHERE event_id=?",String.class,e.eventId().toString()));
    }
    @AfterEach void cleanup() {
        for(String id:ids) {jdbc.update("DELETE FROM fx_outbox WHERE event_id=?",id);jdbc.update("DELETE FROM fx_audit WHERE event_id=?",id);jdbc.update("DELETE FROM fx_inbox WHERE event_id=?",id);}
        ids.clear();
    }
    @AfterAll static void close() { if(factory!=null)factory.destroy();if(context!=null)context.close(); }
}
