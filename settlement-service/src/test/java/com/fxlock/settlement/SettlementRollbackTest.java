package com.fxlock.settlement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import org.springframework.web.server.ResponseStatusException;

import com.fxlock.idempotency.IdempotencyRepository;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.fxlock.wallet.WalletRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
class SettlementRollbackTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SettlementCoordinator coordinator;
    
    @Autowired
    private SettlementService settlementService;

    @MockitoSpyBean
    private WalletRepository walletRepository;
    
    @MockitoSpyBean
    private IdempotencyRepository idempotencyRepository;

    private final String quoteId = UUID.randomUUID().toString();
    private final String customerRef =
            "ROLLBACK-" + UUID.randomUUID();
    
    private final String competingQuoteId =
            UUID.randomUUID().toString();

    private final String idempotencyKey =
            "rollback-" + UUID.randomUUID();

    private final String fixtureCorrelation =
            "TEST-" + UUID.randomUUID();

    private Long customerId;
    private Long sourceWalletId;
    private Long targetWalletId;

    @BeforeEach
    void createFixtures() {
        jdbc.update(
                """
                INSERT INTO customer (customer_ref, full_name)
                VALUES (?, ?)
                """,
                customerRef,
                "Rollback Test Customer"
        );

        customerId = jdbc.queryForObject(
                "SELECT customer_id FROM customer WHERE customer_ref = ?",
                Long.class,
                customerRef
        );

        jdbc.update(
                """
                INSERT INTO wallet (
                    customer_id, currency_code, balance
                )
                VALUES (?, 'EUR', 5000)
                """,
                customerId
        );

        jdbc.update(
                """
                INSERT INTO wallet (
                    customer_id, currency_code, balance
                )
                VALUES (?, 'USD', 1000)
                """,
                customerId
        );

        sourceWalletId = findWalletId("EUR");
        targetWalletId = findWalletId("USD");

        jdbc.update(
                """
                INSERT INTO market_rate_snapshot (
                    currency_pair,
                    provider_name,
                    market_rate,
                    provider_timestamp,
                    validation_status,
                    trusted_flag,
                    correlation_id
                )
                VALUES (
                    'EUR/USD',
                    'ROLLBACK_TEST',
                    1.1000,
                    SYSTIMESTAMP,
                    'TRUSTED',
                    'Y',
                    ?
                )
                """,
                fixtureCorrelation
        );

        Long rateId = jdbc.queryForObject(
                """
                SELECT rate_snapshot_id
                FROM market_rate_snapshot
                WHERE correlation_id = ?
                """,
                Long.class,
                fixtureCorrelation
        );

        jdbc.update(
                """
                INSERT INTO fx_quote (
                    quote_id,
                    customer_id,
                    source_wallet_id,
                    target_wallet_id,
                    rate_snapshot_id,
                    source_currency,
                    target_currency,
                    source_amount,
                    market_rate,
                    customer_rate,
                    applied_spread_bps,
                    target_amount,
                    status,
                    created_at,
                    expires_at,
                    correlation_id
                )
                VALUES (
                    ?, ?, ?, ?, ?,
                    'EUR', 'USD',
                    100, 1.1000, 1.0978, 20, 109.78,
                    'OPEN',
                    SYSTIMESTAMP,
                    SYSTIMESTAMP + INTERVAL '60' SECOND,
                    ?
                )
                """,
                quoteId,
                customerId,
                sourceWalletId,
                targetWalletId,
                rateId,
                fixtureCorrelation
        );
    }
    
    @Test
    void settlementRetrievalIsRestrictedToOwner() {
        String otherCustomerRef =
                "OTHER-" + UUID.randomUUID();

        try {
            jdbc.update(
                    """
                    INSERT INTO customer (customer_ref, full_name)
                    VALUES (?, ?)
                    """,
                    otherCustomerRef,
                    "Other Test Customer"
            );

            SettlementResponse completed = coordinator.settle(
                    quoteId,
                    customerRef,
                    idempotencyKey
            );

            // The owner can retrieve the settlement.
            SettlementResponse retrieved =
                    settlementService.getSettlement(
                            completed.settlementId(),
                            customerRef
                    );

            assertEquals(
                    completed.settlementId(),
                    retrieved.settlementId()
            );

            assertEquals(quoteId, retrieved.quoteId());
            assertEquals("COMPLETED", retrieved.status());
            assertMoney("100", retrieved.sourceAmount());
            assertMoney("109.78", retrieved.targetAmount());

            // A different active customer cannot retrieve it.
            ResponseStatusException forbiddenLookup = assertThrows(
                    ResponseStatusException.class,
                    () -> settlementService.getSettlement(
                            completed.settlementId(),
                            otherCustomerRef
                    )
            );

            assertEquals(
                    404,
                    forbiddenLookup.getStatusCode().value()
            );

            // An unknown settlement also returns 404.
            ResponseStatusException missingLookup = assertThrows(
                    ResponseStatusException.class,
                    () -> settlementService.getSettlement(
                            UUID.randomUUID().toString(),
                            customerRef
                    )
            );

            assertEquals(
                    404,
                    missingLookup.getStatusCode().value()
            );

            // Retrieval attempts must not move money.
            assertMoney("4900", balance(sourceWalletId));
            assertMoney("1109.78", balance(targetWalletId));

        } finally {
            jdbc.update(
                    "DELETE FROM customer WHERE customer_ref = ?",
                    otherCustomerRef
            );
        }
    }
    
    @Test
    void quoteExpiringDuringWalletLockWaitIsRejected()
            throws Exception {

        ExecutorService executor = Executors.newSingleThreadExecutor();

        CountDownLatch walletLockRequested = new CountDownLatch(1);

        Long firstWalletId = Math.min(
                sourceWalletId,
                targetWalletId
        );

        try {
            try (Connection blocker = Objects.requireNonNull(
                    jdbc.getDataSource()
            ).getConnection()) {

                blocker.setAutoCommit(false);

                try {
                    // Hold the first wallet lock in a separate transaction.
                    try (PreparedStatement statement =
                            blocker.prepareStatement(
                                    """
                                    SELECT wallet_id
                                    FROM wallet
                                    WHERE wallet_id = ?
                                    FOR UPDATE
                                    """
                            )) {

                        statement.setLong(1, firstWalletId);

                        try (ResultSet result = statement.executeQuery()) {
                            assertTrue(result.next());
                        }
                    }

                    // Give this test quote a short remaining validity.
                    jdbc.update(
                            """
                            UPDATE fx_quote
                            SET expires_at =
                                SYSTIMESTAMP + INTERVAL '5' SECOND
                            WHERE quote_id = ?
                            """,
                            quoteId
                    );

                    doAnswer(invocation -> {
                        OffsetDateTime now =
                                jdbc.queryForObject(
                                        "SELECT SYSTIMESTAMP FROM dual",
                                        OffsetDateTime.class
                                );

                        OffsetDateTime expiry =
                                jdbc.queryForObject(
                                        """
                                        SELECT expires_at
                                        FROM fx_quote
                                        WHERE quote_id = ?
                                        """,
                                        OffsetDateTime.class,
                                        quoteId
                                );

                        // Confirm settlement reaches wallet locking
                        // while the quote is still fresh.
                        assertNotNull(now);
                        assertNotNull(expiry);
                        assertTrue(
                                now.isBefore(expiry),
                                "Quote expired before wallet locking began"
                        );

                        walletLockRequested.countDown();

                        // Real query waits for the blocker's Oracle lock.
                        return invocation.callRealMethod();

                    }).when(walletRepository).findByIdForUpdate(
                            eq(firstWalletId)
                    );

                    Future<SettlementResponse> future =
                            executor.submit(() -> coordinator.settle(
                                    quoteId,
                                    customerRef,
                                    idempotencyKey
                            ));

                    assertTrue(
                            walletLockRequested.await(
                                    10,
                                    TimeUnit.SECONDS
                            ),
                            "Settlement did not reach wallet locking"
                    );

                    // Keep the wallet locked until Oracle says
                    // the quote has expired.
                    long deadline = System.nanoTime()
                            + TimeUnit.SECONDS.toNanos(15);

                    while (true) {
                        Integer expired = jdbc.queryForObject(
                                """
                                SELECT CASE
                                    WHEN SYSTIMESTAMP >= expires_at
                                    THEN 1 ELSE 0
                                END
                                FROM fx_quote
                                WHERE quote_id = ?
                                """,
                                Integer.class,
                                quoteId
                        );

                        if (Integer.valueOf(1).equals(expired)) {
                            break;
                        }

                        assertTrue(
                                System.nanoTime() < deadline,
                                "Timed out waiting for quote expiry"
                        );

                        Thread.sleep(100);
                    }

                    assertFalse(
                            future.isDone(),
                            "Settlement should still be waiting for the wallet"
                    );

                    // Release the wallet lock after quote expiry.
                    blocker.rollback();

                    ExecutionException failure = assertThrows(
                            ExecutionException.class,
                            () -> future.get(30, TimeUnit.SECONDS)
                    );

                    ResponseStatusException rejection =
                            assertInstanceOf(
                                    ResponseStatusException.class,
                                    failure.getCause()
                            );

                    assertEquals(
                            409,
                            rejection.getStatusCode().value()
                    );

                    assertEquals(
                            "QUOTE_EXPIRED",
                            rejection.getReason()
                    );

                    assertMoney("5000", balance(sourceWalletId));
                    assertMoney("1000", balance(targetWalletId));

                    assertEquals(
                            0,
                            count(
                                    """
                                    SELECT COUNT(*) FROM settlement
                                    WHERE quote_id = ?
                                    """,
                                    quoteId
                            )
                    );

                    assertEquals(
                            0,
                            count(
                                    """
                                    SELECT COUNT(*) FROM wallet_ledger
                                    WHERE wallet_id IN (?, ?)
                                    """,
                                    sourceWalletId,
                                    targetWalletId
                            )
                    );

                    assertEquals(
                            0,
                            count(
                                    """
                                    SELECT COUNT(*) FROM idempotency_record
                                    WHERE idempotency_key = ?
                                    """,
                                    idempotencyKey
                            )
                    );

                    assertEquals(
                            1,
                            count(
                                    """
                                    SELECT COUNT(*) FROM audit_event
                                    WHERE entity_type = 'QUOTE'
                                      AND entity_id = ?
                                      AND event_type =
                                          'SETTLEMENT_ATTEMPT_REJECTED'
                                      AND event_status = 'REJECTED'
                                    """,
                                    quoteId
                            )
                    );

                } finally {
                    // Release the lock even if an assertion fails.
                    blocker.rollback();
                }
            }

        } finally {
            executor.shutdown();

            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();

                assertTrue(
                        executor.awaitTermination(30, TimeUnit.SECONDS),
                        "Worker did not stop"
                );
            }
        }
    }
    
    @Test
    void oneHundredConcurrentAcceptsProduceOneSettlement()
            throws Exception {

        int requestCount = 100;

        ExecutorService executor =
                Executors.newFixedThreadPool(requestCount);

        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<AcceptanceAttempt>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < requestCount; i++) {
                String requestKey = idempotencyKey + "-" + i;

                futures.add(executor.submit(() -> {
                    ready.countDown();

                    if (!start.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Timed out waiting for test start"
                        );
                    }

                    try {
                        SettlementResponse response = coordinator.settle(
                                quoteId,
                                customerRef,
                                requestKey
                        );

                        return new AcceptanceAttempt(
                                true,
                                response.settlementId(),
                                null
                        );

                    } catch (ResponseStatusException exception) {
                        // Any unexpected business error fails the test.
                        assertEquals(
                                409,
                                exception.getStatusCode().value()
                        );

                        assertEquals(
                                "QUOTE_NOT_OPEN",
                                exception.getReason()
                        );

                        return new AcceptanceAttempt(
                                false,
                                null,
                                exception.getReason()
                        );
                    }
                }));
            }

            assertTrue(
                    ready.await(15, TimeUnit.SECONDS),
                    "All 100 workers must be ready"
            );

            start.countDown();

            int successful = 0;
            int rejected = 0;
            String winningSettlementId = null;

            // One overall deadline for collecting results.
            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(90);

            for (Future<AcceptanceAttempt> future : futures) {
                long remaining = deadline - System.nanoTime();

                assertTrue(
                        remaining > 0,
                        "Concurrent requests exceeded the test deadline"
                );

                AcceptanceAttempt attempt = future.get(
                        remaining,
                        TimeUnit.NANOSECONDS
                );

                if (attempt.successful()) {
                    successful++;
                    winningSettlementId = attempt.settlementId();
                } else {
                    rejected++;
                }
            }

            assertEquals(1, successful);
            assertEquals(99, rejected);
            assertNotNull(winningSettlementId);

            // Exactly one conversion.
            assertMoney("4900", balance(sourceWalletId));
            assertMoney("1109.78", balance(targetWalletId));

            assertEquals(
                    "SETTLED",
                    jdbc.queryForObject(
                            "SELECT status FROM fx_quote WHERE quote_id = ?",
                            String.class,
                            quoteId
                    )
            );

            assertEquals(
                    1,
                    count(
                            """
                            SELECT COUNT(*) FROM settlement
                            WHERE quote_id = ?
                              AND status = 'COMPLETED'
                            """,
                            quoteId
                    )
            );

            assertEquals(
                    2,
                    count(
                            """
                            SELECT COUNT(*) FROM wallet_ledger
                            WHERE settlement_id = ?
                            """,
                            winningSettlementId
                    )
            );

            assertEquals(
                    1,
                    count(
                            """
                            SELECT COUNT(*) FROM idempotency_record
                            WHERE quote_id = ?
                              AND response_status = 'COMPLETED'
                            """,
                            quoteId
                    )
            );

            assertEquals(
                    1,
                    count(
                            """
                            SELECT COUNT(*) FROM audit_event
                            WHERE entity_type = 'SETTLEMENT'
                              AND entity_id = ?
                              AND event_type = 'SETTLEMENT_COMPLETED'
                            """,
                            winningSettlementId
                    )
            );

            assertEquals(
                    1,
                    count(
                            """
                            SELECT COUNT(*) FROM outbox_event
                            WHERE aggregate_type = 'SETTLEMENT'
                              AND aggregate_id = ?
                            """,
                            winningSettlementId
                    )
            );

            // Each rejected attempt should have its own audit.
            assertEquals(
                    99,
                    count(
                            """
                            SELECT COUNT(*) FROM audit_event
                            WHERE entity_type = 'QUOTE'
                              AND entity_id = ?
                              AND event_type = 'SETTLEMENT_ATTEMPT_REJECTED'
                              AND event_status = 'REJECTED'
                            """,
                            quoteId
                    )
            );

        } finally {
            // Release waiting workers even if preparation fails.
            start.countDown();

            executor.shutdown();

            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();

                assertTrue(
                        executor.awaitTermination(30, TimeUnit.SECONDS),
                        "Workers did not stop; inspect running database sessions"
                );
            }
        }
    }

    private record AcceptanceAttempt(
            boolean successful,
            String settlementId,
            String rejectionReason
    ) {}
    
    @Test
    void lateFailureRollsBackAllSettlementWrites() {
        java.util.concurrent.atomic.AtomicReference<String> settlementId =
                new java.util.concurrent.atomic.AtomicReference<>();

        doAnswer(invocation -> {
            SettlementResponse response = invocation.getArgument(1);
            settlementId.set(response.settlementId());

            // These queries run inside the settlement transaction.
            // Prove that the financial and success records exist
            // before forcing the failure.
            assertMoney("4900", balance(sourceWalletId));
            assertMoney("1109.78", balance(targetWalletId));

            assertEquals(
                    "SETTLED",
                    jdbc.queryForObject(
                            "SELECT status FROM fx_quote WHERE quote_id = ?",
                            String.class,
                            quoteId
                    )
            );

            assertEquals(
                    1,
                    count(
                            """
                            SELECT COUNT(*) FROM settlement
                            WHERE settlement_id = ?
                              AND status = 'COMPLETED'
                            """,
                            response.settlementId()
                    )
            );

            assertEquals(
                    2,
                    count(
                            """
                            SELECT COUNT(*) FROM wallet_ledger
                            WHERE settlement_id = ?
                            """,
                            response.settlementId()
                    )
            );

            assertEquals(
                    1,
                    count(
                            """
                            SELECT COUNT(*) FROM audit_event
                            WHERE entity_type = 'SETTLEMENT'
                              AND entity_id = ?
                              AND event_type = 'SETTLEMENT_COMPLETED'
                            """,
                            response.settlementId()
                    )
            );

            assertEquals(
                    1,
                    count(
                            """
                            SELECT COUNT(*) FROM outbox_event
                            WHERE aggregate_type = 'SETTLEMENT'
                              AND aggregate_id = ?
                            """,
                            response.settlementId()
                    )
            );

            throw new IllegalStateException("TEST_LATE_FAILURE");

        }).when(idempotencyRepository).complete(
                eq(idempotencyKey),
                any(SettlementResponse.class)
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> coordinator.settle(
                        quoteId,
                        customerRef,
                        idempotencyKey
                )
        );

        assertEquals("TEST_LATE_FAILURE", failure.getMessage());
        assertNotNull(settlementId.get());

        // The transaction must now have rolled back everything.
        assertMoney("5000", balance(sourceWalletId));
        assertMoney("1000", balance(targetWalletId));

        assertEquals(
                "OPEN",
                jdbc.queryForObject(
                        "SELECT status FROM fx_quote WHERE quote_id = ?",
                        String.class,
                        quoteId
                )
        );

        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM settlement WHERE quote_id = ?",
                        quoteId
                )
        );

        assertEquals(
                0,
                count(
                        """
                        SELECT COUNT(*) FROM wallet_ledger
                        WHERE settlement_id = ?
                        """,
                        settlementId.get()
                )
        );

        assertEquals(
                0,
                count(
                        """
                        SELECT COUNT(*) FROM audit_event
                        WHERE entity_type = 'SETTLEMENT'
                          AND entity_id = ?
                          AND event_type = 'SETTLEMENT_COMPLETED'
                        """,
                        settlementId.get()
                )
        );

        assertEquals(
                0,
                count(
                        """
                        SELECT COUNT(*) FROM outbox_event
                        WHERE aggregate_type = 'SETTLEMENT'
                          AND aggregate_id = ?
                        """,
                        settlementId.get()
                )
        );

        assertEquals(
                0,
                count(
                        """
                        SELECT COUNT(*) FROM idempotency_record
                        WHERE idempotency_key = ?
                        """,
                        idempotencyKey
                )
        );

        // The separate error-audit transaction must survive.
        assertEquals(
                1,
                count(
                        """
                        SELECT COUNT(*) FROM audit_event
                        WHERE entity_type = 'QUOTE'
                          AND entity_id = ?
                          AND event_type = 'SETTLEMENT_ATTEMPT_ERROR'
                          AND event_status = 'ERROR'
                        """,
                        quoteId
                )
        );
    }
    
    @Test
    void oneHundredSameKeyRequestsReturnOneSettlement()
            throws Exception {

        int requestCount = 100;

        ExecutorService executor =
                Executors.newFixedThreadPool(requestCount);

        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<SettlementResponse>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < requestCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();

                    if (!start.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Timed out waiting for test start"
                        );
                    }

                    // Every caller uses the SAME quote and key.
                    return coordinator.settle(
                            quoteId,
                            customerRef,
                            idempotencyKey
                    );
                }));
            }

            assertTrue(
                    ready.await(15, TimeUnit.SECONDS),
                    "All 100 workers must be ready"
            );

            start.countDown();

            SettlementResponse originalResponse = null;

            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(90);

            for (Future<SettlementResponse> future : futures) {
                long remaining = deadline - System.nanoTime();

                assertTrue(
                        remaining > 0,
                        "Concurrent requests exceeded the test deadline"
                );

                SettlementResponse response = future.get(
                        remaining,
                        TimeUnit.NANOSECONDS
                );

                assertEquals("COMPLETED", response.status());
                assertEquals(quoteId, response.quoteId());
                assertEquals("EUR", response.sourceCurrency());
                assertEquals("USD", response.targetCurrency());

                assertMoney("100", response.sourceAmount());
                assertMoney("109.78", response.targetAmount());

                if (originalResponse == null) {
                    originalResponse = response;
                } else {
                    assertEquals(
                            originalResponse.settlementId(),
                            response.settlementId(),
                            "All callers must receive the same settlement ID"
                    );
                }
            }

            assertNotNull(originalResponse);

            String settlementId = originalResponse.settlementId();

            assertMoney("4900", balance(sourceWalletId));
            assertMoney("1109.78", balance(targetWalletId));

            assertEquals(
                    "SETTLED",
                    jdbc.queryForObject(
                            "SELECT status FROM fx_quote WHERE quote_id = ?",
                            String.class,
                            quoteId
                    )
            );

            assertEquals(
                    1,
                    count(
                            """
                            SELECT COUNT(*) FROM settlement
                            WHERE quote_id = ?
                              AND status = 'COMPLETED'
                            """,
                            quoteId
                    )
            );

            assertEquals(
                    2,
                    count(
                            """
                            SELECT COUNT(*) FROM wallet_ledger
                            WHERE settlement_id = ?
                            """,
                            settlementId
                    )
            );

            assertEquals(
                    1,
                    count(
                            """
                            SELECT COUNT(*) FROM idempotency_record
                            WHERE idempotency_key = ?
                              AND settlement_id = ?
                              AND response_status = 'COMPLETED'
                            """,
                            idempotencyKey,
                            settlementId
                    )
            );

            assertEquals(
                    1,
                    count(
                            """
                            SELECT COUNT(*) FROM audit_event
                            WHERE entity_type = 'SETTLEMENT'
                              AND entity_id = ?
                              AND event_type = 'SETTLEMENT_COMPLETED'
                            """,
                            settlementId
                    )
            );

            assertEquals(
                    1,
                    count(
                            """
                            SELECT COUNT(*) FROM outbox_event
                            WHERE aggregate_type = 'SETTLEMENT'
                              AND aggregate_id = ?
                            """,
                            settlementId
                    )
            );

            // Successful replays must not create rejection/error audits.
            assertEquals(
                    0,
                    count(
                            """
                            SELECT COUNT(*) FROM audit_event
                            WHERE entity_type = 'QUOTE'
                              AND entity_id = ?
                              AND event_type IN (
                                  'SETTLEMENT_ATTEMPT_REJECTED',
                                  'SETTLEMENT_ATTEMPT_ERROR'
                              )
                            """,
                            quoteId
                    )
            );

        } finally {
            start.countDown();

            executor.shutdown();

            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();

                assertTrue(
                        executor.awaitTermination(30, TimeUnit.SECONDS),
                        "Workers did not stop; inspect running database sessions"
                );
            }
        }
    }
    
    @Test
    void competingQuotesCannotOverspendWallet() throws Exception {

        // Change only this test's unaccepted fixture.
        jdbc.update(
                """
                UPDATE fx_quote
                SET source_amount = 4000,
                    target_amount = 4391.20
                WHERE quote_id = ?
                """,
                quoteId
        );

        // Create a different quote using the same test wallets.
        jdbc.update(
                """
                INSERT INTO fx_quote (
                    quote_id,
                    customer_id,
                    source_wallet_id,
                    target_wallet_id,
                    rate_snapshot_id,
                    source_currency,
                    target_currency,
                    source_amount,
                    market_rate,
                    customer_rate,
                    applied_spread_bps,
                    target_amount,
                    status,
                    created_at,
                    expires_at,
                    correlation_id
                )
                SELECT
                    ?,
                    customer_id,
                    source_wallet_id,
                    target_wallet_id,
                    rate_snapshot_id,
                    source_currency,
                    target_currency,
                    source_amount,
                    market_rate,
                    customer_rate,
                    applied_spread_bps,
                    target_amount,
                    'OPEN',
                    SYSTIMESTAMP,
                    SYSTIMESTAMP + INTERVAL '60' SECOND,
                    correlation_id
                FROM fx_quote
                WHERE quote_id = ?
                """,
                competingQuoteId,
                quoteId
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<AcceptanceAttempt>> futures = new ArrayList<>();

        try {
            for (String requestedQuoteId :
                    List.of(quoteId, competingQuoteId)) {

                futures.add(executor.submit(() -> {
                    ready.countDown();

                    if (!start.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Timed out waiting for test start"
                        );
                    }

                    try {
                        SettlementResponse response = coordinator.settle(
                                requestedQuoteId,
                                customerRef,
                                "competing-" + requestedQuoteId
                        );

                        return new AcceptanceAttempt(
                                true,
                                response.settlementId(),
                                null
                        );

                    } catch (ResponseStatusException exception) {
                        assertEquals(
                                409,
                                exception.getStatusCode().value()
                        );

                        assertEquals(
                                "INSUFFICIENT_FUNDS",
                                exception.getReason()
                        );

                        return new AcceptanceAttempt(
                                false,
                                null,
                                exception.getReason()
                        );
                    }
                }));
            }

            assertTrue(ready.await(15, TimeUnit.SECONDS));
            start.countDown();

            int successful = 0;
            int rejected = 0;

            for (Future<AcceptanceAttempt> future : futures) {
                AcceptanceAttempt attempt =
                        future.get(60, TimeUnit.SECONDS);

                if (attempt.successful()) {
                    successful++;
                } else {
                    rejected++;
                }
            }

            assertEquals(1, successful);
            assertEquals(1, rejected);

            // Only one EUR 4000 -> USD 4391.20 conversion.
            assertMoney("1000", balance(sourceWalletId));
            assertMoney("5391.20", balance(targetWalletId));

            assertEquals(
                    1,
                    count(
                            """
                            SELECT COUNT(*) FROM settlement
                            WHERE customer_id = ?
                              AND status = 'COMPLETED'
                            """,
                            customerId
                    )
            );

            assertEquals(
                    2,
                    count(
                            """
                            SELECT COUNT(*)
                            FROM wallet_ledger
                            WHERE wallet_id IN (?, ?)
                            """,
                            sourceWalletId,
                            targetWalletId
                    )
            );

            assertEquals(
                    1,
                    count(
                            """
                            SELECT COUNT(*) FROM fx_quote
                            WHERE quote_id IN (?, ?)
                              AND status = 'SETTLED'
                            """,
                            quoteId,
                            competingQuoteId
                    )
            );

            assertEquals(
                    1,
                    count(
                            """
                            SELECT COUNT(*) FROM fx_quote
                            WHERE quote_id IN (?, ?)
                              AND status = 'OPEN'
                            """,
                            quoteId,
                            competingQuoteId
                    )
            );

            assertEquals(
                    1,
                    count(
                            """
                            SELECT COUNT(*) FROM idempotency_record
                            WHERE quote_id IN (?, ?)
                              AND response_status = 'COMPLETED'
                            """,
                            quoteId,
                            competingQuoteId
                    )
            );

            assertEquals(
                    1,
                    count(
                            """
                            SELECT COUNT(*) FROM outbox_event
                            WHERE aggregate_type = 'SETTLEMENT'
                              AND aggregate_id IN (
                                  SELECT settlement_id FROM settlement
                                  WHERE customer_id = ?
                              )
                            """,
                            customerId
                    )
            );

            assertEquals(
                    1,
                    count(
                            """
                            SELECT COUNT(*) FROM audit_event
                            WHERE entity_type = 'QUOTE'
                              AND entity_id IN (?, ?)
                              AND event_type = 'SETTLEMENT_ATTEMPT_REJECTED'
                            """,
                            quoteId,
                            competingQuoteId
                    )
            );

        } finally {
            start.countDown();
            executor.shutdown();

            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();

                assertTrue(
                        executor.awaitTermination(30, TimeUnit.SECONDS),
                        "Workers did not stop"
                );
            }
        }
    }

    @Test
    void creditFailureRollsBackDebitAndPreservesErrorAudit() {
        AtomicBoolean creditReached = new AtomicBoolean(false);

        doAnswer(invocation -> {
            creditReached.set(true);

            // This query joins the active settlement transaction.
            // It proves the debit happened before the forced failure.
            assertMoney("4900", balance(sourceWalletId));

            throw new IllegalStateException("TEST_CREDIT_FAILURE");
        }).when(walletRepository).credit(
                eq(targetWalletId),
                any(BigDecimal.class)
        );

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> coordinator.settle(
                        quoteId,
                        customerRef,
                        idempotencyKey
                )
        );

        assertEquals("TEST_CREDIT_FAILURE", failure.getMessage());
        assertTrue(creditReached.get());

        // The service transaction has now rolled back.
        assertMoney("5000", balance(sourceWalletId));
        assertMoney("1000", balance(targetWalletId));

        assertEquals(
                "OPEN",
                jdbc.queryForObject(
                        "SELECT status FROM fx_quote WHERE quote_id = ?",
                        String.class,
                        quoteId
                )
        );

        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM settlement WHERE quote_id = ?",
                        quoteId
                )
        );

        assertEquals(
                0,
                count(
                        """
                        SELECT COUNT(*)
                        FROM wallet_ledger
                        WHERE wallet_id IN (?, ?)
                        """,
                        sourceWalletId,
                        targetWalletId
                )
        );

        assertEquals(
                0,
                count(
                        """
                        SELECT COUNT(*)
                        FROM idempotency_record
                        WHERE idempotency_key = ?
                        """,
                        idempotencyKey
                )
        );

        assertEquals(
                1,
                count(
                        """
                        SELECT COUNT(*)
                        FROM audit_event
                        WHERE entity_type = 'QUOTE'
                          AND entity_id = ?
                          AND event_type = 'SETTLEMENT_ATTEMPT_ERROR'
                          AND event_status = 'ERROR'
                        """,
                        quoteId
                )
        );

        String attemptCorrelation = jdbc.queryForObject(
                """
                SELECT correlation_id
                FROM audit_event
                WHERE entity_type = 'QUOTE'
                  AND entity_id = ?
                  AND event_type = 'SETTLEMENT_ATTEMPT_ERROR'
                """,
                String.class,
                quoteId
        );

        assertEquals(
                0,
                count(
                        """
                        SELECT COUNT(*)
                        FROM outbox_event
                        WHERE correlation_id = ?
                        """,
                        attemptCorrelation
                )
        );

        assertEquals(
                0,
                count(
                        """
                        SELECT COUNT(*)
                        FROM audit_event
                        WHERE correlation_id = ?
                          AND event_type = 'SETTLEMENT_COMPLETED'
                        """,
                        attemptCorrelation
                )
        );
    }

    private Long findWalletId(String currency) {
        return jdbc.queryForObject(
                """
                SELECT wallet_id
                FROM wallet
                WHERE customer_id = ?
                  AND currency_code = ?
                """,
                Long.class,
                customerId,
                currency
        );
    }

    private BigDecimal balance(Long walletId) {
        return jdbc.queryForObject(
                "SELECT balance FROM wallet WHERE wallet_id = ?",
                BigDecimal.class,
                walletId
        );
    }

    private int count(String sql, Object... parameters) {
        return jdbc.queryForObject(sql, Integer.class, parameters);
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(
                0,
                new BigDecimal(expected).compareTo(actual)
        );
    }

    @AfterEach
    void removeTestFixtures() {
        removeQuoteData(quoteId);
        removeQuoteData(competingQuoteId);

        jdbc.update(
                """
                DELETE FROM market_rate_snapshot
                WHERE correlation_id = ?
                """,
                fixtureCorrelation
        );

        jdbc.update(
                "DELETE FROM wallet WHERE customer_id = ?",
                customerId
        );

        jdbc.update(
                "DELETE FROM customer WHERE customer_ref = ?",
                customerRef
        );
    }

    private void removeQuoteData(String testQuoteId) {
        jdbc.update(
                """
                DELETE FROM outbox_event
                WHERE aggregate_type = 'SETTLEMENT'
                  AND aggregate_id IN (
                      SELECT settlement_id
                      FROM settlement WHERE quote_id = ?
                  )
                """,
                testQuoteId
        );

        jdbc.update(
                """
                DELETE FROM audit_event
                WHERE (entity_type = 'QUOTE' AND entity_id = ?)
                   OR (entity_type = 'SETTLEMENT' AND entity_id IN (
                       SELECT settlement_id
                       FROM settlement WHERE quote_id = ?
                   ))
                """,
                testQuoteId,
                testQuoteId
        );

        jdbc.update(
                """
                DELETE FROM idempotency_record
                WHERE quote_id = ?
                """,
                testQuoteId
        );

        jdbc.update(
                """
                DELETE FROM wallet_ledger
                WHERE settlement_id IN (
                    SELECT settlement_id
                    FROM settlement WHERE quote_id = ?
                )
                """,
                testQuoteId
        );

        jdbc.update(
                "DELETE FROM settlement WHERE quote_id = ?",
                testQuoteId
        );

        jdbc.update(
                "DELETE FROM fx_quote WHERE quote_id = ?",
                testQuoteId
        );
    }
    
    private void assertRejectedWithoutFinancialChanges(
            String expectedReason
    ) {
        ResponseStatusException rejection = assertThrows(
                ResponseStatusException.class,
                () -> coordinator.settle(
                        quoteId,
                        customerRef,
                        idempotencyKey
                )
        );

        int expectedStatus =
                "QUOTE_NOT_OWNED".equals(expectedReason) ? 403 : 409;

        assertEquals(
                expectedStatus,
                rejection.getStatusCode().value()
        );

        assertEquals(expectedReason, rejection.getReason());

        assertMoney("5000", balance(sourceWalletId));
        assertMoney("1000", balance(targetWalletId));

        assertEquals(
                "OPEN",
                jdbc.queryForObject(
                        "SELECT status FROM fx_quote WHERE quote_id = ?",
                        String.class,
                        quoteId
                )
        );

        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM settlement WHERE quote_id = ?",
                        quoteId
                )
        );

        assertEquals(
                0,
                count(
                        """
                        SELECT COUNT(*) FROM wallet_ledger
                        WHERE wallet_id IN (?, ?)
                        """,
                        sourceWalletId,
                        targetWalletId
                )
        );

        assertEquals(
                0,
                count(
                        """
                        SELECT COUNT(*) FROM idempotency_record
                        WHERE idempotency_key = ?
                        """,
                        idempotencyKey
                )
        );

        String reason = jdbc.queryForObject(
                """
                SELECT JSON_VALUE(details, '$.reasonCode')
                FROM audit_event
                WHERE entity_type = 'QUOTE'
                  AND entity_id = ?
                  AND event_type = 'SETTLEMENT_ATTEMPT_REJECTED'
                  AND event_status = 'REJECTED'
                """,
                String.class,
                quoteId
        );

        assertEquals(expectedReason, reason);
    }
    
    @Test
    void insufficientFundsRejectsSettlement() {
        jdbc.update(
                """
                UPDATE fx_quote
                SET source_amount = 6000,
                    target_amount = 6586.80
                WHERE quote_id = ?
                """,
                quoteId
        );

        assertRejectedWithoutFinancialChanges(
                "INSUFFICIENT_FUNDS"
        );
    }
    
    @Test
    void blockedSourceWalletRejectsSettlement() {
        jdbc.update(
                "UPDATE wallet SET status = 'BLOCKED' WHERE wallet_id = ?",
                sourceWalletId
        );

        assertRejectedWithoutFinancialChanges(
                "WALLET_NOT_ACTIVE"
        );
    }

    @Test
    void blockedTargetWalletRejectsSettlement() {
        jdbc.update(
                "UPDATE wallet SET status = 'BLOCKED' WHERE wallet_id = ?",
                targetWalletId
        );

        assertRejectedWithoutFinancialChanges(
                "WALLET_NOT_ACTIVE"
        );
    }
    
    @Test
    void mismatchedWalletCurrencyRejectsSettlement() {
        jdbc.update(
                """
                UPDATE fx_quote
                SET source_wallet_id = ?,
                    target_wallet_id = ?
                WHERE quote_id = ?
                """,
                targetWalletId,
                sourceWalletId,
                quoteId
        );

        assertRejectedWithoutFinancialChanges(
                "WALLET_CURRENCY_MISMATCH"
        );
    }
    
    @Test
    void anotherCustomersQuoteCannotBeAccepted() {
        String otherCustomerRef =
                "OWNER-" + UUID.randomUUID();

        try {
            jdbc.update(
                    """
                    INSERT INTO customer (customer_ref, full_name)
                    VALUES (?, ?)
                    """,
                    otherCustomerRef,
                    "Different Quote Owner"
            );

            Long otherCustomerId = jdbc.queryForObject(
                    """
                    SELECT customer_id
                    FROM customer
                    WHERE customer_ref = ?
                    """,
                    Long.class,
                    otherCustomerRef
            );

            // Deliberately assign this fixture to another customer.
            jdbc.update(
                    """
                    UPDATE fx_quote
                    SET customer_id = ?
                    WHERE quote_id = ?
                    """,
                    otherCustomerId,
                    quoteId
            );

            assertRejectedWithoutFinancialChanges(
                    "QUOTE_NOT_OWNED"
            );

        } finally {
            // Restore the reference before removing the extra customer.
            jdbc.update(
                    "UPDATE fx_quote SET customer_id = ? WHERE quote_id = ?",
                    customerId,
                    quoteId
            );

            jdbc.update(
                    "DELETE FROM customer WHERE customer_ref = ?",
                    otherCustomerRef
            );
        }
    }
}