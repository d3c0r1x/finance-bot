package com.decorix.finance.core.api;

import com.decorix.finance.core.api.BankImportApi.ImportPreview;
import com.decorix.finance.core.api.BankImportApi.CategorySelectionRequest;
import com.decorix.finance.core.api.BankImportApi.ConfirmRequest;
import com.decorix.finance.core.api.BankImportApi.RowSelectionRequest;
import com.decorix.finance.core.api.BankImportApi.UndoResult;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/imports")
public class BankImportController {
    private final BankImportService imports;

    public BankImportController(BankImportService imports) {
        this.imports = imports;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ImportPreview> create(@PathVariable UUID tenantId, @RequestPart("file") MultipartFile file,
                                         @AuthenticationPrincipal Jwt jwt) {
        ImportPreview created = imports.create(tenantId, jwt.getSubject(), file);
        return ResponseEntity.created(URI.create("/api/v1/tenants/" + tenantId + "/imports/" + created.id() + "/preview"))
                .body(created);
    }

    @GetMapping("/{importId}/preview")
    ImportPreview preview(@PathVariable UUID tenantId, @PathVariable UUID importId,
                          @AuthenticationPrincipal Jwt jwt) {
        return imports.get(tenantId, jwt.getSubject(), importId);
    }

    @PatchMapping("/{importId}/rows/{rowId}")
    ImportPreview selectRow(@PathVariable UUID tenantId, @PathVariable UUID importId, @PathVariable UUID rowId,
                            @RequestHeader("If-Match") String ifMatch,
                            @RequestBody RowSelectionRequest request, @AuthenticationPrincipal Jwt jwt) {
        return imports.selectRow(tenantId, jwt.getSubject(), importId, rowId, parseRevision(ifMatch), request);
    }

    @PutMapping("/{importId}/rows/{rowId}/category")
    ImportPreview selectCategory(@PathVariable UUID tenantId, @PathVariable UUID importId, @PathVariable UUID rowId,
            @RequestHeader("If-Match") String ifMatch, @RequestBody CategorySelectionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return imports.selectCategory(tenantId, jwt.getSubject(), importId, rowId, parseRevision(ifMatch), request);
    }

    @PostMapping("/{importId}/classify")
    ImportPreview classify(@PathVariable UUID tenantId, @PathVariable UUID importId,
            @RequestHeader("If-Match") String ifMatch, @AuthenticationPrincipal Jwt jwt) {
        return imports.classify(tenantId, jwt.getSubject(), importId, parseRevision(ifMatch));
    }

    @PostMapping("/{importId}/confirm")
    ImportPreview confirm(@PathVariable UUID tenantId, @PathVariable UUID importId,
                          @RequestHeader("If-Match") String ifMatch,
                          @RequestHeader("Idempotency-Key") String idempotencyKey,
                          @RequestBody ConfirmRequest request, @AuthenticationPrincipal Jwt jwt) {
        return imports.confirm(tenantId, jwt.getSubject(), importId, parseRevision(ifMatch), idempotencyKey, request);
    }

    @PostMapping("/{importId}/undo")
    ResponseEntity<UndoResult> undo(@PathVariable UUID tenantId, @PathVariable UUID importId,
                                    @RequestHeader("If-Match") String ifMatch,
                                    @RequestHeader("Idempotency-Key") String idempotencyKey,
                                    @AuthenticationPrincipal Jwt jwt) {
        UndoResult result = imports.undo(tenantId, jwt.getSubject(), importId, parseRevision(ifMatch), idempotencyKey);
        return ResponseEntity.status(result.conflicts().isEmpty() ? HttpStatus.OK : HttpStatus.CONFLICT).body(result);
    }

    private static long parseRevision(String ifMatch) {
        if (ifMatch == null || !ifMatch.matches("\\\"[1-9][0-9]*\\\"")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "If-Match must contain a quoted revision");
        }
        try {
            return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "If-Match revision is invalid", exception);
        }
    }
}
