package com.decorix.finance.core.api;

import com.decorix.finance.core.api.ReceiptProcessingApi.ReceiptProcessingJob;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
public class ReceiptProcessingController {
    private final ReceiptProcessingService jobs;

    public ReceiptProcessingController(ReceiptProcessingService jobs) {
        this.jobs = jobs;
    }

    @PostMapping(path = "/receipts/photo-jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ReceiptProcessingJob> upload(@PathVariable UUID tenantId,
            @RequestHeader("Idempotency-Key") String key, @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) {
        ReceiptProcessingJob job = jobs.upload(tenantId, jwt.getSubject(), key,
                file.getOriginalFilename(), ReceiptPhotoMultipart.bytes(file));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
    }

    @GetMapping("/receipt-jobs/{jobId}")
    ReceiptProcessingJob get(@PathVariable UUID tenantId, @PathVariable UUID jobId,
            @AuthenticationPrincipal Jwt jwt) {
        return jobs.get(tenantId, jwt.getSubject(), jobId);
    }
}
