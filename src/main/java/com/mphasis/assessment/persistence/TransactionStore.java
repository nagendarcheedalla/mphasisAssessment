package com.mphasis.assessment.persistence;

import com.mphasis.assessment.domain.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface TransactionStore {
    TransactionResult accept(TransactionRequest request);
    List<TransactionResult> findReady();
    TransactionResult process(TransactionResult candidate, int maxAttempts);
    List<TransactionResult> findAll();
    BigDecimal balance(String accountId);
    Map<TransactionStatus, Long> counts();
}
