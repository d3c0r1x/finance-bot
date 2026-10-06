package com.decorix.finance.core.api;

import com.decorix.finance.core.api.TenantApi.CreateTenantRequest;
import com.decorix.finance.core.api.TenantApi.TenantResponse;
import com.decorix.finance.core.api.TransactionApi.CreateRequest;
import com.decorix.finance.core.api.TransactionApi.Page;
import com.decorix.finance.core.api.TransactionApi.TransactionResponse;
import com.decorix.finance.core.api.TransactionDraftApi.DraftResponse;
import com.decorix.finance.core.api.TransactionDraftApi.UpdateRequest;
import com.decorix.finance.core.api.ReceiptApi.ReceiptResponse;
import com.decorix.finance.core.api.ReceiptApi.CategorySelection;
import com.decorix.finance.core.api.ReceiptApi.ItemInput;
import com.decorix.finance.core.api.ReceiptApi.ReceiptItemPage;
import com.decorix.finance.core.api.ReceiptApi.RepeatWarnings;
import com.decorix.finance.core.api.ReceiptApi.DuplicateCandidates;
import com.decorix.finance.core.api.ReceiptApi.DuplicateDecisionSelection;
import com.decorix.finance.core.api.ProductApi.ProductDecisionResponse;
import com.decorix.finance.core.api.ProductApi.ProductDecisionSelection;
import com.decorix.finance.core.api.ProductApi.ProductDecisionKeys;
import com.decorix.finance.core.api.ProductApi.PriceComparison;
import com.decorix.finance.core.api.ProductApi.ProductCatalogResponse;
import com.decorix.finance.core.api.ProductApi.ShoppingList;
import com.decorix.finance.core.api.InflationApi.PersonalInflation;
import com.decorix.finance.core.api.RecurringApi.RecurringProjection;
import com.decorix.finance.core.api.ReceiptProcessingApi.ReceiptProcessingJob;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/bff")
public class WebBffController {
    private final TenantService tenants;
    private final TransactionService transactions;
    private final MemberProfileService profiles;
    private final NotificationPreferencesService notificationPreferences;
    private final BudgetService budgets;
    private final DebtService debts;
    private final ReportService reports;
    private final TransactionDraftService drafts;
    private final ReceiptService receipts;
    private final TelegramLinkService telegramLinks;
    private final ProductPriceHistoryService productPriceHistory;
    private final ReceiptProcessingService receiptProcessing;
    private final ReceiptReadingService receiptReadings;
    private final AdviceEvidenceService evidence;

    public WebBffController(TenantService tenants, TransactionService transactions, MemberProfileService profiles,
                            NotificationPreferencesService notificationPreferences, BudgetService budgets,
                            DebtService debts, ReportService reports, TransactionDraftService drafts,
                            ReceiptService receipts, TelegramLinkService telegramLinks,
                            ProductPriceHistoryService productPriceHistory, ReceiptProcessingService receiptProcessing,
                            ReceiptReadingService receiptReadings, AdviceEvidenceService evidence) {
        this.tenants = tenants;
        this.transactions = transactions;
        this.profiles = profiles;
        this.notificationPreferences = notificationPreferences;
        this.budgets = budgets;
        this.debts = debts;
        this.reports = reports;
        this.drafts = drafts;
        this.receipts = receipts;
        this.telegramLinks = telegramLinks;
        this.productPriceHistory = productPriceHistory;
        this.receiptProcessing = receiptProcessing;
        this.receiptReadings = receiptReadings;
        this.evidence = evidence;
    }

    @GetMapping("/csrf")
    CsrfToken csrf(CsrfToken token) {
        return token;
    }

    @PostMapping(path = "/tenants/{tenantId}/receipts/photo-jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ReceiptProcessingJob> uploadReceiptPhoto(@PathVariable UUID tenantId,
            @RequestHeader("Idempotency-Key") String key, @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal OidcUser user) {
        ReceiptProcessingJob job = receiptProcessing.upload(tenantId, user.getSubject(), key,
                file.getOriginalFilename(), ReceiptPhotoMultipart.bytes(file));
        return ResponseEntity.accepted().body(job);
    }

