package com.mphasis.assessment.domain;

import java.util.List;
import java.util.Map;

public record BatchResponse(int received, int processedNow, List<TransactionResult> transactions,
                            Map<TransactionStatus, Long> totalsByStatus) { }
