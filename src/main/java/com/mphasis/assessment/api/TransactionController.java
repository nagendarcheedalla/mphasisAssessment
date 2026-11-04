package com.mphasis.assessment.api;

import com.mphasis.assessment.domain.*;
import com.mphasis.assessment.service.TransactionProcessingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TransactionController {
    private final TransactionProcessingService service;
    public TransactionController(TransactionProcessingService service) { this.service = service; }

    @PostMapping("/batches")
    public BatchResponse submit(@RequestBody List<TransactionRequest> requests) {
        if (requests == null || requests.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Batch must contain at least one transaction");
        return service.processBatch(requests);
    }
    @GetMapping("/transactions") public List<TransactionResult> transactions() { return service.transactions(); }
    @GetMapping("/accounts/{accountId}/balance") public Map<String, Object> balance(@PathVariable String accountId) {
        BigDecimal balance = service.balance(accountId); return Map.of("accountId", accountId, "balance", balance);
    }
    @GetMapping("/summary") public Map<TransactionStatus, Long> summary() { return service.summary(); }
}