    @GetMapping("/tenants/{tenantId}/receipt-jobs/{jobId}")
    ReceiptProcessingJob getReceiptJob(@PathVariable UUID tenantId, @PathVariable UUID jobId,
            @AuthenticationPrincipal OidcUser user) {
        return receiptProcessing.get(tenantId, user.getSubject(), jobId);
    }

    @GetMapping("/tenants/{tenantId}/receipts/{receiptId}/readings")
    ReceiptReadingApi.ReceiptOcrReading getReceiptReading(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @AuthenticationPrincipal OidcUser user) {
        return receiptReadings.get(tenantId, user.getSubject(), receiptId);
    }

    @GetMapping("/session")
    Map<String, Object> session(@AuthenticationPrincipal OidcUser user) {
        return user == null
                ? Map.of("authenticated", false, "displayName", "")
                : Map.of("authenticated", true,
                        "displayName", user.getFullName() == null ? "" : user.getFullName());
    }

    @GetMapping("/me/tenants")
    List<TenantResponse> listTenants(@AuthenticationPrincipal OidcUser user) {
        return tenants.list(user.getSubject());
    }

    @GetMapping("/tenants/{tenantId}/members")
    List<MemberProfileApi.MemberResponse> listMembers(@PathVariable UUID tenantId,
                                                       @AuthenticationPrincipal OidcUser user) {
        return profiles.listMembers(tenantId, user.getSubject());
    }

    @PostMapping("/me/telegram-link")
    TelegramLinkApi.LinkCodeResponse createTelegramLinkCode(@AuthenticationPrincipal OidcUser user) {
        return telegramLinks.createCode(user.getSubject());
    }

    @PostMapping("/tenants")
    ResponseEntity<TenantResponse> createTenant(@RequestBody CreateTenantRequest request,
                                                @AuthenticationPrincipal OidcUser user) {
        TenantResponse created = tenants.create(user.getSubject(), request);
        return ResponseEntity.created(java.net.URI.create("/bff/tenants/" + created.tenantId())).body(created);
    }

    @GetMapping("/tenants/{tenantId}/profile/me")
    MemberProfileApi.ProfileResponse getProfile(@PathVariable UUID tenantId,
                                                 @AuthenticationPrincipal OidcUser user) {
        return profiles.get(tenantId, user.getSubject());
    }

    @org.springframework.web.bind.annotation.PatchMapping("/tenants/{tenantId}/profile/me")
    MemberProfileApi.ProfileResponse updateProfile(@PathVariable UUID tenantId,
            @RequestBody MemberProfileApi.UpdateProfileRequest request,
            @AuthenticationPrincipal OidcUser user) {
        return profiles.update(tenantId, user.getSubject(), request);
    }

    @GetMapping("/tenants/{tenantId}/summary")
    TransactionApi.DashboardSummary summary(@PathVariable UUID tenantId,
            @RequestParam(required = false) YearMonth month, @AuthenticationPrincipal OidcUser user) {
        return transactions.summary(tenantId, user.getSubject(), month);
    }

    @GetMapping("/tenants/{tenantId}/transactions")
    Page listTransactions(@PathVariable UUID tenantId,
                          @RequestParam(defaultValue = "50") int pageSize,
                          @RequestParam(required = false) String cursor,
                          @RequestParam(required = false) LocalDate from,
                          @RequestParam(required = false) LocalDate to,
                          @RequestParam(required = false) String type,
                          @RequestParam(required = false) String search,
                          @RequestParam(required = false) String memberId,
                          @AuthenticationPrincipal OidcUser user) {
        return transactions.list(tenantId, user.getSubject(), pageSize, cursor, from, to, type, search, memberId);
    }

    @GetMapping("/tenants/{tenantId}/budgets")
    BudgetApi.Overview getBudgets(@PathVariable UUID tenantId, @AuthenticationPrincipal OidcUser user) {
        return budgets.get(tenantId, user.getSubject());
    }

    @GetMapping("/tenants/{tenantId}/notification-preferences")
    NotificationPreferencesApi.PreferencesResponse getNotificationPreferences(
            @PathVariable UUID tenantId, @AuthenticationPrincipal OidcUser user) {
        return notificationPreferences.get(tenantId, user.getSubject());
    }

