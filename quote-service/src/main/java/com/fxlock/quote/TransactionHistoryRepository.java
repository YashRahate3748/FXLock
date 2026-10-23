package com.fxlock.quote;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Read-only customer history. Settlement and ledger writes remain owned by M3. */
@Repository
@Transactional(readOnly=true)
public class TransactionHistoryRepository {
    public record Account(String walletId,String currency,String status) {}
    public record Entry(String ledgerId,String settlementId,String walletId,String customerName,String currency,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amountCredited,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal amountDebited,
        @JsonFormat(shape=JsonFormat.Shape.STRING) BigDecimal updatedBalance,Instant transactionDate) {}
    public record HistoryPage(List<Entry> entries,int page,int size,long total,boolean hasNext) {}
    private final JdbcTemplate jdbc;
    private final QuoteRepository customers;
    public TransactionHistoryRepository(JdbcTemplate jdbc,QuoteRepository customers) { this.jdbc=jdbc;this.customers=customers; }
    public List<Account> accounts(String principal) {
        return jdbc.query("SELECT WALLET_ID,CURRENCY_CODE,STATUS FROM WALLET WHERE CUSTOMER_ID=? ORDER BY WALLET_ID",
            (rs,n) -> new Account(rs.getString("WALLET_ID"),rs.getString("CURRENCY_CODE"),rs.getString("STATUS")),customers.customerId(principal));
    }
    public HistoryPage history(String principal,String walletId,int page,int size) {
        if(page<0 || page>1000000 || size<1 || size>100)
            throw new QuoteException(400,"INVALID_PAGE","Use a non-negative page and a page size between 1 and 100.");
        long customer=customers.customerId(principal);
        Long wallet=null;
        if(walletId!=null && !walletId.isBlank()) {
            try { wallet=Long.valueOf(walletId); if(wallet<=0) throw new NumberFormatException(); }
            catch(NumberFormatException e) { throw new QuoteException(400,"INVALID_ACCOUNT","Choose a valid account."); }
            // Do not reveal whether another customer's wallet exists.
            if(jdbc.queryForObject("SELECT COUNT(*) FROM WALLET WHERE WALLET_ID=? AND CUSTOMER_ID=?",Integer.class,wallet,customer)!=1)
                throw new QuoteException(404,"ACCOUNT_NOT_FOUND","Account not found for this customer.");
        }
        String from="""
            FROM TRANSACTION_HISTORY H JOIN WALLET W ON W.WALLET_ID=H.WALLET_ID
            JOIN SETTLEMENT S ON S.SETTLEMENT_ID=H.SETTLEMENT_ID
            WHERE W.CUSTOMER_ID=? AND H.CUSTOMER_ID=W.CUSTOMER_ID AND S.STATUS='COMPLETED'
            """;
        var args=new ArrayList<Object>();args.add(customer);
        if(wallet!=null) { from+=" AND H.WALLET_ID=?";args.add(wallet); }
        long total=jdbc.queryForObject("SELECT COUNT(*) "+from,Long.class,args.toArray());
        args.add((long)page*size);args.add(size);
        List<Entry> entries=jdbc.query("SELECT H.* "+from+" ORDER BY H.TRANSACTION_DATE DESC,H.LEDGER_ID DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
            (rs,n) -> new Entry(rs.getString("LEDGER_ID"),rs.getString("SETTLEMENT_ID"),rs.getString("WALLET_ID"),
                rs.getString("CUSTOMER_NAME"),rs.getString("CURRENCY_CODE"),rs.getBigDecimal("AMOUNT_CREDITED"),
                rs.getBigDecimal("AMOUNT_DEBITED"),rs.getBigDecimal("UPDATED_BALANCE"),
                rs.getObject("TRANSACTION_DATE",OffsetDateTime.class).toInstant()),args.toArray());
        return new HistoryPage(entries,page,size,total,((long)page+1)*size<total);
    }
}