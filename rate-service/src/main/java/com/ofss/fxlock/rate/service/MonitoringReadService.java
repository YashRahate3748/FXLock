package com.ofss.fxlock.rate.service;

import com.ofss.fxlock.rate.dto.MonitoringResponse.*;
import com.ofss.fxlock.rate.entity.FxConfig;
import com.ofss.fxlock.rate.entity.MarketRateSnapshot;
import com.ofss.fxlock.rate.exception.ResourceNotFoundException;
import com.ofss.fxlock.rate.repository.FxConfigRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class MonitoringReadService {

    private static final int HISTORY_LIMIT = 2000;

    private static final Set<String> ANOMALY_STATUSES = Set.of(
            "OPEN",
            "ACKNOWLEDGED",
            "APPROVED",
            "REJECTED"
    );

    private static final String ANOMALY_SELECT = """
            SELECT
                a.ANOMALY_ID,
                a.STATUS AS ANOMALY_STATUS,
                a.REASON,
                a.MOVEMENT_PERCENT AS ANOMALY_MOVEMENT,
                a.THRESHOLD_PERCENT,
                a.CREATED_AT AS ANOMALY_CREATED_AT,
                a.REVIEWED_BY,
                a.REVIEW_REASON,
                a.REVIEWED_AT,

                c.RATE_SNAPSHOT_ID AS C_ID,
                c.CURRENCY_PAIR AS C_PAIR,
                c.MARKET_RATE AS C_RATE,
                c.PROVIDER_TIMESTAMP AS C_OBSERVED,
                c.RECEIVED_AT AS C_RECEIVED,
                c.VALIDATION_STATUS AS C_VALIDATION,
                c.MOVEMENT_PERCENT AS C_MOVEMENT,

                p.RATE_SNAPSHOT_ID AS P_ID,
                p.CURRENCY_PAIR AS P_PAIR,
                p.MARKET_RATE AS P_RATE,
                p.PROVIDER_TIMESTAMP AS P_OBSERVED,
                p.RECEIVED_AT AS P_RECEIVED,
                p.VALIDATION_STATUS AS P_VALIDATION,
                p.MOVEMENT_PERCENT AS P_MOVEMENT

            FROM RATE_ANOMALY a
            JOIN MARKET_RATE_SNAPSHOT c
                ON c.RATE_SNAPSHOT_ID = a.RATE_SNAPSHOT_ID
            LEFT JOIN MARKET_RATE_SNAPSHOT p
                ON p.RATE_SNAPSHOT_ID = a.PREVIOUS_TRUSTED_RATE_ID
            """;

    private final JdbcTemplate jdbc;
    private final FxConfigRepository configRepository;
    private final TrustedRateCache cache;
    private final ProviderStateService provider;
    private final long pollIntervalMs;

    public MonitoringReadService(
            JdbcTemplate jdbc,
            FxConfigRepository configRepository,
            TrustedRateCache cache,
            ProviderStateService provider,
            @Value("${fxlock.rate.scheduler-delay-ms:15000}")
            long pollIntervalMs
    ) {
        this.jdbc = jdbc;
        this.configRepository = configRepository;
        this.cache = cache;
        this.provider = provider;
        this.pollIntervalMs = pollIntervalMs;
    }

    public Status getStatus() {
        FxConfig config = getConfig();
        MarketRateSnapshot current = cache.get().orElse(null);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        boolean fresh = current != null
                && RateValidationService.isProviderObservationFresh(
                        current.getProviderTimestamp(),
                        config.getMaxRateAgeSeconds(),
                        now.toInstant()
                );

        boolean enabled = config.isQuotingEnabled();

        String unavailableReason = null;

        if (current == null) {
            unavailableReason = "No trusted rate available";
        } else if (!fresh) {
            unavailableReason =
                    "Provider observation is missing, future-dated or stale";
        } else if (!enabled) {
            unavailableReason = "Quote generation is disabled";
        }

        return new Status(
                now,
                toPoint(current),
                fresh,
                enabled,
                fresh && enabled,
                unavailableReason,
                provider.getHealth().name(),
                provider.getLastSuccess(),
                provider.getLastFailure(),
                provider.getNextAllowedFetch(),
                config.getMaxRateAgeSeconds(),
                decimal(config.getAnomalyThresholdPercent()),
                pollIntervalMs
        );
    }

    public History getHistory(
            OffsetDateTime from,
            OffsetDateTime to
    ) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException(
                    "'from' must be earlier than 'to'"
            );
        }

        if (Duration.between(from, to).compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException(
                    "The maximum history range is seven days"
            );
        }

        String sql = """
                SELECT
                    RATE_SNAPSHOT_ID,
                    CURRENCY_PAIR,
                    MARKET_RATE,
                    PROVIDER_TIMESTAMP,
                    RECEIVED_AT,
                    VALIDATION_STATUS,
                    MOVEMENT_PERCENT
                FROM MARKET_RATE_SNAPSHOT
                WHERE CURRENCY_PAIR = ?
                  AND PROVIDER_TIMESTAMP >= ?
                  AND PROVIDER_TIMESTAMP < ?
                ORDER BY PROVIDER_TIMESTAMP ASC, RATE_SNAPSHOT_ID ASC
                FETCH FIRST 2001 ROWS ONLY
                """;

        List<RatePoint> rows = jdbc.query(
                sql,
                (rs, rowNumber) -> new RatePoint(
                        rs.getLong("RATE_SNAPSHOT_ID"),
                        rs.getString("CURRENCY_PAIR"),
                        decimal(rs.getBigDecimal("MARKET_RATE")),
                        timestamp(rs, "PROVIDER_TIMESTAMP"),
                        timestamp(rs, "RECEIVED_AT"),
                        rs.getString("VALIDATION_STATUS"),
                        decimal(rs.getBigDecimal("MOVEMENT_PERCENT"))
                ),
                "EUR/USD",
                from,
                to
        );

        boolean truncated = rows.size() > HISTORY_LIMIT;

        return new History(
                rows.stream().limit(HISTORY_LIMIT).toList(),
                truncated,
                HISTORY_LIMIT
        );
    }

    public AnomalyPage getAnomalies(
            String status,
            int page,
            int size
    ) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException(
                    "Page must be nonnegative and size must be between 1 and 100"
            );
        }

        String filter = status == null || status.isBlank()
                ? null
                : status.trim();

        if (filter != null && !ANOMALY_STATUSES.contains(filter)) {
            throw new IllegalArgumentException("Unknown anomaly status");
        }

        FxConfig config = getConfig();
        MarketRateSnapshot current = cache.get().orElse(null);
        Instant now = Instant.now();

        String where = """
                WHERE c.CURRENCY_PAIR = ?
                """;

        if (filter != null) {
            where += " AND a.STATUS = ? ";
        }

        String countSql = """
                SELECT COUNT(*)
                FROM RATE_ANOMALY a
                JOIN MARKET_RATE_SNAPSHOT c
                    ON c.RATE_SNAPSHOT_ID = a.RATE_SNAPSHOT_ID
                """ + where;

        Long count = filter == null
                ? jdbc.queryForObject(countSql, Long.class, "EUR/USD")
                : jdbc.queryForObject(
                        countSql,
                        Long.class,
                        "EUR/USD",
                        filter
                );

        String query = ANOMALY_SELECT + where + """
                ORDER BY a.CREATED_AT DESC, a.ANOMALY_ID DESC
                OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """;

        long offset = (long) page * size;

        Object[] parameters = filter == null
                ? new Object[]{"EUR/USD", offset, size}
                : new Object[]{"EUR/USD", filter, offset, size};

        List<Anomaly> items = jdbc.query(
                query,
                (rs, rowNumber) -> mapAnomaly(
                        rs,
                        config,
                        current,
                        now
                ),
                parameters
        );

        long total = count == null ? 0 : count;
        int totalPages = (int) Math.min(
                Integer.MAX_VALUE,
                (total + size - 1) / size
        );

        return new AnomalyPage(items, total, totalPages, page);
    }

    public Anomaly getAnomaly(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException(
                    "Anomaly ID must be positive"
            );
        }

        FxConfig config = getConfig();
        MarketRateSnapshot current = cache.get().orElse(null);
        Instant now = Instant.now();

        List<Anomaly> items = jdbc.query(
                ANOMALY_SELECT + """
                        WHERE a.ANOMALY_ID = ?
                          AND c.CURRENCY_PAIR = ?
                        """,
                (rs, rowNumber) -> mapAnomaly(
                        rs,
                        config,
                        current,
                        now
                ),
                id,
                "EUR/USD"
        );

        if (items.isEmpty()) {
            throw new ResourceNotFoundException("Anomaly not found");
        }

        return items.getFirst();
    }

    private Anomaly mapAnomaly(
            ResultSet rs,
            FxConfig config,
            MarketRateSnapshot current,
            Instant now
    ) throws SQLException {
        RatePoint candidate = joinedPoint(rs, "C_");
        RatePoint previous = joinedPoint(rs, "P_");

        String status = rs.getString("ANOMALY_STATUS");

        boolean reviewable = "OPEN".equals(status)
                || "ACKNOWLEDGED".equals(status);

        String blockedReason = null;

        if (!reviewable) {
            blockedReason = "Anomaly already reviewed";
        } else if (candidate == null) {
            blockedReason = "Candidate observation is missing";
        } else if (!RateValidationService.isProviderObservationFresh(
                candidate.observationTime(),
                config.getMaxRateAgeSeconds(),
                now
        )) {
            blockedReason =
                    "Candidate observation is missing, future-dated or stale";
        } else if (current != null
                && current.getProviderTimestamp() != null
                && !candidate.observationTime().isAfter(
                        current.getProviderTimestamp()
                )) {
            blockedReason =
                    "A newer or equal trusted observation already exists";
        }

        return new Anomaly(
                rs.getLong("ANOMALY_ID"),
                status,
                rs.getString("REASON"),
                decimal(rs.getBigDecimal("ANOMALY_MOVEMENT")),
                decimal(rs.getBigDecimal("THRESHOLD_PERCENT")),
                timestamp(rs, "ANOMALY_CREATED_AT"),
                rs.getString("REVIEWED_BY"),
                rs.getString("REVIEW_REASON"),
                timestamp(rs, "REVIEWED_AT"),
                candidate,
                previous,
                blockedReason == null,
                reviewable,
                blockedReason
        );
    }

    private RatePoint joinedPoint(
            ResultSet rs,
            String prefix
    ) throws SQLException {
        long id = rs.getLong(prefix + "ID");

        if (rs.wasNull()) {
            return null;
        }

        return new RatePoint(
                id,
                rs.getString(prefix + "PAIR"),
                decimal(rs.getBigDecimal(prefix + "RATE")),
                timestamp(rs, prefix + "OBSERVED"),
                timestamp(rs, prefix + "RECEIVED"),
                rs.getString(prefix + "VALIDATION"),
                decimal(rs.getBigDecimal(prefix + "MOVEMENT"))
        );
    }

    private RatePoint toPoint(MarketRateSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }

        return new RatePoint(
                snapshot.getId(),
                snapshot.getCurrencyPair(),
                decimal(snapshot.getMarketRate()),
                snapshot.getProviderTimestamp(),
                snapshot.getReceivedAt(),
                snapshot.getValidationStatus(),
                decimal(snapshot.getMovementPercent())
        );
    }

    private FxConfig getConfig() {
        return configRepository.findByCurrencyPair("EUR/USD")
                .orElseThrow(() -> new IllegalStateException(
                        "EUR/USD configuration missing"
                ));
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static OffsetDateTime timestamp(
            ResultSet rs,
            String column
    ) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class);
    }
}