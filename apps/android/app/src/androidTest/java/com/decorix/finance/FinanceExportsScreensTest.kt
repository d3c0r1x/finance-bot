package com.decorix.finance

import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
                    pollIdentity = FinanceExportStatusIdentity("tenant-test", 1L),
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

    @Test fun activeExportWaitsAboutFiveRealSecondsBeforeFirstAutomaticRefresh() {
        val currentJob = mutableStateOf(job("processing"))
        val delayStartedAt = AtomicLong(0L)
        val refreshedAt = AtomicLong(0L)
        val delayStarted = CountDownLatch(1)
        val firstRefresh = CountDownLatch(1)

        compose.setContent {
            MaterialTheme {
                FinanceExportsSection(
                    role = "owner", language = "ru", timezone = "UTC", members = emptyList(),
                    membersLoading = false, job = currentJob.value, busy = false, error = null,
                    onCreate = {},
                    onRefresh = {
                        refreshedAt.compareAndSet(0L, SystemClock.elapsedRealtime())
                        currentJob.value = job("ready")
                        firstRefresh.countDown()
                    },
                    onDownload = {},
                    pollIdentity = FinanceExportStatusIdentity("tenant-test", 1L),
                    pollDelay = { millis ->
                        if (delayStartedAt.compareAndSet(0L, SystemClock.elapsedRealtime())) {
                            delayStarted.countDown()
                        }
                        // Compose's test dispatcher does not advance real wall time while the test waits.
                        withContext(Dispatchers.IO) { delay(millis) }
                    },
                )
            }
        }

        assertTrue("automatic polling delay did not start", delayStarted.await(2, TimeUnit.SECONDS))
        assertFalse("automatic refresh fired during the first four seconds", firstRefresh.await(4, TimeUnit.SECONDS))
        assertTrue("automatic refresh did not fire after its real-time delay", firstRefresh.await(8, TimeUnit.SECONDS))

        val elapsedMillis = refreshedAt.get() - delayStartedAt.get()
        assertTrue("automatic refresh fired before five seconds: ${elapsedMillis}ms", elapsedMillis >= 5_000L)
        assertTrue("automatic refresh exceeded the scheduling bound: ${elapsedMillis}ms", elapsedMillis <= 12_000L)
    }

    @Test fun leavingExportScreenCancelsPendingAutomaticStatusWait() {
        val visible = mutableStateOf(true)
        val waitStarted = AtomicBoolean(false)
        val waitCancelled = AtomicBoolean(false)
        val refreshes = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                if (visible.value) FinanceExportsSection(
                    role = "owner", language = "ru", timezone = "UTC", members = emptyList(),
                    membersLoading = false, job = job("queued"), busy = false, error = null,
                    onCreate = {}, onRefresh = refreshes::add, onDownload = {},
                    pollIdentity = FinanceExportStatusIdentity("tenant-test", 1L),
                    pollDelay = {
                        suspendCancellableCoroutine { continuation ->
                            waitStarted.set(true)
                            continuation.invokeOnCancellation { waitCancelled.set(true) }
                        }
                    },
                )
            }
        }

        compose.waitForIdle()
        assertTrue(waitStarted.get())
        assertEquals(emptyList<String>(), refreshes)

        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()

        assertTrue(waitCancelled.get())
        assertEquals(emptyList<String>(), refreshes)
    }

    @Test fun pausingAppCancelsPendingAutomaticStatusWaitWithoutRefreshing() {
        val owner = TestLifecycleOwner()
        compose.runOnIdle {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }
        val waitStarted = AtomicBoolean(false)
        val waitCancelled = AtomicBoolean(false)
        val refreshes = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme {
                    FinanceExportsSection(
                        role = "owner", language = "ru", timezone = "UTC", members = emptyList(),
                        membersLoading = false, job = job("processing"), busy = false, error = null,
                        onCreate = {}, onRefresh = refreshes::add, onDownload = {},
                        pollIdentity = FinanceExportStatusIdentity("tenant-test", 1L),
                        pollDelay = {
                            suspendCancellableCoroutine { continuation ->
                                waitStarted.set(true)
                                continuation.invokeOnCancellation { waitCancelled.set(true) }
                            }
                        },
                    )
                }
            }
        }

        compose.waitForIdle()
        assertFalse(waitStarted.get())
        compose.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START) }
        compose.waitForIdle()
        assertFalse(waitStarted.get())
        compose.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        compose.waitForIdle()
        assertTrue(waitStarted.get())
        compose.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        compose.waitForIdle()

        assertTrue(waitCancelled.get())
        assertEquals(emptyList<String>(), refreshes)
    }

    @Test fun tenantAndSessionChangesCancelPendingAutomaticStatusWait() {
        val identity = mutableStateOf(FinanceExportStatusIdentity("tenant-a", 10L))
        val waitStartedFor = mutableListOf<FinanceExportStatusIdentity>()
        val waitCancelledFor = mutableListOf<FinanceExportStatusIdentity>()
        val refreshes = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                FinanceExportsSection(
                    role = "owner", language = "en", timezone = "UTC", members = emptyList(),
                    membersLoading = false, job = job("queued"), busy = false, error = null,
                    onCreate = {}, onRefresh = refreshes::add, onDownload = {},
                    pollIdentity = identity.value,
                    pollDelay = {
                        val startedFor = identity.value
                        suspendCancellableCoroutine { continuation ->
                            waitStartedFor += startedFor
                            continuation.invokeOnCancellation { waitCancelledFor += startedFor }
                        }
                    },
                )
            }
        }

        compose.waitForIdle()
        assertEquals(listOf(FinanceExportStatusIdentity("tenant-a", 10L)), waitStartedFor)

        compose.runOnIdle { identity.value = FinanceExportStatusIdentity("tenant-b", 10L) }
        compose.waitForIdle()
        assertEquals(
            listOf(FinanceExportStatusIdentity("tenant-a", 10L)),
            waitCancelledFor,
        )
        assertEquals(
            listOf(FinanceExportStatusIdentity("tenant-a", 10L), FinanceExportStatusIdentity("tenant-b", 10L)),
            waitStartedFor,
        )

        compose.runOnIdle { identity.value = FinanceExportStatusIdentity("tenant-b", 11L) }
        compose.waitForIdle()

        assertEquals(
            listOf(
                FinanceExportStatusIdentity("tenant-a", 10L),
                FinanceExportStatusIdentity("tenant-b", 10L),
            ),
            waitCancelledFor,
        )
        assertEquals(
            listOf(
                FinanceExportStatusIdentity("tenant-a", 10L),
                FinanceExportStatusIdentity("tenant-b", 10L),
                FinanceExportStatusIdentity("tenant-b", 11L),
            ),
            waitStartedFor,
        )
        assertEquals(emptyList<String>(), refreshes)
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

    @Test fun ruAndEnglishRenderEveryExportStatusAndExactRowCount() {
        val language = mutableStateOf("ru")
        val currentJob = mutableStateOf(job("queued", rows = 17))
        compose.setContent {
            MaterialTheme {
                FinanceExportsSection(
                    role = "viewer", language = language.value, timezone = "UTC", members = emptyList(),
                    membersLoading = false, job = currentJob.value, busy = false, error = null,
                    onCreate = {}, onRefresh = {}, onDownload = {},
                    pollDelay = { suspendCancellableCoroutine { } },
                )
            }
        }

        val labels = listOf(
            "queued" to ("Экспорт: в очереди" to "Export: queued"),
            "processing" to ("Экспорт: готовится" to "Export: processing"),
            "ready" to ("Экспорт: готов" to "Export: ready"),
            "failed" to ("Экспорт: ошибка" to "Export: failed"),
            "expired" to ("Экспорт: истёк" to "Export: expired"),
        )
        for ((status, expected) in labels) {
            for ((locale, label, rowCount) in listOf(
                Triple("ru", expected.first, "Операций: 17"),
                Triple("en", expected.second, "Transactions: 17"),
            )) {
                compose.runOnIdle {
                    language.value = locale
                    currentJob.value = job(status, rows = 17)
                }
                compose.waitForIdle()
                compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(rowCount).performScrollTo().assertIsDisplayed()
            }
        }
    }

    @Test fun ruAndEnglishLocalizeDateAndCoreExportErrors() {
        val language = mutableStateOf("ru")
        val error = mutableStateOf<String?>(null)
        val currentJob = mutableStateOf<FinanceExportJob?>(null)
        val refreshes = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                FinanceExportsSection(
                    role = "viewer", language = language.value, timezone = "UTC", members = emptyList(),
                    membersLoading = false, job = currentJob.value, busy = false, error = error.value,
                    onCreate = {}, onRefresh = refreshes::add, onDownload = {},
                    pollDelay = { suspendCancellableCoroutine { } },
                )
            }
        }

        compose.onNodeWithTag("export-from-date").performTextClearance()
        compose.onNodeWithTag("export-from-date").performTextInput("invalid-date")
        for ((locale, expected) in listOf(
            "ru" to "Проверьте даты: период должен быть от 1 до 366 дней.",
            "en" to "Check the dates: the period must be 1 to 366 days.",
        )) {
            compose.runOnIdle { language.value = locale }
            compose.waitForIdle()
            compose.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
        }

        val mappings = listOf(
            "too_many_rows" to ("Слишком много операций для одного файла." to "Too many transactions for one export."),
            "forbidden" to ("Нет доступа к выбранной области экспорта." to "You cannot export the selected scope."),
            "invalid_request" to ("Проверьте период и участников экспорта." to "Check the export period and member selection."),
            "unauthorized" to ("Не удалось выполнить экспорт. Проверьте соединение и повторите." to
                "Export failed. Check the connection and retry."),
            "unavailable" to ("Не удалось выполнить экспорт. Проверьте соединение и повторите." to
                "Export failed. Check the connection and retry."),
            "network" to ("Не удалось выполнить экспорт. Проверьте соединение и повторите." to
                "Export failed. Check the connection and retry."),
        )
        for ((key, localized) in mappings) {
            for ((locale, expected) in listOf("ru" to localized.first, "en" to localized.second)) {
                compose.runOnIdle {
                    language.value = locale
                    error.value = key
                }
                compose.waitForIdle()
                compose.onNodeWithText(expected).performScrollTo().assertIsDisplayed()
            }
        }

        compose.runOnIdle { currentJob.value = job("processing"); error.value = "unavailable" }
        for ((locale, expectedError, refreshLabel) in listOf(
            Triple("ru", "Не удалось выполнить экспорт. Проверьте соединение и повторите.", "Обновить статус"),
            Triple("en", "Export failed. Check the connection and retry.", "Refresh status"),
        )) {
            compose.runOnIdle { language.value = locale }
            compose.waitForIdle()
            compose.onNodeWithText(expectedError).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(refreshLabel).performScrollTo().assertIsDisplayed().performClick()
        }
        assertEquals(listOf(EXPORT_ID, EXPORT_ID), refreshes)
    }

    @Test fun ruAndEnglishRenderReadyEmptyAndMissingLinkStates() {
        val language = mutableStateOf("ru")
        val currentJob = mutableStateOf(job("ready", rows = 0))
        val downloads = mutableListOf<String>()
        var expectedDownloads = 0
        compose.setContent {
            MaterialTheme {
                FinanceExportsSection(
                    role = "viewer", language = language.value, timezone = "UTC", members = emptyList(),
                    membersLoading = false, job = currentJob.value, busy = false, error = null,
                    onCreate = {}, onRefresh = {}, onDownload = downloads::add,
                )
            }
        }

        for ((locale, emptyCopy) in listOf(
            "ru" to "За период операций нет. CSV содержит только заголовки.",
            "en" to "No transactions for this period. The CSV contains its headers only.",
        )) {
            compose.runOnIdle {
                language.value = locale
                currentJob.value = job("ready", rows = 0)
            }
            compose.waitForIdle()
            compose.onNodeWithText(emptyCopy).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(if (locale == "ru") "Операций: 0" else "Transactions: 0")
                .performScrollTo().assertIsDisplayed()
            compose.runOnIdle { currentJob.value = job("ready", rows = 17) }
            compose.waitForIdle()
            compose.onNodeWithText(if (locale == "ru") "Скачать CSV" else "Download CSV")
                .performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithText(DOWNLOAD_URL).assertCountEquals(0)
            assertEquals(expectedDownloads, downloads.size)
            compose.onNodeWithTag("download-export").performScrollTo().performClick()
            expectedDownloads += 1
            assertEquals(expectedDownloads, downloads.size)
        }

        val missingLinkCopies = listOf(
            "ru" to ("Ссылка истекла или недоступна. Запросите свежую ссылку." to "Получить ссылку и скачать"),
            "en" to ("The link expired or is unavailable. Request a fresh link." to "Refresh link and download"),
        )
        for ((locale, copy) in missingLinkCopies) {
            compose.runOnIdle {
                language.value = locale
                currentJob.value = job("ready", rows = 17, downloadUrl = null)
            }
            compose.waitForIdle()
            compose.onNodeWithText(copy.first).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(copy.second).performScrollTo().assertIsDisplayed()
            assertEquals(expectedDownloads, downloads.size)
            compose.onAllNodesWithText(DOWNLOAD_URL).assertCountEquals(0)
            compose.onNodeWithTag("download-export").performScrollTo().performClick()
            expectedDownloads += 1
            assertEquals(expectedDownloads, downloads.size)
        }
    }

    @Test fun ruAndEnglishShowExplicitDuplicateRetryAndManualRefreshActions() {
        val language = mutableStateOf("ru")
        val retry = mutableStateOf(true)
        val error = mutableStateOf<String?>("network")
        val currentJob = mutableStateOf<FinanceExportJob?>(null)
        val creates = mutableListOf<FinanceExportRequest>()
        val refreshes = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                FinanceExportsSection(
                    role = "viewer", language = language.value, timezone = "UTC", members = emptyList(),
                    membersLoading = false, job = currentJob.value, busy = false, error = error.value,
                    retryNeedsConfirmation = retry.value,
                    onCreate = creates::add, onRefresh = refreshes::add, onDownload = {},
                )
            }
        }

        val retryCopies = listOf(
            "ru" to ("Предыдущий запрос мог создать файл. Повтор может создать дубликат." to
                "Повторить запрос (возможен дубль)"),
            "en" to ("The previous request may have created a file. Retrying may create a duplicate." to
                "Retry request (may create duplicate)"),
        )
        var retryCount = 0
        for ((locale, copy) in retryCopies) {
            compose.runOnIdle { language.value = locale }
            compose.waitForIdle()
            compose.onNodeWithText(copy.first).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(copy.second).performScrollTo().assertIsDisplayed().performClick()
            retryCount += 1
            assertEquals(retryCount, creates.size)
            compose.runOnIdle { retry.value = false; error.value = null }
            compose.waitForIdle()
            compose.runOnIdle { retry.value = true; error.value = "network" }
            compose.waitForIdle()
        }

        compose.runOnIdle { retry.value = false; error.value = null; currentJob.value = job("processing") }
        compose.waitForIdle()
        for ((locale, label) in listOf("ru" to "Обновить статус", "en" to "Refresh status")) {
            compose.runOnIdle { language.value = locale }
            compose.waitForIdle()
            compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
        }
        assertEquals(listOf(EXPORT_ID, EXPORT_ID), refreshes)
    }

    private fun show(
        role: String = "viewer",
        language: String = "ru",
        members: List<FinanceTenantMember> = emptyList(),
        error: String? = null,
        retryNeedsConfirmation: Boolean = false,
        onCreate: (FinanceExportRequest) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                FinanceExportsSection(
                    role = role, language = language, timezone = "Europe/Moscow", members = members,
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

    private fun job(status: String, rows: Int = 17, downloadUrl: String? = if (status == "ready") DOWNLOAD_URL else null) = FinanceExportJob(
        id = EXPORT_ID, status = status, formatVersion = "csv-v1", fromDate = "2026-10-01",
        toDate = "2026-10-31", memberId = null, allMembers = true, rowCount = rows,
        snapshotAt = "2026-10-11T08:00:00Z", createdAt = "2026-10-11T08:00:01Z",
        expiresAt = "2026-10-11T08:10:00Z", downloadUrl = downloadUrl,
    )

    private companion object {
        const val EXPORT_ID = "00000000-0000-4000-8000-000000000052"
        const val MEMBER_ID = "00000000-0000-4000-8000-000000000053"
        const val SECOND_MEMBER_ID = "00000000-0000-4000-8000-000000000054"
        const val DOWNLOAD_URL = "https://files.example.test/private/export.csv?signature=short-lived"
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
