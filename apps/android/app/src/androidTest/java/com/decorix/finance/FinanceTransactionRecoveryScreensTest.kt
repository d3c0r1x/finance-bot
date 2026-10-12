package com.decorix.finance

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinanceTransactionRecoveryScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pendingRepeatOffersOnlyExplicitRetryInRussianAndEnglish() {
        var normalRepeatCalls = 0
        var repeatRetryCalls = 0
        var normalVoidCalls = 0
        val language = mutableStateOf("ru")

        compose.setContent {
            MaterialTheme {
                TransactionMutationActionButtons(
                    transactionId = TRANSACTION_ID,
                    sourceStatus = source().status,
                    language = language.value,
                    canWrite = true,
                    busy = false,
                    canRepeat = true,
                    pendingRepeat = true,
                    pendingVoid = false,
                    onRepeat = { normalRepeatCalls++ },
                    onVoid = { normalVoidCalls++ },
                    onRepeatRetry = { repeatRetryCalls++ },
                )
            }
        }

        compose.onNodeWithText("Повторить запрос").assertIsDisplayed()
        compose.onAllNodesWithText("Повторить", substring = false).assertCountEquals(0)
        compose.onAllNodesWithTag("transaction-void-$TRANSACTION_ID").assertCountEquals(0)
        compose.onAllNodesWithText("Отменить", substring = false).assertCountEquals(0)
        assertEquals(0, normalRepeatCalls)
        assertEquals(0, repeatRetryCalls)
        assertEquals(0, normalVoidCalls)

        compose.runOnIdle { language.value = "en" }
        compose.onNodeWithText("Retry request").assertIsDisplayed()
        compose.onNodeWithTag("transaction-repeat-$TRANSACTION_ID").performClick()

        assertEquals(0, normalRepeatCalls)
        assertEquals(1, repeatRetryCalls)
        assertEquals(0, normalVoidCalls)
    }

    @Test fun pendingVoidForVoidedSourceOffersOnlyExplicitLocalizedRetry() {
        var normalRepeatCalls = 0
        var normalVoidCalls = 0
        var retried: MutationCheckpoint? = null
        val language = mutableStateOf("ru")
        val checkpoint = MutationCheckpoint(
            action = MutationAction.VOID,
            actorUserId = "member-1",
            tenantId = TENANT_ID,
            original = source(),
            occurredAt = null,
            idempotencyKey = "synthetic-void-key-1",
            sessionGeneration = 4L,
        )

        compose.setContent {
            MaterialTheme {
                TransactionHistoryCard(
                    transaction = source().copy(status = "voided", version = 5),
                    language = language.value,
                    canWrite = true,
                    busy = false,
                    canEdit = false,
                    onEdit = {},
                    onRepeat = { normalRepeatCalls++ },
                    onVoid = { normalVoidCalls++ },
                    pendingVoid = checkpoint,
                    onVoidRetry = { retried = it },
                )
            }
        }

        compose.onNodeWithText("Повторить отмену").assertIsDisplayed()
        compose.onAllNodesWithTag("transaction-repeat-$TRANSACTION_ID").assertCountEquals(0)
        compose.onAllNodesWithText("Отменить", substring = false).assertCountEquals(0)
        assertEquals(0, normalRepeatCalls)
        assertEquals(0, normalVoidCalls)
        assertEquals(null, retried)

        compose.onNodeWithTag("transaction-void-$TRANSACTION_ID").performClick()
        assertEquals(0, normalRepeatCalls)
        assertEquals(0, normalVoidCalls)
        assertEquals(checkpoint, retried)

        compose.runOnIdle { language.value = "en" }
        compose.onNodeWithText("Retry void").assertIsDisplayed()
    }

    @Test fun filteredOutCheckpointAppearsInDedicatedManualRecoverySection() {
        val checkpoint = MutationCheckpoint(
            action = MutationAction.REPEAT,
            actorUserId = "member-1",
            tenantId = TENANT_ID,
            original = source(),
            occurredAt = "2026-10-12T12:00:00Z",
            idempotencyKey = "synthetic-repeat-key-2",
            sessionGeneration = 5L,
        )
        var retried: MutationCheckpoint? = null
        var normalVoidCalls = 0

        compose.setContent {
            MaterialTheme {
                TransactionMutationRecoverySection(
                    checkpoints = listOf(checkpoint),
                    visibleTransactions = emptyList(),
                    language = "ru",
                    canWrite = true,
                    busy = false,
                    onRepeatRetry = { retried = it },
                    onVoidRetry = {},
                    onRepeat = {},
                    onVoid = { normalVoidCalls++ },
                )
            }
        }

        compose.onNodeWithText("Запросы, ожидающие повтора").assertIsDisplayed()
        compose.onNodeWithText("Synthetic taxi").assertIsDisplayed()
        compose.onNodeWithText("Повторить запрос").assertIsDisplayed()
        compose.onAllNodesWithTag("transaction-void-$TRANSACTION_ID").assertCountEquals(0)
        assertEquals(null, retried)
        assertEquals(0, normalVoidCalls)

        compose.onNodeWithTag("transaction-repeat-$TRANSACTION_ID").performClick()
        assertEquals(checkpoint, retried)
        assertEquals(0, normalVoidCalls)
    }

    @Test fun pendingVoidPreventsEditingTheSourceVersionNeededForRetry() {
        val checkpoint = MutationCheckpoint(
            action = MutationAction.VOID,
            actorUserId = "member-1",
            tenantId = TENANT_ID,
            original = source(),
            occurredAt = null,
            idempotencyKey = "synthetic-void-key-2",
            sessionGeneration = 5L,
        )

        compose.setContent {
            MaterialTheme {
                TransactionHistoryCard(
                    transaction = source(),
                    language = "ru",
                    canWrite = true,
                    busy = false,
                    canEdit = true,
                    onEdit = {},
                    onRepeat = {},
                    onVoid = {},
                    pendingVoid = checkpoint,
                )
            }
        }

        compose.onAllNodesWithTag("transaction-edit-$TRANSACTION_ID").assertCountEquals(0)
        compose.onNodeWithText("Повторить отмену").assertIsDisplayed()
    }

    @Test fun pendingVoidSuppressesNormalRepeatForPostedSource() {
        var normalRepeatCalls = 0
        var normalVoidCalls = 0
        var voidRetryCalls = 0

        compose.setContent {
            MaterialTheme {
                TransactionMutationActionButtons(
                    transactionId = TRANSACTION_ID,
                    sourceStatus = "posted",
                    language = "ru",
                    canWrite = true,
                    busy = false,
                    canRepeat = true,
                    pendingRepeat = false,
                    pendingVoid = true,
                    onRepeat = { normalRepeatCalls++ },
                    onVoid = { normalVoidCalls++ },
                    onVoidRetry = { voidRetryCalls++ },
                )
            }
        }

        compose.onNodeWithText("Повторить отмену").assertIsDisplayed()
        compose.onAllNodesWithTag("transaction-repeat-$TRANSACTION_ID").assertCountEquals(0)
        compose.onAllNodesWithText("Повторить", substring = false).assertCountEquals(0)
        assertEquals(0, normalRepeatCalls)
        assertEquals(0, normalVoidCalls)
        assertEquals(0, voidRetryCalls)

        compose.onNodeWithTag("transaction-void-$TRANSACTION_ID").performClick()
        assertEquals(0, normalRepeatCalls)
        assertEquals(0, normalVoidCalls)
        assertEquals(1, voidRetryCalls)
    }

    private fun source() = FinanceTransaction(
        id = TRANSACTION_ID,
        tenantId = TENANT_ID,
        type = "expense",
        amount = "247.35",
        currency = "RUB",
        categoryCode = "transport",
        subcategoryCode = "taxi",
        description = "Synthetic taxi",
        source = "manual",
        occurredAt = "2026-10-11T10:00:00Z",
        accountId = "account-1",
        status = "posted",
        version = 4,
        createdAt = "2026-10-11T10:00:00Z",
        memberName = "Test member",
        debtId = null,
        ownerUserId = "member-1",
    )

    private companion object {
        const val TENANT_ID = "7ed3e606-0000-4000-8000-000000000001"
        const val TRANSACTION_ID = "7ed3e606-0000-4000-8000-000000000002"
    }
}
