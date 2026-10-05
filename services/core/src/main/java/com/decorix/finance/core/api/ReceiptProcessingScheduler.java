package com.decorix.finance.core.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "finance.receipts.worker.enabled", havingValue = "true", matchIfMissing = true)
public class ReceiptProcessingScheduler {
    private final ReceiptProcessingService processing;

    public ReceiptProcessingScheduler(ReceiptProcessingService processing) {
        this.processing = processing;
    }

    @Scheduled(fixedDelayString = "${finance.receipts.worker.poll-delay:1000}")
    public void poll() {
        processing.processNext();
    }
}