    @PatchMapping("/tenants/{tenantId}/notification-preferences")
    NotificationPreferencesApi.PreferencesResponse updateNotificationPreferences(
            @PathVariable UUID tenantId, @RequestHeader("If-Match") String ifMatch,
            @RequestBody NotificationPreferencesApi.UpdateRequest request,
            @AuthenticationPrincipal OidcUser user) {
        if (ifMatch == null || !ifMatch.matches("\"(?:0|[1-9][0-9]*)\"")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "If-Match must contain a quoted version");
        }
        try {
            long version = Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
            return notificationPreferences.update(tenantId, user.getSubject(), version, request);
        } catch (NumberFormatException exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "If-Match version is invalid", exception);
        }
    }

    @GetMapping("/tenants/{tenantId}/reports/month")
    ReportApi.Report monthReport(@PathVariable UUID tenantId,
            @RequestParam(required = false) YearMonth month,
            @RequestParam(defaultValue = "personal") String scope,
            @AuthenticationPrincipal OidcUser user) {
        return reports.get(tenantId, user.getSubject(), "month", month, null, null, familyReport(scope));
    }

    @GetMapping("/tenants/{tenantId}/reports/period")
    ReportApi.Report periodReport(@PathVariable UUID tenantId,
            @RequestParam String period,
            @RequestParam(required = false) YearMonth month,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(defaultValue = "personal") String scope,
            @AuthenticationPrincipal OidcUser user) {
        return reports.get(tenantId, user.getSubject(), period, month, from, to, familyReport(scope));
    }

    @GetMapping("/tenants/{tenantId}/reports/family")
    ReportApi.Report familyReport(@PathVariable UUID tenantId,
            @RequestParam(defaultValue = "month") String period,
            @RequestParam(required = false) YearMonth month,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @AuthenticationPrincipal OidcUser user) {
        return reports.get(tenantId, user.getSubject(), period, month, from, to, true);
    }

    @org.springframework.web.bind.annotation.PutMapping("/tenants/{tenantId}/budgets/{budgetKey}")
    BudgetApi.Overview updateBudget(@PathVariable UUID tenantId, @PathVariable String budgetKey,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody BudgetApi.UpdateRequest request,
            @AuthenticationPrincipal OidcUser user) {
        if (ifMatch == null || !ifMatch.matches("\"(?:0|[1-9][0-9]*)\"")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "If-Match must contain a quoted version");
        }
        try {
            long version = Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
            return budgets.update(tenantId, user.getSubject(), budgetKey, key, version, request);
        } catch (NumberFormatException ex) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "If-Match version is invalid", ex);
        }
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/tenants/{tenantId}/budgets/personal-overrides")
    BudgetApi.Overview resetPersonalBudgets(@PathVariable UUID tenantId,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal OidcUser user) {
        return budgets.resetPersonal(tenantId, user.getSubject(), key);
    }

    @PostMapping("/tenants/{tenantId}/budget-proposals")
    ResponseEntity<BudgetApi.BudgetProposalResponse> proposeBudget(@PathVariable UUID tenantId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody BudgetApi.BudgetProposalRequest request,
            @AuthenticationPrincipal OidcUser user) {
        BudgetApi.BudgetProposalResponse proposal = budgets.propose(tenantId, user.getSubject(), key, request);
        return ResponseEntity.created(java.net.URI.create("/bff/tenants/" + tenantId
                + "/budget-proposals/" + proposal.id())).body(proposal);
    }

    @PostMapping("/tenants/{tenantId}/budget-proposals/history")
    ResponseEntity<BudgetApi.BudgetProposalResponse> proposeBudgetFromHistory(@PathVariable UUID tenantId,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal OidcUser user) {
        BudgetApi.BudgetProposalResponse proposal = budgets.proposeFromHistory(tenantId, user.getSubject(), key);
        return ResponseEntity.created(java.net.URI.create("/bff/tenants/" + tenantId
                + "/budget-proposals/" + proposal.id())).body(proposal);
    }

    @PostMapping("/tenants/{tenantId}/budget-proposals/{proposalId}/apply")
    BudgetApi.Overview applyBudgetProposal(@PathVariable UUID tenantId, @PathVariable UUID proposalId,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal OidcUser user) {
        return budgets.applyProposal(tenantId, user.getSubject(), proposalId, key);
    }

    @GetMapping("/tenants/{tenantId}/debts")
    DebtApi.Page listDebts(@PathVariable UUID tenantId, @AuthenticationPrincipal OidcUser user) {
        return debts.list(tenantId, user.getSubject());
    }

    @PostMapping("/tenants/{tenantId}/debts")
    ResponseEntity<DebtApi.DebtResponse> createDebt(@PathVariable UUID tenantId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody DebtApi.CreateRequest request,
            @AuthenticationPrincipal OidcUser user) {
        DebtApi.DebtResponse created = debts.create(tenantId, user.getSubject(), key, request);
        return ResponseEntity.created(java.net.URI.create("/bff/tenants/" + tenantId + "/debts/" + created.id())).body(created);
    }

    @PostMapping("/tenants/{tenantId}/debts/{debtId}/payments")
    DebtApi.PaymentResponse payDebt(@PathVariable UUID tenantId, @PathVariable UUID debtId,
            @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch,
            @RequestBody DebtApi.PaymentRequest request, @AuthenticationPrincipal OidcUser user) {
        return debts.pay(tenantId, user.getSubject(), debtId, key, parseBffVersion(ifMatch), request);
    }

    @org.springframework.web.bind.annotation.PutMapping("/tenants/{tenantId}/debts/{debtId}/balance")
    DebtApi.DebtResponse adjustDebtBalance(@PathVariable UUID tenantId, @PathVariable UUID debtId,
            @RequestHeader("Idempotency-Key") String key, @RequestHeader("If-Match") String ifMatch,
            @RequestBody DebtApi.BalanceAdjustmentRequest request, @AuthenticationPrincipal OidcUser user) {
        return debts.adjustBalance(tenantId, user.getSubject(), debtId, key, parseBffVersion(ifMatch), request);
    }

    @GetMapping("/tenants/{tenantId}/debts/{debtId}/forecast")
    DebtApi.ForecastResponse debtForecast(@PathVariable UUID tenantId, @PathVariable UUID debtId,
            @AuthenticationPrincipal OidcUser user) {
        return debts.forecast(tenantId, user.getSubject(), debtId);
    }

    private static long parseBffVersion(String value) {
        if (value == null || !value.matches("\\\"[1-9][0-9]*\\\"")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "If-Match must contain a quoted version");
        }
        try { return Long.parseLong(value.substring(1, value.length() - 1)); }
        catch (NumberFormatException ex) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "If-Match version is invalid", ex);
        }
    }

    private static boolean familyReport(String scope) {
        if ("family".equals(scope)) return true;
        if ("personal".equals(scope)) return false;
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Report scope must be personal or family");
    }

    @PostMapping("/tenants/{tenantId}/transactions")
    ResponseEntity<TransactionResponse> createTransaction(@PathVariable UUID tenantId,
                                                           @RequestHeader("Idempotency-Key") String key,
                                                           @RequestBody CreateRequest request,
                                                           @AuthenticationPrincipal OidcUser user) {
        TransactionResponse created = transactions.create(tenantId, user.getSubject(), key, request);
        return ResponseEntity.created(java.net.URI.create("/bff/tenants/" + tenantId
                + "/transactions/" + created.id())).body(created);
    }

    @org.springframework.web.bind.annotation.PatchMapping("/tenants/{tenantId}/transactions/{transactionId}")
    TransactionResponse updateTransaction(@PathVariable UUID tenantId,
            @PathVariable UUID transactionId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody TransactionApi.UpdateRequest request,
            @AuthenticationPrincipal OidcUser user) {
        if (ifMatch == null || !ifMatch.matches("\\\"[1-9][0-9]*\\\"")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "If-Match must contain a quoted version");
        }
        try {
            long version = Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
            return transactions.update(tenantId, user.getSubject(), transactionId, key, version, request);
        } catch (NumberFormatException ex) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "If-Match version is invalid", ex);
        }
    }

    @org.springframework.web.bind.annotation.PostMapping("/tenants/{tenantId}/transactions/{transactionId}/void")
    TransactionResponse voidTransaction(@PathVariable UUID tenantId,
            @PathVariable UUID transactionId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") String ifMatch,
            @AuthenticationPrincipal OidcUser user) {
        if (ifMatch == null || !ifMatch.matches("\\\"[1-9][0-9]*\\\"")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "If-Match must contain a quoted version");
        }
        try {
            long version = Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
            return transactions.voidTransaction(tenantId, user.getSubject(), transactionId, key, version);
        } catch (NumberFormatException ex) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "If-Match version is invalid", ex);
        }
    }

    @PostMapping("/tenants/{tenantId}/transaction-drafts")
    ResponseEntity<DraftResponse> createTransactionDraft(@PathVariable UUID tenantId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody TransactionDraftApi.CreateRequest request,
            @AuthenticationPrincipal OidcUser user) {
        DraftResponse draft = drafts.create(tenantId, user.getSubject(), key, request);
        return ResponseEntity.created(java.net.URI.create("/bff/tenants/" + tenantId
                + "/transaction-drafts/" + draft.id())).body(draft);
    }

    @PostMapping("/tenants/{tenantId}/receipts")
    ResponseEntity<ReceiptResponse> createReceipt(@PathVariable UUID tenantId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody ReceiptApi.CreateRequest request,
            @AuthenticationPrincipal OidcUser user) {
        ReceiptResponse receipt = receipts.create(tenantId, user.getSubject(), key, request);
        return ResponseEntity.created(java.net.URI.create("/bff/tenants/" + tenantId
                + "/receipts/" + receipt.id())).body(receipt);
    }

    @GetMapping("/tenants/{tenantId}/receipts/{receiptId}")
    ReceiptResponse getReceipt(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
                               @AuthenticationPrincipal OidcUser user) {
        return receipts.get(tenantId, user.getSubject(), receiptId);
    }

    @GetMapping("/tenants/{tenantId}/receipts/{receiptId}/items")
    ReceiptItemPage getReceiptItems(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @RequestParam(defaultValue = "1") int page, @AuthenticationPrincipal OidcUser user) {
        return receipts.getItems(tenantId, user.getSubject(), receiptId, page);
    }

    @GetMapping("/tenants/{tenantId}/receipts/{receiptId}/disputed-items")
    ReceiptItemPage getDisputedReceiptItems(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @RequestParam(defaultValue = "1") int page, @AuthenticationPrincipal OidcUser user) {
        return receipts.disputedItems(tenantId, user.getSubject(), receiptId, page);
    }

    @GetMapping("/tenants/{tenantId}/receipts/{receiptId}/repeat-warnings")
    RepeatWarnings getReceiptRepeatWarnings(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @AuthenticationPrincipal OidcUser user) {
        return receipts.repeatWarnings(tenantId, user.getSubject(), receiptId);
    }

    @GetMapping("/tenants/{tenantId}/receipts/{receiptId}/duplicate-candidates")
    DuplicateCandidates getReceiptDuplicateCandidates(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @AuthenticationPrincipal OidcUser user) {
        return receipts.duplicateCandidates(tenantId, user.getSubject(), receiptId);
    }

    @PutMapping("/tenants/{tenantId}/receipts/{receiptId}/duplicate-decision")
    ReceiptResponse decideReceiptDuplicate(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @RequestHeader("If-Match") String ifMatch, @RequestBody DuplicateDecisionSelection request,
            @AuthenticationPrincipal OidcUser user) {
        return receipts.decideDuplicate(tenantId, user.getSubject(), receiptId, parseBffVersion(ifMatch), request);
    }

    @PostMapping("/tenants/{tenantId}/receipts/{receiptId}/confirm")
    ReceiptResponse confirmReceipt(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @RequestHeader("If-Match") String ifMatch,
            @AuthenticationPrincipal OidcUser user) {
        return receipts.confirm(tenantId, user.getSubject(), receiptId, idempotencyKey, parseBffVersion(ifMatch));
    }

    @PostMapping("/tenants/{tenantId}/receipts/{receiptId}/items")
    ResponseEntity<ReceiptResponse> addReceiptItem(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @RequestHeader("If-Match") String ifMatch, @RequestBody ItemInput request,
            @AuthenticationPrincipal OidcUser user) {
        ReceiptResponse updated = receipts.addItem(tenantId, user.getSubject(), receiptId,
                parseBffVersion(ifMatch), request);
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED).body(updated);
    }

    @PatchMapping("/tenants/{tenantId}/receipts/{receiptId}/items/{itemId}")
    ReceiptResponse updateReceiptItem(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @PathVariable UUID itemId, @RequestHeader("If-Match") String ifMatch,
            @RequestBody ItemInput request, @AuthenticationPrincipal OidcUser user) {
        return receipts.updateItem(tenantId, user.getSubject(), receiptId, itemId,
                parseBffVersion(ifMatch), request);
    }

    @DeleteMapping("/tenants/{tenantId}/receipts/{receiptId}/items/{itemId}")
    ResponseEntity<Void> deleteReceiptItem(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @PathVariable UUID itemId, @RequestHeader("If-Match") String ifMatch,
            @AuthenticationPrincipal OidcUser user) {
        receipts.deleteItem(tenantId, user.getSubject(), receiptId, itemId, parseBffVersion(ifMatch));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/tenants/{tenantId}/receipts/{receiptId}/sync-total")
    ReceiptResponse syncReceiptTotal(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @RequestHeader("If-Match") String ifMatch, @AuthenticationPrincipal OidcUser user) {
        return receipts.syncTotal(tenantId, user.getSubject(), receiptId, parseBffVersion(ifMatch));
    }

    @PostMapping("/tenants/{tenantId}/receipts/{receiptId}/basket-review")
    ReceiptResponse reviewReceiptBasket(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @RequestHeader("If-Match") String ifMatch, @AuthenticationPrincipal OidcUser user) {
        return receipts.reviewBasket(tenantId, user.getSubject(), receiptId, parseBffVersion(ifMatch));
    }

    @PutMapping("/tenants/{tenantId}/products/{productKey}/decision")
    ProductDecisionResponse allowProduct(@PathVariable UUID tenantId, @PathVariable String productKey,
            @RequestBody ProductDecisionSelection request, @AuthenticationPrincipal OidcUser user) {
        return receipts.allowProduct(tenantId, user.getSubject(), productKey, request);
    }

    @GetMapping("/tenants/{tenantId}/products/decisions")
    ProductDecisionKeys allowedProducts(@PathVariable UUID tenantId, @AuthenticationPrincipal OidcUser user) {
        return receipts.allowedProducts(tenantId, user.getSubject());
    }

    @GetMapping("/tenants/{tenantId}/products/do-not-buy")
    AdviceEvidenceApi.Report doNotBuy(@PathVariable UUID tenantId, @AuthenticationPrincipal OidcUser user) {
        return evidence.get(tenantId, user.getSubject());
    }

    @GetMapping("/tenants/{tenantId}/products/price-history")
    PriceComparison productPriceHistory(@PathVariable UUID tenantId, @RequestParam UUID receiptId,
                                        @RequestParam UUID itemId, @AuthenticationPrincipal OidcUser user) {
        return productPriceHistory.get(tenantId, user.getSubject(), receiptId, itemId);
    }

    @GetMapping("/tenants/{tenantId}/products")
    ProductCatalogResponse productCatalog(@PathVariable UUID tenantId,
            @RequestParam(required = false) String query, @AuthenticationPrincipal OidcUser user) {
        return productPriceHistory.catalog(tenantId, user.getSubject(), query);
    }

    @GetMapping("/tenants/{tenantId}/shopping")
    ShoppingList shopping(@PathVariable UUID tenantId, @AuthenticationPrincipal OidcUser user) {
        return productPriceHistory.shopping(tenantId, user.getSubject());
    }

    @GetMapping("/tenants/{tenantId}/analytics/personal-inflation")
    PersonalInflation personalInflation(@PathVariable UUID tenantId, @AuthenticationPrincipal OidcUser user) {
        return productPriceHistory.personalInflation(tenantId, user.getSubject());
    }

    @GetMapping("/tenants/{tenantId}/analytics/recurring")
    RecurringProjection recurring(@PathVariable UUID tenantId, @AuthenticationPrincipal OidcUser user) {
        return productPriceHistory.recurring(tenantId, user.getSubject());
    }

    @PutMapping("/tenants/{tenantId}/analytics/recurring/{seriesId}/mute")
    RecurringProjection muteRecurring(@PathVariable UUID tenantId, @PathVariable String seriesId,
                                      @AuthenticationPrincipal OidcUser user) {
        return productPriceHistory.muteRecurring(tenantId, user.getSubject(), seriesId);
    }

    @DeleteMapping("/tenants/{tenantId}/analytics/recurring/{seriesId}/mute")
    RecurringProjection unmuteRecurring(@PathVariable UUID tenantId, @PathVariable String seriesId,
                                        @AuthenticationPrincipal OidcUser user) {
        return productPriceHistory.unmuteRecurring(tenantId, user.getSubject(), seriesId);
    }

    @PostMapping("/tenants/{tenantId}/shopping/{productKey}/bought")
    ShoppingList markShoppingBought(@PathVariable UUID tenantId, @PathVariable String productKey,
                                    @AuthenticationPrincipal OidcUser user) {
        return productPriceHistory.markShoppingBought(tenantId, user.getSubject(), productKey);
    }

    @PutMapping("/tenants/{tenantId}/suggestions/shopping/{productKey}/mute")
    ShoppingList muteShopping(@PathVariable UUID tenantId, @PathVariable String productKey,
                              @AuthenticationPrincipal OidcUser user) {
        return productPriceHistory.muteShopping(tenantId, user.getSubject(), productKey);
    }

    @DeleteMapping("/tenants/{tenantId}/suggestions/shopping/{productKey}/mute")
    ShoppingList unmuteShopping(@PathVariable UUID tenantId, @PathVariable String productKey,
                                @AuthenticationPrincipal OidcUser user) {
        return productPriceHistory.unmuteShopping(tenantId, user.getSubject(), productKey);
    }

    @DeleteMapping("/tenants/{tenantId}/products/{productKey}/decision")
    ResponseEntity<Void> revokeProduct(@PathVariable UUID tenantId, @PathVariable String productKey,
            @AuthenticationPrincipal OidcUser user) {
        receipts.revokeProduct(tenantId, user.getSubject(), productKey);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/tenants/{tenantId}/receipts/{receiptId}/category")
    ReceiptResponse selectReceiptCategory(@PathVariable UUID tenantId, @PathVariable UUID receiptId,
            @RequestHeader("If-Match") String ifMatch, @RequestBody CategorySelection request,
            @AuthenticationPrincipal OidcUser user) {
        return receipts.selectCategory(tenantId, user.getSubject(), receiptId, parseBffVersion(ifMatch), request);
    }

    @PatchMapping("/tenants/{tenantId}/transaction-drafts/{draftId}")
    DraftResponse updateTransactionDraft(@PathVariable UUID tenantId, @PathVariable UUID draftId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody UpdateRequest request,
            @AuthenticationPrincipal OidcUser user) {
        return drafts.update(tenantId, user.getSubject(), draftId, parseBffVersion(ifMatch), request);
    }

    @PostMapping("/tenants/{tenantId}/transaction-drafts/{draftId}/confirm")
    TransactionResponse confirmTransactionDraft(@PathVariable UUID tenantId, @PathVariable UUID draftId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader("If-Match") String ifMatch,
            @AuthenticationPrincipal OidcUser user) {
        return drafts.confirm(tenantId, user.getSubject(), draftId, key, parseBffVersion(ifMatch));
    }

    @DeleteMapping("/tenants/{tenantId}/transaction-drafts/{draftId}")
    ResponseEntity<Void> cancelTransactionDraft(@PathVariable UUID tenantId, @PathVariable UUID draftId,
            @RequestHeader("If-Match") String ifMatch,
            @AuthenticationPrincipal OidcUser user) {
        drafts.cancel(tenantId, user.getSubject(), draftId, parseBffVersion(ifMatch));
        return ResponseEntity.noContent().build();
    }
}
