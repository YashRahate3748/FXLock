package com.fxlock.quote;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import static com.fxlock.quote.Models.*;

@Repository
public class QuoteRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public QuoteRepository(
            JdbcTemplate jdbc,
            ObjectMapper json
    ) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /*
     * Server-side training aliases.
     * Request JSON cannot choose the sender/customer.
     */
    public long customerId(String principal) {

        String ref = switch (principal) {
            case "customer-demo" -> "CUST001";
            case "customer-other" -> "CUST002";
            default -> principal;
        };

        var ids = jdbc.queryForList(
                """
                SELECT CUSTOMER_ID
                FROM CUSTOMER
                WHERE CUSTOMER_REF = ?
                  AND STATUS = 'ACTIVE'
                """,
                Long.class,
                ref
        );

        if (ids.size() != 1) {
            throw new QuoteException(
                    403,
                    "CUSTOMER_UNAVAILABLE",
                    "No active customer is mapped to this login."
            );
        }

        return ids.getFirst();
    }

    /*
     * Current EUR -> USD prototype:
     *
     * Source:
     * - only the logged-in customer's EUR wallet can be debited.
     *
     * Destination:
     * - logged-in customer's own USD wallet is allowed
     *   (self conversion).
     *
     * - another active customer's USD wallet is also allowed
     *   (customer-to-customer transfer).
     */
    public List<WalletOption> wallets(String principal) {

        long senderCustomerId =
                customerId(principal);

        return jdbc.query(
                """
                SELECT
                    W.WALLET_ID,
                    W.CUSTOMER_ID,
                    W.CURRENCY_CODE,
                    C.CUSTOMER_REF,
                    C.FULL_NAME
                FROM WALLET W
                JOIN CUSTOMER C
                    ON C.CUSTOMER_ID =
                       W.CUSTOMER_ID
                WHERE W.STATUS = 'ACTIVE'
                  AND C.STATUS = 'ACTIVE'
                  AND W.CURRENCY_CODE
                      IN ('EUR', 'USD')
                ORDER BY
                    W.CUSTOMER_ID,
                    W.CURRENCY_CODE,
                    W.WALLET_ID
                """,

                (rs, rowNum) -> {

                    long walletCustomerId =
                            rs.getLong(
                                    "CUSTOMER_ID"
                            );

                    String currency =
                            rs.getString(
                                    "CURRENCY_CODE"
                            );

                    boolean ownWallet =
                            walletCustomerId
                                    == senderCustomerId;

                    String label;

                    if (ownWallet) {

                        label =
                                "My "
                                + currency
                                + " wallet";

                    } else {

                        label =
                                rs.getString(
                                        "FULL_NAME"
                                )
                                + " ("
                                + rs.getString(
                                        "CUSTOMER_REF"
                                )
                                + ") - "
                                + currency
                                + " wallet";
                    }

                    /*
                     * A customer may debit only wallets
                     * that belong to themselves.
                     *
                     * Both EUR and USD can now be sources.
                     */
                    boolean canDebit =
                            ownWallet;

                    return new WalletOption(
                            rs.getString(
                                    "WALLET_ID"
                            ),
                            label,
                            currency,
                            canDebit
                    );
                }
        );
    }

    public void insert(Quote q) {

        jdbc.update(
                """
                INSERT INTO FX_QUOTE
                (
                    QUOTE_ID,
                    CUSTOMER_ID,
                    SOURCE_WALLET_ID,
                    TARGET_WALLET_ID,
                    RATE_SNAPSHOT_ID,
                    SOURCE_CURRENCY,
                    TARGET_CURRENCY,
                    SOURCE_AMOUNT,
                    MARKET_RATE,
                    CUSTOMER_RATE,
                    APPLIED_SPREAD_BPS,
                    TARGET_AMOUNT,
                    STATUS,
                    CREATED_AT,
                    EXPIRES_AT,
                    CORRELATION_ID
                )
                VALUES
                (
                    ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?
                )
                """,
                q.quoteId(),
                Long.valueOf(q.customerId()),
                Long.valueOf(q.sourceWalletId()),
                Long.valueOf(q.targetWalletId()),
                Long.valueOf(q.snapshotId()),
                q.sourceCurrency(),
                q.targetCurrency(),
                q.sourceAmount(),
                q.marketRate(),
                q.offeredRate(),
                q.spreadBps(),
                q.targetAmount(),
                q.status(),
                utc(q.createdAt()),
                utc(q.expiresAt()),
                q.quoteId()
        );

        var c = q.confidence();

        jdbc.update(
                """
                INSERT INTO QUOTE_CONFIDENCE
                (
                    QUOTE_ID,
                    FRESHNESS_SCORE,
                    ANOMALY_SCORE,
                    PROVIDER_HEALTH_SCORE,
                    STABILITY_SCORE,
                    TOTAL_SCORE,
                    CONFIDENCE_LEVEL,
                    CALCULATION_DETAILS,
                    CREATED_AT
                )
                VALUES
                (
                    ?,?,?,?,?,?,?,?,?
                )
                """,
                q.quoteId(),
                c.freshnessScore(),
                c.anomalyScore(),
                c.providerHealthScore(),
                c.stabilityScore(),
                c.score(),
                c.label(),
                encode(c.reasons()),
                utc(q.createdAt())
        );
    }

    private static final String SELECT = """
            SELECT
                Q.*,
                R.PROVIDER_TIMESTAMP,
                R.RECEIVED_AT,
                C.FRESHNESS_SCORE,
                C.ANOMALY_SCORE,
                C.PROVIDER_HEALTH_SCORE,
                C.STABILITY_SCORE,
                C.TOTAL_SCORE,
                C.CONFIDENCE_LEVEL,
                C.CALCULATION_DETAILS
            FROM FX_QUOTE Q
            JOIN MARKET_RATE_SNAPSHOT R
                ON R.RATE_SNAPSHOT_ID = Q.RATE_SNAPSHOT_ID
            JOIN QUOTE_CONFIDENCE C
                ON C.QUOTE_ID = Q.QUOTE_ID
            WHERE Q.QUOTE_ID = ?
              AND Q.CUSTOMER_ID = ?
            """;

    public Optional<Quote> owned(
            String id,
            String customer
    ) {

        return jdbc.query(
                SELECT,
                this::map,
                id,
                customerId(customer)
        )
        .stream()
        .findFirst();
    }

    public Optional<Quote> lockOwned(
            String id,
            String customer
    ) {

        long customerId = customerId(customer);

        var ids = jdbc.queryForList(
                """
                SELECT QUOTE_ID
                FROM FX_QUOTE
                WHERE QUOTE_ID = ?
                  AND CUSTOMER_ID = ?
                FOR UPDATE
                """,
                String.class,
                id,
                customerId
        );

        if (ids.isEmpty()) {
            return Optional.empty();
        }

        return jdbc.query(
                SELECT,
                this::map,
                id,
                customerId
        )
        .stream()
        .findFirst();
    }

    public void cancel(
            String id,
            Instant now
    ) {

        jdbc.update(
                """
                UPDATE FX_QUOTE
                SET STATUS = 'CANCELLED',
                    CANCELLED_AT = ?
                WHERE QUOTE_ID = ?
                  AND STATUS = 'OPEN'
                """,
                utc(now),
                id
        );
    }

    public void audit(
            Quote q,
            String action,
            Instant now
    ) {

        jdbc.update(
                """
                INSERT INTO AUDIT_EVENT
                (
                    EVENT_TYPE,
                    ENTITY_TYPE,
                    ENTITY_ID,
                    ACTOR_TYPE,
                    ACTOR_ID,
                    EVENT_STATUS,
                    DETAILS,
                    CORRELATION_ID,
                    CREATED_AT
                )
                VALUES
                (
                    ?,
                    'QUOTE',
                    ?,
                    'CUSTOMER',
                    ?,
                    'SUCCESS',
                    ?,
                    ?,
                    ?
                )
                """,
                action,
                q.quoteId(),
                q.customerId(),
                encode(
                        Map.of(
                                "quoteId",
                                q.quoteId(),
                                "eventType",
                                action
                        )
                ),
                q.quoteId(),
                utc(now)
        );

        String eventId =
                UUID.randomUUID().toString();

        jdbc.update(
                """
                INSERT INTO OUTBOX_EVENT
                (
                    EVENT_ID,
                    AGGREGATE_TYPE,
                    AGGREGATE_ID,
                    EVENT_TYPE,
                    TOPIC_NAME,
                    PAYLOAD,
                    STATUS,
                    CORRELATION_ID,
                    CREATED_AT
                )
                VALUES
                (
                    ?,
                    'QUOTE',
                    ?,
                    ?,
                    ?,
                    ?,
                    'PENDING',
                    ?,
                    ?
                )
                """,
                eventId,
                q.quoteId(),
                action,
                "fx-quote-events",
                encode(
                        Map.of(
                                "eventId",
                                eventId,
                                "eventType",
                                action,
                                "schemaVersion",
                                1,
                                "occurredAt",
                                now.toString(),
                                "aggregateType",
                                "QUOTE",
                                "aggregateId",
                                q.quoteId(),
                                "correlationId",
                                q.quoteId(),
                                "data",
                                Map.of(
                                        "customerId",
                                        q.customerId(),
                                        "quoteId",
                                        q.quoteId(),
                                        "sourceWalletId",
                                        q.sourceWalletId(),
                                        "targetWalletId",
                                        q.targetWalletId()
                                )
                        )
                ),
                q.quoteId(),
                utc(now)
        );
    }

    public Instant settlementCompletedAt(
            String id,
            String quoteId,
            String customer
    ) {

        return jdbc.query(
                """
                SELECT COMPLETED_AT
                FROM SETTLEMENT
                WHERE SETTLEMENT_ID = ?
                  AND QUOTE_ID = ?
                  AND CUSTOMER_ID = ?
                  AND STATUS = 'COMPLETED'
                """,
                (rs, n) ->
                        instant(
                                rs,
                                "COMPLETED_AT"
                        ),
                id,
                quoteId,
                customerId(customer)
        )
        .stream()
        .findFirst()
        .orElseThrow(
                () -> new QuoteException(
                        502,
                        "SETTLEMENT_RESPONSE_INVALID",
                        "No matching committed settlement exists in Oracle."
                )
        );
    }

    private Quote map(
            ResultSet rs,
            int row
    ) throws SQLException {

        var confidence =
                new Confidence(
                        rs.getBigDecimal(
                                "TOTAL_SCORE"
                        ),
                        rs.getString(
                                "CONFIDENCE_LEVEL"
                        ),
                        reasons(
                                rs.getString(
                                        "CALCULATION_DETAILS"
                                )
                        ),
                        rs.getBigDecimal(
                                "FRESHNESS_SCORE"
                        ),
                        rs.getBigDecimal(
                                "ANOMALY_SCORE"
                        ),
                        rs.getBigDecimal(
                                "PROVIDER_HEALTH_SCORE"
                        ),
                        rs.getBigDecimal(
                                "STABILITY_SCORE"
                        )
                );

        var observed =
                rs.getObject(
                        "PROVIDER_TIMESTAMP",
                        OffsetDateTime.class
                );

        return new Quote(
                rs.getString("QUOTE_ID"),
                rs.getString("CUSTOMER_ID"),
                rs.getString("SOURCE_WALLET_ID"),
                rs.getString("TARGET_WALLET_ID"),
                rs.getString("SOURCE_CURRENCY"),
                rs.getString("TARGET_CURRENCY"),
                rs.getString("RATE_SNAPSHOT_ID"),

                observed == null
                        ? instant(
                                rs,
                                "RECEIVED_AT"
                        )
                        : observed.toInstant(),

                rs.getBigDecimal("MARKET_RATE"),

                rs.getBigDecimal(
                        "CUSTOMER_RATE"
                ).setScale(10),

                rs.getBigDecimal(
                        "SOURCE_AMOUNT"
                ).setScale(2),

                rs.getBigDecimal(
                        "TARGET_AMOUNT"
                ).setScale(2),

                rs.getBigDecimal(
                        "APPLIED_SPREAD_BPS"
                ),

                confidence,

                instant(
                        rs,
                        "CREATED_AT"
                ),

                instant(
                        rs,
                        "EXPIRES_AT"
                ),

                rs.getString("STATUS")
        );
    }

    private static OffsetDateTime utc(
            Instant time
    ) {
        return time.atOffset(
                ZoneOffset.UTC
        );
    }

    private static Instant instant(
            ResultSet rs,
            String column
    ) throws SQLException {

        var value =
                rs.getObject(
                        column,
                        OffsetDateTime.class
                );

        if (value == null) {
            throw new SQLException(
                    "Missing timestamp: "
                    + column
            );
        }

        return value.toInstant();
    }

    private String encode(Object value) {

        try {
            return json.writeValueAsString(
                    value
            );
        } catch (Exception e) {

            throw new IllegalStateException(
                    "Cannot serialize quote event",
                    e
            );
        }
    }

    private List<String> reasons(
            String value
    ) {

        try {

            return json.readValue(
                    value,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                    }
            );

        } catch (Exception e) {

            throw new IllegalStateException(
                    "Cannot read stored confidence details",
                    e
            );
        }
    }
}