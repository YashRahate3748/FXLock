package com.fxlock.quote;

import java.math.*;
import java.time.*;
import java.util.List;
import org.springframework.stereotype.Component;
import static com.fxlock.quote.Models.*;

@Component
public class Pricing {
    private final DatabaseConfig config;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    public Pricing(DatabaseConfig config) { this.config=config; }

    public void validate(TrustedRate r, Instant now) { validate(r,now,config.current()); }
    public void validate(TrustedRate r, Instant now, DatabaseConfig.Settings settings) {
        if (r == null || r.snapshotId()==null || r.snapshotId().isBlank() || r.snapshotId().length()>80
            || !"EUR".equals(r.sourceCurrency()) || !"USD".equals(r.targetCurrency())
            || r.rate()==null || r.rate().signum()<=0 || r.rate().precision()>20 || r.rate().stripTrailingZeros().scale()>10
            || r.rate().compareTo(new BigDecimal("10000000000"))>=0
            || r.observedAt()==null || r.observedAt().isAfter(now)
            || r.unresolvedAnomaly()==null || r.providerHealthy()==null
            || r.relativeMovement()==null || r.relativeMovement().signum()<0) {
            throw new QuoteException(503,"RATE_UNAVAILABLE","M1 did not supply a valid trusted-rate snapshot.");
        }
        if (!settings.quotingEnabled() || !Boolean.TRUE.equals(r.trusted()) || !Boolean.TRUE.equals(r.quotingEnabled())) {
            throw new QuoteException(503,"RATE_UNAVAILABLE","New quotes are currently disabled or no trusted rate is available.");
        }
        if (Duration.between(r.observedAt(),now).compareTo(Duration.ofSeconds(settings.maxRateAgeSeconds()))>=0) {
            throw new QuoteException(503,"RATE_STALE","The market observation is too old to create a new quote.");
        }
    }
    public BigDecimal offeredRate(BigDecimal market) { return offeredRate(market,config.current()); }
    public BigDecimal offeredRate(BigDecimal market, DatabaseConfig.Settings settings) {
        BigDecimal multiplier=BigDecimal.ONE.subtract(settings.spreadBps().movePointLeft(4));
        BigDecimal rate=market.multiply(multiplier).setScale(10,RoundingMode.HALF_EVEN);
        if (rate.signum()<=0) throw new QuoteException(503,"RATE_UNAVAILABLE","The offered rate rounds to zero.");
        return rate;
    }
    public BigDecimal targetAmount(BigDecimal source, BigDecimal rate) {
        BigDecimal result=source.multiply(rate).setScale(2,RoundingMode.HALF_EVEN);
        if (result.signum()<=0 || result.compareTo(new BigDecimal("1000000000000000"))>=0)
            throw new QuoteException(422,"AMOUNT_OUT_OF_RANGE","The converted amount cannot be represented by the wallet schema.");
        return result;
    }
    public Confidence confidence(TrustedRate r, Instant now) { return confidence(r,now,config.current()); }
    public Confidence confidence(TrustedRate r, Instant now, DatabaseConfig.Settings settings) {
        BigDecimal age=new BigDecimal(Duration.between(r.observedAt(),now).toNanos()).movePointLeft(9);
        BigDecimal fresh=BigDecimal.ONE.subtract(age.divide(BigDecimal.valueOf(settings.maxRateAgeSeconds()),12,RoundingMode.HALF_EVEN)).max(BigDecimal.ZERO).min(BigDecimal.ONE);
        BigDecimal stability=BigDecimal.ONE.subtract(r.relativeMovement().divide(settings.anomalyThreshold(),12,RoundingMode.HALF_EVEN)).max(BigDecimal.ZERO).min(BigDecimal.ONE);
        BigDecimal freshness=fresh.multiply(HUNDRED).setScale(2,RoundingMode.HALF_EVEN);
        BigDecimal anomaly=r.unresolvedAnomaly()?BigDecimal.ZERO:HUNDRED;
        BigDecimal provider=r.providerHealthy()?HUNDRED:BigDecimal.ZERO;
        BigDecimal stable=stability.multiply(HUNDRED).setScale(2,RoundingMode.HALF_EVEN);
        if(r.inputs()!=null) {
            // Recompute freshness after the HTTP call; use the stricter local/provider score.
            freshness=freshness.min(component(r.inputs().freshness()));
            anomaly=component(r.inputs().anomaly()).min(anomaly);
            provider=component(r.inputs().provider());
            stable=component(r.inputs().stability());
        }
        BigDecimal score=freshness.multiply(new BigDecimal("0.40"))
            .add(anomaly.multiply(new BigDecimal("0.20")))
            .add(provider.multiply(new BigDecimal("0.20")))
            .add(stable.multiply(new BigDecimal("0.20"))).setScale(2,RoundingMode.HALF_EVEN);
        String label=score.compareTo(new BigDecimal("80"))>=0?"HIGH":score.compareTo(new BigDecimal("60"))>=0?"MEDIUM":"LOW";
        return new Confidence(score,label,List.of(
            "Freshness (40%): observation age "+age.setScale(1,RoundingMode.HALF_EVEN)+" seconds",
            "Anomaly (20%): "+(r.unresolvedAnomaly()?"a newer candidate is awaiting review; this quote uses the prior trusted rate":"no unresolved candidate"),
            "Provider health (20%): input score "+provider.toPlainString()+"/100",
            "Stability (20%): movement "+r.relativeMovement().multiply(HUNDRED).toPlainString()+"%",
            "Advisory score only; safety checks always apply."),freshness,anomaly,provider,stable);
    }
    private static BigDecimal component(BigDecimal value) {
        if(value==null || value.signum()<0 || value.compareTo(HUNDRED)>0)
            throw new QuoteException(503,"RATE_UNAVAILABLE","M1 confidence components must be between 0 and 100.");
        return value.setScale(2,RoundingMode.HALF_EVEN);
    }
}
