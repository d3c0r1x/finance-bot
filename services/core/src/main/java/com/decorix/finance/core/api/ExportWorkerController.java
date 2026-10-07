package com.decorix.finance.core.api;

import com.decorix.finance.core.api.ExportWorkerApi.Claim;
import com.decorix.finance.core.api.ExportWorkerApi.CompleteRequest;
import com.decorix.finance.core.api.ExportWorkerApi.FailRequest;
import com.decorix.finance.core.api.ExportWorkerApi.SnapshotPage;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/exports")
public class ExportWorkerController {
    private final ExportWorkerService service;
    private final String serviceToken;

    public ExportWorkerController(ExportWorkerService service,
                                  @Value("${finance.exports.service-token:}") String serviceToken) {
        this.service = service;
        this.serviceToken = serviceToken;
    }

    @PostMapping("/claim")
    ResponseEntity<Claim> claim(@RequestHeader(name = "X-Export-Service-Token", required = false) String token) {
        authenticate(token);
        Claim claim = service.claim();
        return claim == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(claim);
    }

    @GetMapping("/{exportId}/rows")
    SnapshotPage rows(@PathVariable UUID exportId,
                      @RequestParam UUID leaseToken,
                      @RequestParam(defaultValue = "0") long afterRowNumber,
                      @RequestParam(defaultValue = "500") int limit,
                      @RequestHeader(name = "X-Export-Service-Token", required = false) String token) {
        authenticate(token);
        return service.page(exportId, leaseToken, afterRowNumber, limit);
    }

    @PostMapping("/{exportId}/complete")
    ResponseEntity<Void> complete(@PathVariable UUID exportId, @RequestBody CompleteRequest request,
                                  @RequestHeader(name = "X-Export-Service-Token", required = false) String token) {
        authenticate(token);
        if (!service.complete(exportId, request)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Export lease is stale");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{exportId}/fail")
    ResponseEntity<Void> fail(@PathVariable UUID exportId, @RequestBody FailRequest request,
                              @RequestHeader(name = "X-Export-Service-Token", required = false) String token) {
        authenticate(token);
        if (!service.fail(exportId, request)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Export lease is stale");
        return ResponseEntity.noContent().build();
    }

    private void authenticate(String token) {
        if (!TelegramServiceCredential.matches(serviceToken, token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid export service credential");
        }
    }
}
