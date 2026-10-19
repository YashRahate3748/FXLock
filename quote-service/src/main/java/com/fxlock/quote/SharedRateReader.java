package com.fxlock.quote;

import java.time.*;
import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import static com.fxlock.quote.Models.*;

/** Reads the actual M1-owned observation; never refreshes its timestamp on a quote request. */
@Component
public class SharedRateReader {
    private final JdbcTemplate jdbc;
    private final DatabaseConfig config;
    public SharedRateReader(JdbcTemplate jdbc,DatabaseConfig config) { this.jdbc=jdbc; this.config=config; }
    public TrustedRate latest() { return read(null); }
    public TrustedRate byId(String id) { return read(id); }
    private TrustedRate read(String id) {
        String filter=id==null?"TRUSTED_FLAG='Y' AND VALIDATION_STATUS='TRUSTED'":"RATE_SNAPSHOT_ID=?";
        var rows=jdbc.query("SELECT * FROM MARKET_RATE_SNAPSHOT WHERE CURRENCY_PAIR='EUR/USD' AND "+filter+
            " ORDER BY RECEIVED_AT DESC,RATE_SNAPSHOT_ID DESC FETCH FIRST 1 ROW ONLY",(rs,n) -> {
                OffsetDateTime observed=rs.getObject("PROVIDER_TIMESTAMP",OffsetDateTime.class);
                // A missing provider observation cannot prove freshness. Do not substitute fetch time.
                BigDecimal movement=rs.getBigDecimal("MOVEMENT_PERCENT");
                return new TrustedRate(rs.getString("RATE_SNAPSHOT_ID"),"EUR","USD",rs.getBigDecimal("MARKET_RATE"),
                    observed==null?null:observed.toInstant(),"Y".equals(rs.getString("TRUSTED_FLAG")) && "TRUSTED".equals(rs.getString("VALIDATION_STATUS")),
                    config.current().quotingEnabled(),unresolved(),true,movement==null?BigDecimal.ZERO:movement.abs().movePointLeft(2));
            },id==null?new Object[0]:new Object[]{Long.valueOf(id)});
        if(rows.isEmpty()) throw new QuoteException(503,"RATE_UNAVAILABLE","No trusted snapshot is available in the common database.");
        return rows.getFirst();
    }
    private boolean unresolved() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM RATE_ANOMALY WHERE STATUS IN ('OPEN','ACKNOWLEDGED')",Integer.class)>0;
    }
}
