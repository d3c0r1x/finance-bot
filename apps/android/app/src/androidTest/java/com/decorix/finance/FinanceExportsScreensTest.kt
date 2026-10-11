package com.decorix.finance

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinanceExportsScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun viewerCanCreateOnlyOwnExportAfterExplicitClick() {
        val created = mutableListOf<FinanceExportRequest>()
        show(role = "viewer", onCreate = created::add)

        compose.onNodeWithText("Экспорт операций").assertIsDisplayed()
        compose.onAllNodesWithText("Кому выгрузить").assertCountEquals(0)
        assertEquals(emptyList<FinanceExportRequest>(), created)

        compose.onNodeWithText("Сформировать CSV").performScrollTo().performClick()

        assertEquals(1, created.size)
        assertEquals("csv-v1", created.single().formatVersion)
        assertEquals(null, created.single().memberId)
    }

    @Test fun ownerCanChooseAllMembersButDoesNotCreateUntilExplicitClick() {
        val created = mutableListOf<FinanceExportRequest>()
        show(role = "owner", members = listOf(member("Alice"), member("Bob")), onCreate = created::add)

        compose.onNodeWithText("Свои операции").performScrollTo().performClick()
        compose.onNodeWithText("Все участники").performClick()

        assertEquals(emptyList<FinanceExportRequest>(), created)
        compose.onNodeWithText("Сформировать CSV").performScrollTo().performClick()

        assertEquals("all", created.single().memberId)
    }

    @Test fun dateRangeOver366DaysIsRejectedBeforeTheCreateCallback() {
        val created = mutableListOf<FinanceExportRequest>()
        show(onCreate = created::add)

        compose.onNodeWithTag("export-from-date").performTextClearance()
        compose.onNodeWithTag("export-from-date").performTextInput("2025-10-10")
        compose.onNodeWithTag("export-to-date").performTextClearance()
        compose.onNodeWithTag("export-to-date").performTextInput("2026-10-11")
        compose.onNodeWithTag("create-export").performScrollTo().assertIsNotEnabled()

        assertEquals(emptyList<FinanceExportRequest>(), created)
        compose.onNodeWithText("Проверьте даты: период должен быть от 1 до 366 дней.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun createIsDisabledForReversedDateRange() {
        show()
        setDateRange("2026-10-12", "2026-10-11")

        compose.onNodeWithTag("create-export").performScrollTo().assertIsNotEnabled()
    }

    @Test fun createIsDisabledForUnparseableCalendarDate() {
        show()
        setDateRange("2026-02-30", "2026-03-01")

        compose.onNodeWithTag("create-export").performScrollTo().assertIsNotEnabled()
    }

    @Test fun oneDayTenantLocalRangeIsValid() {
        val created = mutableListOf<FinanceExportRequest>()
        show(onCreate = created::add)

        setDateRange("2026-02-28", "2026-02-28")
        compose.onNodeWithTag("create-export").performScrollTo().assertIsEnabled().performClick()
        assertEquals("2026-02-28", created.single().fromDate)
        assertEquals("2026-02-28", created.single().toDate)
    }

    @Test fun exactly366InclusiveDaysAreValid() {
        val created = mutableListOf<FinanceExportRequest>()
        show(onCreate = created::add)
        setDateRange("2025-10-11", "2026-10-11")
        compose.onNodeWithTag("create-export").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, created.size)
        assertEquals("2025-10-11", created.single().fromDate)
        assertEquals("2026-10-11", created.single().toDate)
    }

    @Test fun ambiguousCreateFailureNeedsExplicitDuplicateAwareRetryTap() {
        val created = mutableListOf<FinanceExportRequest>()
        show(error = "network", retryNeedsConfirmation = true, onCreate = created::add)

        compose.onNodeWithText("Предыдущий запрос мог создать файл. Повтор может создать дубликат.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Повторить запрос (возможен дубль)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("retry-export").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithTag("create-export").assertCountEquals(0)
        assertEquals(emptyList<FinanceExportRequest>(), created)

        compose.onNodeWithTag("retry-export").performClick()

        assertEquals(1, created.size)
    }

    @Test fun queuedAndProcessingJobsNeverOfferDownload() {
        val currentJob = mutableStateOf(job("queued"))
        compose.setContent {
            MaterialTheme {
                FinanceExportsSection(
                    role = "owner", language = "ru", timezone = "UTC", members = emptyList(),
                    membersLoading = false, job = currentJob.value, busy = false, error = null,
                    onCreate = {}, onRefresh = {}, onDownload = { error("pending export cannot download") },
                )
            }
        }
        compose.onNodeWithTag("export-job").assertIsDisplayed()
        compose.onAllNodesWithTag("download-export").assertCountEquals(0)
        compose.runOnIdle { currentJob.value = job("processing") }
        compose.onNodeWithTag("export-job").assertIsDisplayed()
        compose.onAllNodesWithTag("download-export").assertCountEquals(0)
    }

    @Test fun activeExportUsesTwelveFiveSecondPollsThenKeepsManualRefreshAvailable() {
        val waits = mutableListOf<Long>()
        val refreshes = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                FinanceExportsSection(
                    role = "owner", language = "ru", timezone = "UTC", members = emptyList(),
                    membersLoading = false, job = job("processing"), busy = false, error = null,
                    onCreate = {}, onRefresh = refreshes::add, onDownload = {},
                    pollDelay = { millis -> waits += millis },
                )
            }
        }

        compose.waitForIdle()

        assertEquals(List(12) { 5_000L }, waits)
        assertEquals(List(12) { EXPORT_ID }, refreshes)
        compose.onNodeWithText("Обновить статус").performScrollTo().performClick()
        assertEquals(13, refreshes.size)
        assertEquals(EXPORT_ID, refreshes.last())
    }

    @Test fun readyEmptyExportOpensOnlyTheReturnedSignedUrlAfterTap() {
        val opened = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                FinanceExportsSection(
                    role = "viewer", language = "ru", timezone = "UTC", members = emptyList(),
                    membersLoading = false, job = job("ready", rows = 0), busy = false, error = null,
                    onCreate = {}, onRefresh = {}, onDownload = opened::add,
                )
            }
        }

        compose.onNodeWithText("CSV содержит только заголовки.", substring = true)
            .performScrollTo().assertIsDisplayed()
        assertEquals(emptyList<String>(), opened)
        compose.onNodeWithTag("download-export").performScrollTo().performClick()
        assertEquals(listOf(EXPORT_ID), opened)
    }

    private fun show(
        role: String = "viewer",
        members: List<FinanceTenantMember> = emptyList(),
        error: String? = null,
        retryNeedsConfirmation: Boolean = false,
        onCreate: (FinanceExportRequest) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                FinanceExportsSection(
                    role = role, language = "ru", timezone = "Europe/Moscow", members = members,
                    membersLoading = false, job = null, busy = false, error = error,
                    retryNeedsConfirmation = retryNeedsConfirmation,
                    onCreate = onCreate, onRefresh = {}, onDownload = {},
                )
            }
        }
    }

    private fun setDateRange(from: String, to: String) {
        compose.onNodeWithTag("export-from-date").performTextClearance()
        compose.onNodeWithTag("export-from-date").performTextInput(from)
        compose.onNodeWithTag("export-to-date").performTextClearance()
        compose.onNodeWithTag("export-to-date").performTextInput(to)
    }

    private fun member(name: String) = FinanceTenantMember(
        userId = if (name == "Alice") MEMBER_ID else SECOND_MEMBER_ID, displayName = name, role = "member",
    )

    private fun job(status: String, rows: Int = 17) = FinanceExportJob(
        id = EXPORT_ID, status = status, formatVersion = "csv-v1", fromDate = "2026-10-01",
        toDate = "2026-10-31", memberId = null, allMembers = true, rowCount = rows,
        snapshotAt = "2026-10-11T08:00:00Z", createdAt = "2026-10-11T08:00:01Z",
        expiresAt = "2026-10-11T08:10:00Z", downloadUrl = if (status == "ready") DOWNLOAD_URL else null,
    )

    private companion object {
        const val EXPORT_ID = "00000000-0000-4000-8000-000000000052"
        const val MEMBER_ID = "00000000-0000-4000-8000-000000000053"
        const val SECOND_MEMBER_ID = "00000000-0000-4000-8000-000000000054"
        const val DOWNLOAD_URL = "https://files.example.test/private/export.csv?signature=short-lived"
    }
}
