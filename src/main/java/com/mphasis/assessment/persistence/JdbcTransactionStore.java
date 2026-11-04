package com.mphasis.assessment.persistence;

import com.mphasis.assessment.domain.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Repository
public class JdbcTransactionStore implements TransactionStore {
    private final JdbcTemplate jdbc;

    public JdbcTransactionStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public TransactionResult accept(TransactionRequest r) {
        TransactionResult existing = findByTransactionId(r.transactionId());
        if (existing != null) return duplicateView(r, "transactionId already exists");
        existing = findByRequestId(r.requestId());
        if (existing != null) return duplicateView(r, "requestId already exists");
        Long sequenceCollision = jdbc.query("SELECT COUNT(*) FROM transactions WHERE account_id=? AND sequence_no=?", rs -> rs.next() ? rs.getLong(1) : 0L, r.accountId(), r.sequenceNumber());
        if (sequenceCollision != null && sequenceCollision > 0) return new TransactionResult(r.transactionId(), r.accountId(), r.sequenceNumber(), TransactionStatus.FAILED, 0, "Sequence already assigned to a different transaction", null);
        try {
            jdbc.update("INSERT INTO transactions(transaction_id, request_id, account_id, type, amount, currency, sequence_no, simulate_failure, status, attempts, message) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    r.transactionId(), r.requestId(), r.accountId(), r.type().name(), r.amount(), r.currency().toUpperCase(Locale.ROOT), r.sequenceNumber(), r.simulateTransientFailure(), TransactionStatus.RECEIVED.name(), 0, "Accepted");
        } catch (DuplicateKeyException race) {
            TransactionResult duplicate = findByTransactionId(r.transactionId());
            if (duplicate == null) duplicate = findByRequestId(r.requestId());
            return duplicate != null ? duplicateView(r, "transaction or request identifier already exists")
                    : new TransactionResult(r.transactionId(), r.accountId(), r.sequenceNumber(), TransactionStatus.FAILED, 0, "Identifier conflict", null);
        }
        return new TransactionResult(r.transactionId(), r.accountId(), r.sequenceNumber(), TransactionStatus.RECEIVED, 0, "Accepted", null);
    }

    private TransactionResult duplicateView(TransactionRequest r, String why) {
        return new TransactionResult(r.transactionId(), r.accountId(), r.sequenceNumber(), TransactionStatus.DUPLICATE, 0, why, null);
    }

    private TransactionResult findByTransactionId(String id) {
        return jdbc.query("SELECT * FROM transactions WHERE transaction_id=?", this::map, id).stream().findFirst().orElse(null);
    }
    private TransactionResult findByRequestId(String id) {
        return jdbc.query("SELECT * FROM transactions WHERE request_id=?", this::map, id).stream().findFirst().orElse(null);
    }

    @Override
    public List<TransactionResult> findReady() {
        return jdbc.query("SELECT * FROM transactions WHERE status IN ('RECEIVED','RETRY_PENDING','PENDING_SEQUENCE') ORDER BY account_id, sequence_no", this::map);
    }

    @Override
    @Transactional
    public TransactionResult process(TransactionResult candidate, int maxAttempts) {
        TransactionRow row = jdbc.query("SELECT transaction_id, request_id, account_id, type, amount, currency, sequence_no, simulate_failure, status, attempts, message FROM transactions WHERE transaction_id=? FOR UPDATE",
                rs -> rs.next() ? new TransactionRow(rs) : null, candidate.transactionId());
        if (row == null || isTerminal(row.status)) return result(row);
        long expected = nextSequence(row.accountId);
        if (row.sequence != expected) {
            String msg = row.sequence > expected ? "Waiting for sequence " + expected : "Sequence already passed; manual review required";
            TransactionStatus status = row.sequence > expected ? TransactionStatus.PENDING_SEQUENCE : TransactionStatus.FAILED;
            updateStatus(row.id, status, row.attempts, msg);
            return new TransactionResult(row.id, row.accountId, row.sequence, status, row.attempts, msg, balance(row.accountId));
        }
        if (row.currency == null || !row.currency.matches("[A-Z]{3}") || row.amount.signum() <= 0) {
            updateStatus(row.id, TransactionStatus.FAILED, row.attempts, "Invalid amount or currency");
            return new TransactionResult(row.id, row.accountId, row.sequence, TransactionStatus.FAILED, row.attempts, "Invalid amount or currency", balance(row.accountId));
        }
        if (row.type == TransactionType.DEBIT && balance(row.accountId).compareTo(row.amount) < 0) {
            updateStatus(row.id, TransactionStatus.FAILED, row.attempts, "Insufficient funds");
            return new TransactionResult(row.id, row.accountId, row.sequence, TransactionStatus.FAILED, row.attempts, "Insufficient funds", balance(row.accountId));
        }
        int attempts = row.attempts;
        updateStatus(row.id, TransactionStatus.PROCESSING, attempts, "Processing");
        while (true) {
            attempts++;
            if (row.simulateFailure && attempts == 1) {
                if (attempts >= maxAttempts) {
                    updateStatus(row.id, TransactionStatus.FAILED, attempts, "Transient failure; retry limit reached");
                    return new TransactionResult(row.id, row.accountId, row.sequence, TransactionStatus.FAILED, attempts, "Transient failure; retry limit reached", balance(row.accountId));
                }
                updateStatus(row.id, TransactionStatus.RETRY_PENDING, attempts, "Simulated transient failure; retrying");
                continue;
            }
            BigDecimal delta = row.type == TransactionType.CREDIT ? row.amount : row.amount.negate();
            jdbc.update("MERGE INTO accounts(account_id, balance, currency) KEY(account_id) VALUES(?, COALESCE((SELECT balance FROM accounts WHERE account_id=?), 0) + ?, ?)", row.accountId, row.accountId, delta, row.currency);
            jdbc.update("INSERT INTO ledger(transaction_id, account_id, sequence_no, type, amount, currency) VALUES(?,?,?,?,?,?)", row.id, row.accountId, row.sequence, row.type.name(), row.amount, row.currency);
            updateStatus(row.id, TransactionStatus.PROCESSED, attempts, attempts > 1 ? "Processed after retry" : "Processed");
            return new TransactionResult(row.id, row.accountId, row.sequence, TransactionStatus.PROCESSED, attempts, attempts > 1 ? "Processed after retry" : "Processed", balance(row.accountId));
        }
    }

    private long nextSequence(String account) {
        Long seq = jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no),0) FROM ledger WHERE account_id=?", Long.class, account);
        return seq == null ? 1 : seq + 1;
    }
    private void updateStatus(String id, TransactionStatus s, int attempts, String msg) {
        jdbc.update("UPDATE transactions SET status=?, attempts=?, message=?, updated_at=CURRENT_TIMESTAMP WHERE transaction_id=?", s.name(), attempts, msg, id);
    }
    private boolean isTerminal(TransactionStatus s) { return s == TransactionStatus.PROCESSED || s == TransactionStatus.FAILED; }
    private TransactionResult result(TransactionRow r) {
        return new TransactionResult(r.id, r.accountId, r.sequence, r.status, r.attempts, r.message, balance(r.accountId));
    }

    @Override public List<TransactionResult> findAll() {
        return jdbc.query("SELECT * FROM transactions ORDER BY account_id, sequence_no", this::map);
    }
    @Override public BigDecimal balance(String id) {
        return jdbc.query("SELECT balance FROM accounts WHERE account_id=?", rs -> rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO, id);
    }
    @Override public Map<TransactionStatus, Long> counts() {
        Map<TransactionStatus, Long> result = new EnumMap<>(TransactionStatus.class);
        for (TransactionStatus status : TransactionStatus.values()) result.put(status, 0L);
        jdbc.query("SELECT status, COUNT(*) total FROM transactions GROUP BY status", (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                result.put(TransactionStatus.valueOf(rs.getString(1)), rs.getLong(2)));
        return result;
    }
    private TransactionResult map(ResultSet rs, int n) throws SQLException {
        TransactionStatus status = TransactionStatus.valueOf(rs.getString("status"));
        String account = rs.getString("account_id");
        return new TransactionResult(rs.getString("transaction_id"), account, rs.getLong("sequence_no"), status, rs.getInt("attempts"), rs.getString("message"), balance(account));
    }

    private static final class TransactionRow {
        final String id, accountId, currency, message;
        final TransactionType type;
        final BigDecimal amount;
        final long sequence;
        final boolean simulateFailure;
        final TransactionStatus status;
        final int attempts;
        TransactionRow(ResultSet rs) throws SQLException {
            id=rs.getString("transaction_id"); accountId=rs.getString("account_id"); currency=rs.getString("currency"); message=rs.getString("message");
            type=TransactionType.valueOf(rs.getString("type")); amount=rs.getBigDecimal("amount"); sequence=rs.getLong("sequence_no");
            simulateFailure=rs.getBoolean("simulate_failure"); status=TransactionStatus.valueOf(rs.getString("status")); attempts=rs.getInt("attempts");
        }
    }
}
