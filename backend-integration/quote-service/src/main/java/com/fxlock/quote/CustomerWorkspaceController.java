package com.fxlock.quote;

import java.security.Principal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Read-only customer workspace adapter. Financial writes remain in QuoteService and M3. */
@RestController
@RequestMapping("/api/customer")
public class CustomerWorkspaceController {
    private final JdbcTemplate jdbc;
    private final QuoteRepository customers;
    private final DatabaseConfig config;
    private final RateGateway rates;
    private final Pricing pricing;
    private final Clock clock;
    public CustomerWorkspaceController(JdbcTemplate jdbc,QuoteRepository customers,DatabaseConfig config,
        RateGateway rates,Pricing pricing,Clock clock) {
        this.jdbc=jdbc;this.customers=customers;this.config=config;this.rates=rates;this.pricing=pricing;this.clock=clock;
    }
    @GetMapping("/overview") Map<String,Object> overview(Principal principal) {
        long customer=customers.customerId(principal.getName());
        var settings=config.current();
        var profile=jdbc.queryForMap("SELECT FULL_NAME,CUSTOMER_REF FROM CUSTOMER WHERE CUSTOMER_ID=?",customer);
        var wallets=jdbc.query("SELECT WALLET_ID,CURRENCY_CODE,BALANCE,STATUS FROM WALLET WHERE CUSTOMER_ID=? ORDER BY WALLET_ID",
            (rs,n)->Map.of("walletId",rs.getString("WALLET_ID"),"currency",rs.getString("CURRENCY_CODE"),
                "balance",rs.getBigDecimal("BALANCE").toPlainString(),"status",rs.getString("STATUS")),customer);
        Map<String,Object> rate;
        try {
            var observed=rates.trustedRate();pricing.validate(observed,clock.instant(),settings);
            rate=Map.of("available",true,"marketRate",observed.rate().toPlainString(),"observedAt",observed.observedAt(),
                "message","Indicative market rate. Request a quote for your fixed offer.");
        } catch(QuoteException failure) {
            rate=Map.of("available",false,"message",failure.getMessage());
        }
        return Map.of("username",principal.getName(),"customerName",profile.get("FULL_NAME"),"wallets",wallets,"rate",rate,
            "settings",Map.of("minAmount",settings.minAmount().toPlainString(),"maxAmount",settings.maxAmount().toPlainString(),
                "spreadBps",settings.spreadBps().toPlainString(),"quoteSeconds",settings.quoteSeconds()),"serverTime",clock.instant());
    }
    @GetMapping("/settlements/{id}") Map<String,Object> settlement(@PathVariable String id,Principal principal) {
        long customer=customers.customerId(principal.getName());
        var rows=jdbc.query("""
            SELECT S.*,Q.SOURCE_CURRENCY,Q.TARGET_CURRENCY FROM SETTLEMENT S
            JOIN FX_QUOTE Q ON Q.QUOTE_ID=S.QUOTE_ID
            WHERE S.SETTLEMENT_ID=? AND S.CUSTOMER_ID=? AND Q.CUSTOMER_ID=?
            """,(rs,n)->{
                Map<String,Object> result=new LinkedHashMap<>();
                for(String key:List.of("SETTLEMENT_ID","QUOTE_ID","SOURCE_WALLET_ID","TARGET_WALLET_ID","SOURCE_CURRENCY","TARGET_CURRENCY","STATUS"))
                    result.put(key,rs.getString(key));
                for(String key:List.of("SOURCE_AMOUNT","TARGET_AMOUNT","CUSTOMER_RATE")) result.put(key,rs.getBigDecimal(key).toPlainString());
                var completed=rs.getObject("COMPLETED_AT",OffsetDateTime.class);
                result.put("COMPLETED_AT",completed==null?null:completed.toInstant());return result;
            },id,customer,customer);
        if(rows.isEmpty()) throw new QuoteException(404,"SETTLEMENT_NOT_FOUND","Settlement not found for this customer.");
        return rows.getFirst();
    }
}
