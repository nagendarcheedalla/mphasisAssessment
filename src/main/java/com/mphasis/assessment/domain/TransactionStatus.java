package com.mphasis.assessment.domain;

public enum TransactionStatus {
    RECEIVED, PROCESSING, RETRY_PENDING, PENDING_SEQUENCE, PROCESSED, DUPLICATE, FAILED
}
