package com.decorix.finance.core.api;

import java.util.Set;

/** Permissions placed in short-lived Telegram actor contexts after Core verifies current membership. */
public final class TelegramActorContextPolicy {
    private static final Set<String> READ = Set.of(
            "transaction.read", "budget.read", "debt.read", "report.read", "receipt.read");
    private static final Set<String> MEMBER = Set.of(
            "transaction.read", "transaction.write.own", "budget.read", "debt.read", "report.read", "receipt.read",
            "receipt.write.own", "debt.pay.allowed");
    private static final Set<String> ADMIN = Set.of(
            "transaction.read", "transaction.write.own", "transaction.write.any", "budget.read", "budget.write",
            "debt.read", "debt.write", "report.read", "receipt.read", "receipt.write.own", "debt.pay.allowed",
            "member.manage");
    private static final Set<String> OWNER = Set.of(
            "transaction.read", "transaction.write.own", "transaction.write.any", "budget.read", "budget.write",
            "debt.read", "debt.write", "report.read", "receipt.read", "receipt.write.own", "debt.pay.allowed",
            "member.manage", "billing.manage", "tenant.manage");

    private TelegramActorContextPolicy() {}

    public static Set<String> permissionsForRole(String role) {
        String normalized = "partner".equals(role) ? "member" : role;
        return switch (normalized == null ? "" : normalized) {
            case "owner" -> OWNER;
            case "admin" -> ADMIN;
            case "member" -> MEMBER;
            case "viewer" -> READ;
            default -> throw new IllegalArgumentException("Unsupported Telegram actor role");
        };
    }
}
