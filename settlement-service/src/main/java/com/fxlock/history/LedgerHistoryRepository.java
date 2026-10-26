package com.fxlock.history;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Read model over the already installed history/funding tables; never writes or repairs history. */
@Repository
@Transactional(readOnly = true)
public class LedgerHistoryRepository {
    private final JdbcTemplate jdbc;

    public LedgerHistoryRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Account(String walletId, String currency, String status) {}
    // The original history fields are preserved; entryId/type/reference also describe funding rows.
    public record Entry(String ledgerId, String settlementId, String walletId, String customerName,
                        String currency, String amountCredited, String amountDebited, String updatedBalance,
                        OffsetDateTime transactionDate, String entryId, String transactionType, String transactionRef) {}
    public record HistoryPage(List<Entry> entries, int page, int size, long total, boolean hasNext) {}

    public List<Account> accounts(long customerId) {
        return jdbc.query("""
                SELECT wallet_id,currency_code,status FROM wallet WHERE customer_id=? ORDER BY currency_code,wallet_id
                """, (rs,n) -> new Account(rs.getBigDecimal("wallet_id").toBigIntegerExact().toString(),rs.getString("currency_code"),rs.getString("status")), customerId);
    }

    private static final String MOVEMENTS = """
            SELECT 'FX:' || CAST(h.ledger_id AS VARCHAR(40)) AS entry_id,
                   h.ledger_id, h.settlement_id, h.wallet_id, h.customer_name,
                   w.currency_code AS currency, h.amount_credited, h.amount_debited,
                   h.updated_balance, h.transaction_date,
                   CASE WHEN h.amount_debited>0 THEN 'FX_DEBIT' ELSE 'FX_CREDIT' END AS transaction_type,
                   h.settlement_id AS transaction_ref
            FROM transaction_history h JOIN wallet w ON w.wallet_id=h.wallet_id
            JOIN settlement s ON s.settlement_id=h.settlement_id
            WHERE w.customer_id=? AND h.customer_id=w.customer_id AND h.currency_code=w.currency_code
              AND s.status='COMPLETED'
            UNION ALL
            SELECT 'FUNDING:' || f.transaction_ref AS entry_id,
                   CAST(NULL AS NUMBER) AS ledger_id, CAST(NULL AS VARCHAR(36)) AS settlement_id,
                   f.wallet_id, c.full_name AS customer_name, w.currency_code AS currency,
                   CASE WHEN f.operation='DEPOSIT' THEN f.amount ELSE 0 END AS amount_credited,
                   CASE WHEN f.operation='WITHDRAWAL' THEN f.amount ELSE 0 END AS amount_debited,
                   f.balance_after AS updated_balance, f.created_at AS transaction_date,
                   f.operation AS transaction_type, f.transaction_ref
            FROM wallet_funding f JOIN wallet w ON w.wallet_id=f.wallet_id
            JOIN customer c ON c.customer_id=w.customer_id
            WHERE w.customer_id=? AND f.customer_id=w.customer_id AND f.currency_code=w.currency_code
            """;

    public HistoryPage history(long customerId, String walletId, int page, int size) {
        if (page<0 || page>1000000 || size<1 || size>100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_PAGE");
        }
        Long wallet=null;
        if (walletId!=null && !walletId.isBlank()) {
            try {
                if (!walletId.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
                wallet=Long.valueOf(walletId);
            } catch (NumberFormatException invalid) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_ACCOUNT");
            }
            Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM wallet WHERE wallet_id=? AND customer_id=?",Integer.class,wallet,customerId);
            if (count==null || count!=1) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ACCOUNT_NOT_FOUND");
        }
        String from=" FROM ("+MOVEMENTS+") movements"+(wallet==null ? "" : " WHERE wallet_id=?");
        var parameters=new ArrayList<Object>();
        parameters.add(customerId); parameters.add(customerId);
        if (wallet!=null) parameters.add(wallet);
        Long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,parameters.toArray());
        parameters.add((long)page*size); parameters.add(size);
        var entries=jdbc.query("SELECT *"+from+" ORDER BY transaction_date DESC,entry_id DESC OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",
                (rs,n) -> new Entry(rs.getBigDecimal("ledger_id")==null ? null : rs.getBigDecimal("ledger_id").toBigIntegerExact().toString(),
                        rs.getString("settlement_id"),rs.getBigDecimal("wallet_id").toBigIntegerExact().toString(),
                        rs.getString("customer_name"),rs.getString("currency"),rs.getBigDecimal("amount_credited").toPlainString(),
                        rs.getBigDecimal("amount_debited").toPlainString(),rs.getBigDecimal("updated_balance").toPlainString(),
                        rs.getObject("transaction_date",OffsetDateTime.class),rs.getString("entry_id"),
                        rs.getString("transaction_type"),rs.getString("transaction_ref")),parameters.toArray());
        if (total==null) throw new IllegalStateException("History count was unavailable");
        return new HistoryPage(List.copyOf(entries),page,size,total,((long)page+1)*size<total);
    }
}
