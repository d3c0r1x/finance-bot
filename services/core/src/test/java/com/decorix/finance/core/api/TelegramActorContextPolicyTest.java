package com.decorix.finance.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;

class TelegramActorContextPolicyTest {
    @Test
    void ownerAndAdminCanWriteSharedFinanceAndManageMembersButOnlyOwnerCanManageTenant() {
        Set<String> owner = TelegramActorContextPolicy.permissionsForRole("owner");
        Set<String> admin = TelegramActorContextPolicy.permissionsForRole("admin");

        org.junit.jupiter.api.Assertions.assertAll(
                () -> org.junit.jupiter.api.Assertions.assertTrue(owner.containsAll(Set.of(
                        "transaction.read", "transaction.write.own", "transaction.write.any",
                        "budget.read", "budget.write", "debt.read", "debt.write", "report.read",
                        "receipt.read", "receipt.write.own", "debt.pay.allowed", "member.manage",
                        "billing.manage", "tenant.manage"))),
                () -> org.junit.jupiter.api.Assertions.assertTrue(admin.containsAll(Set.of(
                        "transaction.read", "transaction.write.own", "transaction.write.any",
                        "budget.read", "budget.write", "debt.read", "debt.write", "report.read",
                        "receipt.read", "receipt.write.own", "debt.pay.allowed", "member.manage"))),
                () -> org.junit.jupiter.api.Assertions.assertEquals(false, admin.contains("billing.manage")),
                () -> org.junit.jupiter.api.Assertions.assertEquals(false, admin.contains("tenant.manage")));
    }

    @Test
    void memberAndLegacyPartnerCanReadAndWriteOwnItemsButNotSharedSettings() {
        Set<String> member = TelegramActorContextPolicy.permissionsForRole("member");

        assertEquals(member, TelegramActorContextPolicy.permissionsForRole("partner"));
        org.junit.jupiter.api.Assertions.assertTrue(member.containsAll(Set.of(
                "transaction.read", "transaction.write.own", "budget.read", "debt.read", "report.read",
                "receipt.read", "receipt.write.own", "debt.pay.allowed")));
        org.junit.jupiter.api.Assertions.assertEquals(false, member.contains("transaction.write.any"));
        org.junit.jupiter.api.Assertions.assertEquals(false, member.contains("budget.write"));
        org.junit.jupiter.api.Assertions.assertEquals(false, member.contains("member.manage"));
    }

    @Test
    void viewerIsReadOnlyAndUnknownRolesAreRejected() {
        assertEquals(Set.of("transaction.read", "budget.read", "debt.read", "report.read", "receipt.read"),
                TelegramActorContextPolicy.permissionsForRole("viewer"));
        assertThrows(IllegalArgumentException.class,
                () -> TelegramActorContextPolicy.permissionsForRole("support"));
    }
}
