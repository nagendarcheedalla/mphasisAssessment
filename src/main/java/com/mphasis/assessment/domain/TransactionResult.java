package com.mphasis.assessment.domain;

import java.math.BigDecimal;

public record TransactionResult(String transactionId, String accountId, Long sequenceNumber,
                                TransactionStatus status, int attempts, String message,
                                BigDecimal resultingBalance) { }
