package com.mphasis.assessment;

import com.mphasis.assessment.domain.*;
import com.mphasis.assessment.service.TransactionProcessingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.junit.jupiter.api.BeforeEach;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TransactionProcessingIntegrationTest {
    @Autowired TransactionProcessingService service;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clearTables() {
        jdbc.update("DELETE FROM ledger");
        jdbc.update("DELETE FROM accounts");
        jdbc.update("DELETE FROM transactions");
    }

    @Test void buffersFutureSequenceThenProcessesInOrder() {
        BatchResponse first = service.processBatch(List.of(tx("T2","R2","A",2,TransactionType.DEBIT,"40",false)));
        assertThat(first.transactions().get(0).status()).isEqualTo(TransactionStatus.PENDING_SEQUENCE);
        BatchResponse second = service.processBatch(List.of(tx("T1","R1","A",1,TransactionType.CREDIT,"100",false)));
        assertThat(second.transactions().get(0).status()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(service.transactions()).extracting(TransactionResult::status)
                .containsExactlyInAnyOrder(TransactionStatus.PROCESSED, TransactionStatus.PROCESSED);
        assertThat(service.balance("A")).isEqualByComparingTo("60.00");
    }

    @Test void duplicateTransactionAndRequestIdsDoNotRepeatBalanceEffect() {
        TransactionRequest original = tx("T1","R1","A",1,TransactionType.CREDIT,"25",false);
        service.processBatch(List.of(original));
        BatchResponse duplicate = service.processBatch(List.of(original));
        assertThat(duplicate.transactions().get(0).status()).isEqualTo(TransactionStatus.DUPLICATE);
        assertThat(service.balance("A")).isEqualByComparingTo("25.00");
    }

    @Test void transientFailureRetriesWithoutDuplicateLedgerEffect() {
        BatchResponse response = service.processBatch(List.of(tx("T1","R1","A",1,TransactionType.CREDIT,"25",true)));
        assertThat(response.transactions().get(0).status()).isEqualTo(TransactionStatus.PROCESSED);
        assertThat(response.transactions().get(0).attempts()).isEqualTo(2);
        assertThat(service.balance("A")).isEqualByComparingTo("25.00");
    }

    @Test void invalidRecordDoesNotStopOtherRecordsInBatch() {
        TransactionRequest invalid = tx("BAD","RBAD","A",1,TransactionType.CREDIT,"-1",false);
        TransactionRequest good = tx("GOOD","RGOOD","B",1,TransactionType.CREDIT,"10",false);
        BatchResponse response = service.processBatch(List.of(invalid, good));
        assertThat(response.transactions()).extracting(TransactionResult::status)
                .contains(TransactionStatus.FAILED, TransactionStatus.PROCESSED);
        assertThat(service.balance("B")).isEqualByComparingTo("10.00");
    }

    private TransactionRequest tx(String id, String request, String account, long sequence,
                                  TransactionType type, String amount, boolean failOnce) {
        return new TransactionRequest(id, request, account, type, new BigDecimal(amount), "INR", sequence, failOnce);
    }
}

