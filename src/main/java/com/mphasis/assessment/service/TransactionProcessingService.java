package com.mphasis.assessment.service;

import com.mphasis.assessment.domain.*;
import com.mphasis.assessment.persistence.TransactionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.*;

@Service
public class TransactionProcessingService {
    private static final Logger log = LoggerFactory.getLogger(TransactionProcessingService.class);
    private final TransactionStore store;
    private final Validator validator;
    private final int maxAttempts;
    public TransactionProcessingService(TransactionStore store, Validator validator, @Value("${processor.max-attempts:3}") int maxAttempts) {
        this.store = store; this.validator = validator; this.maxAttempts = Math.max(1, maxAttempts);
    }

    public BatchResponse processBatch(List<TransactionRequest> requests) {
        List<TransactionResult> submitted = new ArrayList<>();
        List<TransactionResult> invalid = new ArrayList<>();
        for (TransactionRequest r : requests) {
            Set<jakarta.validation.ConstraintViolation<TransactionRequest>> violations = r == null ? Set.of() : validator.validate(r);
            if (r == null || !violations.isEmpty()) {
                String id = r == null ? null : r.transactionId();
                String account = r == null ? null : r.accountId();
                Long seq = r == null ? null : r.sequenceNumber();
                String message = r == null ? "Transaction must not be null" : violations.stream().map(v -> v.getPropertyPath() + " " + v.getMessage()).sorted().reduce((a,b) -> a + "; " + b).orElse("Invalid transaction");
                invalid.add(new TransactionResult(id, account, seq, TransactionStatus.FAILED, 0, message, null));
            } else submitted.add(store.accept(r));
        }
        Map<String, TransactionResult> before = new HashMap<>();
        store.findAll().forEach(r -> before.put(r.transactionId(), r));
        boolean changed;
        do {
            changed = false;
            for (TransactionResult candidate : store.findReady()) {
                TransactionResult after = store.process(candidate, maxAttempts);
                if (after.status() == TransactionStatus.PROCESSED || after.status() == TransactionStatus.FAILED) changed = true;
                log.info("transaction={} account={} sequence={} status={} attempts={}", after.transactionId(), after.accountId(), after.sequenceNumber(), after.status(), after.attempts());
            }
        } while (changed && store.findReady().stream().anyMatch(r -> r.status() == TransactionStatus.PENDING_SEQUENCE || r.status() == TransactionStatus.RECEIVED || r.status() == TransactionStatus.RETRY_PENDING));
        List<TransactionResult> outcomes = new ArrayList<>(invalid);
        for (TransactionResult accepted : submitted) {
            TransactionResult current = store.findAll().stream().filter(r -> r.transactionId().equals(accepted.transactionId())).findFirst().orElse(accepted);
            if (accepted.status() == TransactionStatus.DUPLICATE) outcomes.add(accepted); else outcomes.add(current);
        }
        long processedBefore = before.values().stream().filter(r -> r.status() == TransactionStatus.PROCESSED).count();
        long processedAfter = store.counts().getOrDefault(TransactionStatus.PROCESSED, 0L);
        Map<TransactionStatus, Long> totals = new EnumMap<>(store.counts());
        totals.merge(TransactionStatus.FAILED, (long)invalid.size(), Long::sum);
        return new BatchResponse(requests.size(), (int)Math.max(0, processedAfter-processedBefore), outcomes, totals);
    }
    public List<TransactionResult> transactions() { return store.findAll(); }
    public BigDecimal balance(String accountId) { return store.balance(accountId); }
    public Map<TransactionStatus, Long> summary() { return store.counts(); }
}
