package com.fxlock.quote;

import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** FX_CONFIG is the shared authority; environment defaults do not override it. */
@Component
public class DatabaseConfig {
    public record Settings(BigDecimal minAmount, BigDecimal maxAmount, BigDecimal spreadBps,
        int quoteSeconds, int maxRateAgeSeconds, BigDecimal anomalyThreshold, boolean quotingEnabled) {}
    private final JdbcTemplate jdbc;
    public DatabaseConfig(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Settings current() {
        var rows=jdbc.query("""
            SELECT * FROM FX_CONFIG WHERE CURRENCY_PAIR='EUR/USD' AND ACTIVE_FLAG='Y'
            AND BASE_CURRENCY='EUR' AND QUOTE_CURRENCY='USD'
            """, (rs,n) -> new Settings(rs.getBigDecimal("MIN_TRANSACTION_AMOUNT"),
                rs.getBigDecimal("MAX_TRANSACTION_AMOUNT"),rs.getBigDecimal("BUSINESS_SPREAD_BPS"),
                rs.getInt("QUOTE_VALIDITY_SECONDS"),rs.getInt("MAX_RATE_AGE_SECONDS"),
                rs.getBigDecimal("ANOMALY_THRESHOLD_PERCENT").movePointLeft(2),
                "Y".equals(rs.getString("QUOTING_ENABLED"))));
        if (rows.size()!=1) throw new QuoteException(503,"CONFIG_UNAVAILABLE","One active EUR/USD configuration is required.");
        var s=rows.getFirst();
        if (s.minAmount().signum()<=0 || s.maxAmount().compareTo(s.minAmount())<0 || s.spreadBps().signum()<0
            || s.spreadBps().compareTo(new BigDecimal("10000"))>=0 || s.quoteSeconds()<=0
            || s.maxRateAgeSeconds()<=0 || s.anomalyThreshold().signum()<=0)
            throw new QuoteException(503,"CONFIG_UNAVAILABLE","The shared quote configuration is invalid.");
        return s;
    }
}
