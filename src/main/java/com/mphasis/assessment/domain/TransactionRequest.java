package com.mphasis.assessment.domain;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record TransactionRequest(
        @NotBlank String transactionId,
        @NotBlank String requestId,
        @NotBlank String accountId,
        @NotNull TransactionType type,
        @NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal amount,
        @NotBlank String currency,
        @NotNull @Positive Long sequenceNumber,
        boolean simulateTransientFailure) {
}
