package com.decorix.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BudgetApplyErrorMessagesTest {
    @Test fun staleProposalConflictIsLocalized() {
        assertEquals(
            "Предложение устарело. Можно оставить текущие лимиты или создать новое предложение в разделе «Бюджеты».",
            BudgetApplyErrorMessages.message(412, "ru"),
        )
        assertEquals(
            "This proposal is stale. Keep the current limits or create a new proposal in Budgets.",
            BudgetApplyErrorMessages.message(412, "en"),
        )
    }

    @Test fun changedProposalConflictIsLocalized() {
        assertEquals(
            "Предложение недоступно. Можно оставить текущие лимиты или создать новое предложение в разделе «Бюджеты».",
            BudgetApplyErrorMessages.message(409, "ru"),
        )
        assertEquals(
            "This proposal is unavailable. Keep the current limits or create a new proposal in Budgets.",
            BudgetApplyErrorMessages.message(409, "en"),
        )
    }

    @Test fun forbiddenApplyIsLocalized() {
        assertEquals(
            "У вас нет прав применять это предложение бюджета.",
            BudgetApplyErrorMessages.message(403, "ru"),
        )
        assertEquals(
            "You do not have permission to apply this budget proposal.",
            BudgetApplyErrorMessages.message(403, "en"),
        )
    }

    @Test fun unrelatedStatusHasNoBudgetSpecificMessage() {
        assertNull(BudgetApplyErrorMessages.message(500, "ru"))
        assertNull(BudgetApplyErrorMessages.message(404, "en"))
    }
}
