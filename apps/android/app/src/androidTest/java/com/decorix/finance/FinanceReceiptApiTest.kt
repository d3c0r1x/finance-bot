package com.decorix.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class FinanceReceiptApiTest {
    private val server = MockWebServer()

    @get:Rule val serverRule = object : ExternalResource() {
        override fun before() = server.start()
        override fun after() = server.shutdown()
    }

    @Test fun photoUploadRefreshesAfter401AndReplaysSameMultipartFileAndKey() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}"""))
        server.enqueue(MockResponse().setBody("""{"access_token":"fresh-token","refresh_token":"refresh-next","expires_in":300}"""))
        server.enqueue(MockResponse().setResponseCode(202).setBody(jobJson(state = "queued", stage = "queued", progress = 0)))

        val api = api()
        api.saveTokens("stale-token", "refresh-token", System.currentTimeMillis() + 60_000)
        val photo = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x01, 0x02, 0x03, 0xff.toByte(), 0xd9.toByte())
        val key = "receipt-photo-test-key-0001"

        val job = api.uploadReceiptPhoto("tenant-17", photo, "receipt.jpg", "image/jpeg", key)

        assertEquals("job-29", job.id)
        val first = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val refresh = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val replay = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", first.method)
        assertEquals("/api/v1/tenants/tenant-17/receipts/photo-jobs", first.path)
        assertEquals("Bearer stale-token", first.getHeader("Authorization"))
        assertEquals(key, first.getHeader("Idempotency-Key"))
        assertEquals("multipart/form-data", first.getHeader("Content-Type")?.substringBefore(';'))
        assertEquals("POST", refresh.method)
        assertEquals("/protocol/openid-connect/token", refresh.path)
        assertEquals("grant_type=refresh_token&client_id=finance-android&refresh_token=refresh-token", refresh.body.readUtf8())
        assertEquals("POST", replay.method)
        assertEquals("/api/v1/tenants/tenant-17/receipts/photo-jobs", replay.path)
        assertEquals("Bearer fresh-token", replay.getHeader("Authorization"))
        assertEquals(key, replay.getHeader("Idempotency-Key"))
        assertEquals("multipart/form-data", replay.getHeader("Content-Type")?.substringBefore(';'))
        val firstBody = first.body.readByteArray()
        val replayBody = replay.body.readByteArray()
        assertEquals(firstBody.toList(), replayBody.toList())
        val multipart = replayBody.toString(Charsets.ISO_8859_1)
        assertTrue(multipart.contains("name=\"file\"; filename=\"receipt.jpg\""))
        assertTrue(multipart.contains("Content-Type: image/jpeg"))
        assertTrue(multipart.contains(photo.toString(Charsets.ISO_8859_1)))
    }

    @Test fun receiptJobAndReceiptResponsesParseIntoTypedModels() {
        server.enqueue(MockResponse().setBody(jobJson(state = "completed", stage = "complete", progress = 100)))
        server.enqueue(MockResponse().setBody(receiptJson()))
        val api = api()

        val job = api.receiptJob("tenant-17", "job-29")
        val receipt = api.receipt("tenant-17", "receipt-42")
        assertEquals("/api/v1/tenants/tenant-17/receipt-jobs/job-29",
            server.takeRequest(2, TimeUnit.SECONDS)?.path)
        assertEquals("/api/v1/tenants/tenant-17/receipts/receipt-42",
            server.takeRequest(2, TimeUnit.SECONDS)?.path)

        assertEquals("job-29", job.id)
        assertEquals("tenant-17", job.tenantId)
        assertEquals("completed", job.state)
        assertEquals("complete", job.stage)
        assertEquals(100, job.progressPercent)
        assertEquals("receipt-42", job.receiptId)
        assertEquals("receipt-42", receipt.id)
        assertEquals("review_required", receipt.state)
        assertEquals("RUB", receipt.currency)
        assertEquals("245.70", receipt.cashTotal)
        assertEquals("Магазин Тест", receipt.merchant)
        assertEquals(1, receipt.itemCount)
        assertEquals(1, receipt.items.size)
        assertEquals("Хлеб", receipt.items.single().name)
        assertNotNull(receipt.items.single().id)
        assertEquals(null, receipt.transactionId)
    }

    @Test fun receiptReadingFetchesOwnerScopedEvidenceAndParsesCoordinatesAndProvenance() {
        server.enqueue(MockResponse().setBody(receiptReadingJson()))
        val api = api()
        api.saveTokens("receipt-owner-token", "refresh-token", System.currentTimeMillis() + 60_000)

        val reading = api.receiptReading("tenant-17", "receipt-42")

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/api/v1/tenants/tenant-17/receipts/receipt-42/readings", request.path)
        assertEquals("Bearer receipt-owner-token", request.getHeader("Authorization"))
        assertEquals("TEST MARKET TOTAL 120.00", reading.text)
        assertEquals("tesseract", reading.provider)
        assertEquals("tesseract-5.3.0", reading.modelVersion)
        assertEquals("tesseract-ocr.v2", reading.promptVersion)
        assertEquals("120.00", reading.ocrTotal)
        assertEquals("Bread", reading.ocrItems.single().name)
        assertEquals("120.00", reading.ocrItems.single().lineSum)
        val box = reading.words.single()["box"] as Map<*, *>
        assertEquals(31, (box["x"] as Number).toInt())
        assertEquals(17, (box["y"] as Number).toInt())
        assertEquals(52, (box["width"] as Number).toInt())
        assertEquals("review_required", reading.reconciliation.decision)
        assertEquals("ocr", reading.reconciliation.selectedReader)
        assertEquals("120.00", reading.reconciliation.ocrItemsTotal)
        assertEquals("900.00", reading.reconciliation.visionItemsTotal)
        assertEquals("corroborated", reading.reconciliation.itemEvidence.single().status)
        assertEquals("Synthetic Vision Mart", reading.vision?.store)
        assertEquals("2026-10-04", reading.vision?.date)
        assertEquals("900.00", reading.vision?.total)
        assertEquals("synthetic-vision-v1", reading.vision?.modelVersion)
        assertEquals("vision-prompt-v1", reading.vision?.promptVersion)
        assertEquals("Model Bread", reading.vision?.items?.single()?.get("name"))
    }

    @Test fun receiptReadingPreservesNullableVisionValuesAndEmptyEvidence() {
        server.enqueue(MockResponse().setBody(receiptReadingJson(
            text = "",
            words = "[]",
            ocrTotal = "null",
            ocrItems = "[]",
            vision = """{"store":null,"date":null,"total":null,"items":[],"provider":"synthetic-vision",
                "modelVersion":"vision-v1","promptVersion":"vision-prompt-v1","fallbackReason":null}""",
            reconciliation = """{"algorithmVersion":"receipt-reconciliation.v1","decision":"review_required",
                "selectedReader":null,"mismatchFields":[],"ocrItemsTotal":null,"visionItemsTotal":null,
                "allowedDifference":"0.02","ocrItemsReconciled":false,"visionItemsReconciled":false,
                "itemEvidence":[],"suggestedTopUps":[]}""",
        )))

        val api = api()
        api.saveTokens("receipt-owner-token", "refresh-token", System.currentTimeMillis() + 60_000)
        val reading = api.receiptReading("tenant-17", "receipt-42")
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))

        assertEquals("/api/v1/tenants/tenant-17/receipts/receipt-42/readings", request.path)
        assertEquals("Bearer receipt-owner-token", request.getHeader("Authorization"))
        assertEquals("", reading.text)
        assertEquals(emptyList<Any>(), reading.words)
        assertEquals(null, reading.ocrTotal)
        assertEquals(emptyList<Any>(), reading.ocrItems)
        assertEquals(null, reading.vision?.store)
        assertEquals(null, reading.vision?.date)
        assertEquals(null, reading.vision?.total)
        assertEquals(emptyList<Any>(), reading.vision?.items)
        assertEquals(null, reading.reconciliation.selectedReader)
        assertEquals(null, reading.reconciliation.ocrItemsTotal)
        assertEquals(null, reading.reconciliation.visionItemsTotal)
    }

    @Test fun missingOwnerScopedReceiptReadingReturnsNotFoundWithoutInventingEvidence() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"not found"}"""))
        val api = api()
        api.saveTokens("receipt-owner-token", "refresh-token", System.currentTimeMillis() + 60_000)

        val failure = runCatching { api.receiptReading("tenant-17", "receipt-42") }.exceptionOrNull()

        assertTrue(failure is ApiFailure)
        assertEquals(404, (failure as ApiFailure).status)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/api/v1/tenants/tenant-17/receipts/receipt-42/readings", request.path)
        assertEquals("Bearer receipt-owner-token", request.getHeader("Authorization"))
    }

    @Test fun receiptDuplicateCandidatesFetchesAuthenticatedCandidatesAndExactAmounts() {
        server.enqueue(MockResponse().setBody(receiptDuplicateCandidatesJson()))
        val api = api()
        api.saveTokens("receipt-owner-token", "refresh-token", System.currentTimeMillis() + 60_000)

        val result = api.receiptDuplicateCandidates("tenant-17", "receipt-42")

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/api/v1/tenants/tenant-17/receipts/receipt-42/duplicate-candidates", request.path)
        assertEquals("Bearer receipt-owner-token", request.getHeader("Authorization"))
        assertEquals("receipt-42", result.receiptId)
        assertEquals("unknown", result.decision)
        assertEquals(1, result.candidates.size)
        assertEquals("00000000-0000-4000-8000-000000000091", result.candidates.single().id)
        assertEquals("245.70", result.candidates.single().cashTotal)
        assertEquals("Synthetic Market", result.candidates.single().merchant)
        assertEquals("2026-10-08T08:59:00Z", result.candidates.single().createdAt)
    }

    @Test fun decideReceiptDuplicatePutsExplicitIndependentChoiceWithQuotedVersion() {
        val response = org.json.JSONObject(receiptJson())
            .put("duplicateDecision", "independent")
            .put("version", 2)
        server.enqueue(MockResponse().setBody(response.toString()))
        val api = api()
        api.saveTokens("receipt-owner-token", "refresh-token", System.currentTimeMillis() + 60_000)

        val updated = api.decideReceiptDuplicate("tenant-17", "receipt-42", 1,
            "independent", null)

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("PUT", request.method)
        assertEquals("/api/v1/tenants/tenant-17/receipts/receipt-42/duplicate-decision", request.path)
        assertEquals("Bearer receipt-owner-token", request.getHeader("Authorization"))
        assertEquals("\"1\"", request.getHeader("If-Match"))
        val body = org.json.JSONObject(request.body.readUtf8())
        assertEquals(setOf("decision", "duplicateReceiptId"), body.keys().asSequence().toSet())
        assertEquals("independent", body.getString("decision"))
        assertTrue(body.isNull("duplicateReceiptId"))
        assertEquals("independent", updated.duplicateDecision)
        assertNull(updated.duplicateOfReceiptId)
        assertEquals(2L, updated.version)
        assertNull(updated.transactionId)
    }

    @Test fun confirmReceiptPostsWithoutBodyWithStableKeyAndParsesPostedTransaction() {
        val response = org.json.JSONObject(receiptJson())
            .put("state", "confirmed")
            .put("version", 2)
            .put("duplicateDecision", "independent")
            .put("transactionId", "00000000-0000-4000-8000-000000000092")
        server.enqueue(MockResponse().setBody(response.toString()))
        val api = api()
        api.saveTokens("receipt-owner-token", "refresh-token", System.currentTimeMillis() + 60_000)
        val key = "receipt-confirm-stable-key-0001"

        val confirmed = api.confirmReceipt("tenant-17", "receipt-42", 1, key)

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/v1/tenants/tenant-17/receipts/receipt-42/confirm", request.path)
        assertEquals("Bearer receipt-owner-token", request.getHeader("Authorization"))
        assertEquals(key, request.getHeader("Idempotency-Key"))
        assertEquals("\"1\"", request.getHeader("If-Match"))
        assertEquals("", request.body.readUtf8())
        assertEquals("confirmed", confirmed.state)
        assertEquals(2L, confirmed.version)
        assertEquals("00000000-0000-4000-8000-000000000092", confirmed.transactionId)
        assertEquals("245.70", confirmed.cashTotal)
    }

    @Test fun duplicateDecisionAndConfirmationPreserveStaleVersionFailures() {
        server.enqueue(MockResponse().setResponseCode(412).setBody("""{"detail":"stale_version"}"""))
        server.enqueue(MockResponse().setResponseCode(412).setBody("""{"detail":"stale_version"}"""))
        val api = api()
        api.saveTokens("receipt-owner-token", "refresh-token", System.currentTimeMillis() + 60_000)

        val decisionFailure = runCatching {
            api.decideReceiptDuplicate("tenant-17", "receipt-42", 3, "independent", null)
        }.exceptionOrNull()
        val confirmFailure = runCatching {
            api.confirmReceipt("tenant-17", "receipt-42", 3, "receipt-confirm-stable-key-0002")
        }.exceptionOrNull()

        assertTrue("Duplicate decision 412 must remain an API failure", decisionFailure is ApiFailure)
        assertEquals(412, (decisionFailure as ApiFailure).status)
        assertTrue("Confirmation 412 must remain an API failure", confirmFailure is ApiFailure)
        assertEquals(412, (confirmFailure as ApiFailure).status)
        val decision = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("PUT", decision.method)
        assertEquals("\"3\"", decision.getHeader("If-Match"))
        val confirm = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", confirm.method)
        assertEquals("\"3\"", confirm.getHeader("If-Match"))
        assertEquals("receipt-confirm-stable-key-0002", confirm.getHeader("Idempotency-Key"))
        assertEquals("", confirm.body.readUtf8())
    }

    @Test fun selectReceiptCategoryPatchesExactChoiceWithVersionAndPreservesProvenance() {
        val response = org.json.JSONObject(receiptJson())
            .put("categoryCode", "еда")
            .put("categorySource", "human")
            .put("categoryAlgorithmVersion", "receipt-category.v1")
            .put("alcoholShare", "0.2000")
            .put("leisureShare", "0.2500")
            .put("leisure", true)
            .put("version", 7)
        server.enqueue(MockResponse().setBody(response.toString()))
        val api = api()
        api.saveTokens("receipt-owner-token", "refresh-token", System.currentTimeMillis() + 60_000)

        val updated = api.selectReceiptCategory("tenant-17", "receipt-42", 6, "еда")

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("PATCH", request.method)
        assertEquals("/api/v1/tenants/tenant-17/receipts/receipt-42/category", request.path)
        assertEquals("Bearer receipt-owner-token", request.getHeader("Authorization"))
        assertEquals("\"6\"", request.getHeader("If-Match"))
        val body = org.json.JSONObject(request.body.readUtf8())
        assertEquals(setOf("categoryCode"), body.keys().asSequence().toSet())
        assertEquals("еда", body.getString("categoryCode"))
        assertEquals("еда", updated.categoryCode)
        assertEquals("human", updated.categorySource)
        assertEquals("receipt-category.v1", updated.categoryAlgorithmVersion)
        assertEquals("0.2000", updated.alcoholShare)
        assertEquals("0.2500", updated.leisureShare)
        assertTrue(updated.leisure)
        assertEquals(7L, updated.version)
        assertNull(updated.transactionId)
    }

    @Test fun selectReceiptCategoryPreservesStaleVersionFailure() {
        server.enqueue(MockResponse().setResponseCode(412).setBody("""{"detail":"stale_version"}"""))
        val api = api()
        api.saveTokens("receipt-owner-token", "refresh-token", System.currentTimeMillis() + 60_000)

        val failure = runCatching {
            api.selectReceiptCategory("tenant-17", "receipt-42", 6, "еда")
        }.exceptionOrNull()

        assertTrue("Category selection 412 must remain an API failure", failure is ApiFailure)
        assertEquals(412, (failure as ApiFailure).status)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("PATCH", request.method)
        assertEquals("/api/v1/tenants/tenant-17/receipts/receipt-42/category", request.path)
        assertEquals("Bearer receipt-owner-token", request.getHeader("Authorization"))
        assertEquals("\"6\"", request.getHeader("If-Match"))
        assertEquals("еда", org.json.JSONObject(request.body.readUtf8()).getString("categoryCode"))
    }

    @Test fun reviewReceiptBasketPostsAuthenticatedVersionedEmptyRequestAndParsesAdviceWithoutChangingMoney() {
        val response = org.json.JSONObject(receiptJson())
            .put("version", 5)
            .put("cashTotal", "245.70")
            .put("itemsTotal", "245.70")
        val items = org.json.JSONArray()
            .put(org.json.JSONObject()
                .put("id", "item-rule-1").put("name", "Пиво безалкогольное")
                .put("quantity", "1").put("unitPrice", "125.00").put("lineSum", "125.00")
                .put("productKey", "pivo-bezalkogolnoe").put("provenance", "ocr")
                .put("confidence", 0.92).put("categoryCode", "еда")
                .put("verdict", "harmful").put("advice", "Ограничить покупку")
                .put("reviewReason", "Правило исключает этот товар")
                .put("reviewAction", "limit purchase").put("verdictSource", "rule")
                .put("reviewProvider", "ollama").put("reviewModelVersion", "basket-test-v1")
                .put("reviewPromptVersion", "receipt-basket.v1")
                .put("reviewAlgorithmVersion", "receipt-basket-review.v1").put("version", 3))
            .put(org.json.JSONObject()
                .put("id", "item-model-2").put("name", "Хлеб")
                .put("quantity", "1").put("unitPrice", "120.70").put("lineSum", "120.70")
                .put("productKey", org.json.JSONObject.NULL).put("provenance", "ocr")
                .put("confidence", org.json.JSONObject.NULL).put("categoryCode", "еда")
                .put("verdict", "useful").put("advice", "Оставить в корзине")
                .put("reviewReason", "Основной продукт").put("reviewAction", "keep")
                .put("verdictSource", "model").put("reviewProvider", "ollama")
                .put("reviewModelVersion", "basket-test-v1").put("reviewPromptVersion", "receipt-basket.v1")
                .put("reviewAlgorithmVersion", "receipt-basket-review.v1").put("version", 4))
        response.put("items", items).put("itemCount", 2)
        server.enqueue(MockResponse().setBody(response.toString()))
        val api = api()
        api.saveTokens("receipt-writer-token", "refresh-token", System.currentTimeMillis() + 60_000)

        val reviewed = api.reviewReceiptBasket("tenant-17", "receipt-42", 4)

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/v1/tenants/tenant-17/receipts/receipt-42/basket-review", request.path)
        assertEquals("Bearer receipt-writer-token", request.getHeader("Authorization"))
        assertEquals("\"4\"", request.getHeader("If-Match"))
        assertEquals("", request.body.readUtf8())
        assertEquals(5L, reviewed.version)
        assertEquals("245.70", reviewed.cashTotal)
        assertEquals("245.70", reviewed.itemsTotal)
        assertEquals(2, reviewed.itemCount)
        assertEquals("harmful", reviewed.items[0].verdict)
        assertEquals("Ограничить покупку", reviewed.items[0].advice)
        assertEquals("Правило исключает этот товар", reviewed.items[0].reviewReason)
        assertEquals("limit purchase", reviewed.items[0].reviewAction)
        assertEquals("rule", reviewed.items[0].verdictSource)
        assertEquals("ollama", reviewed.items[0].reviewProvider)
        assertEquals("basket-test-v1", reviewed.items[0].reviewModelVersion)
        assertEquals("receipt-basket.v1", reviewed.items[0].reviewPromptVersion)
        assertEquals("receipt-basket-review.v1", reviewed.items[0].reviewAlgorithmVersion)
        assertEquals(3L, reviewed.items[0].version)
        assertEquals("useful", reviewed.items[1].verdict)
        assertEquals("model", reviewed.items[1].verdictSource)
        assertEquals("120.70", reviewed.items[1].lineSum)
    }

    @Test fun reviewReceiptBasketPreservesIdentityAndReviewMetadataForDuplicateNamesAndLegacyUnknownSource() {
        val firstId = "00000000-0000-4000-8000-000000000181"
        val secondId = "00000000-0000-4000-8000-000000000182"
        val response = org.json.JSONObject(receiptJson()).put("version", 2)
        val items = org.json.JSONArray()
            .put(org.json.JSONObject()
                .put("id", firstId).put("name", "Одинаковый товар")
                .put("quantity", "1").put("unitPrice", "100.00").put("lineSum", "100.00")
                .put("productKey", "same-product").put("provenance", "ocr")
                .put("confidence", 0.9).put("categoryCode", "еда")
                .put("verdict", "harmful").put("advice", "Первый совет")
                .put("reviewReason", "Первое основание").put("reviewAction", "limit purchase")
                .put("verdictSource", "rule").put("version", 3))
            .put(org.json.JSONObject()
                .put("id", secondId).put("name", "Одинаковый товар")
                .put("quantity", "1").put("unitPrice", "100.00").put("lineSum", "100.00")
                .put("productKey", "same-product").put("provenance", "ocr")
                .put("confidence", 0.9).put("categoryCode", "еда")
                .put("verdict", "useful").put("advice", "Второй совет")
                .put("reviewReason", "Второе основание").put("reviewAction", "keep")
                .put("verdictSource", "unknown").put("version", 4))
        response.put("items", items).put("itemCount", 2)
        server.enqueue(MockResponse().setBody(response.toString()))
        val api = api()
        api.saveTokens("receipt-writer-token", "refresh-token", System.currentTimeMillis() + 60_000)

        val reviewed = api.reviewReceiptBasket("tenant-17", "receipt-42", 1)

        assertEquals(2, reviewed.items.size)
        assertEquals("Одинаковый товар", reviewed.items[0].name)
        assertEquals("Одинаковый товар", reviewed.items[1].name)
        assertEquals(firstId, reviewed.items[0].id)
        assertEquals(secondId, reviewed.items[1].id)
        assertEquals("harmful", reviewed.items[0].verdict)
        assertEquals("Первый совет", reviewed.items[0].advice)
        assertEquals("Первое основание", reviewed.items[0].reviewReason)
        assertEquals("rule", reviewed.items[0].verdictSource)
        assertEquals("useful", reviewed.items[1].verdict)
        assertEquals("Второй совет", reviewed.items[1].advice)
        assertEquals("Второе основание", reviewed.items[1].reviewReason)
        assertEquals("unknown", reviewed.items[1].verdictSource)
        assertFalse("Legacy unknown source must not be relabeled as rule", reviewed.items[1].verdictSource == "rule")
    }

    @Test fun reviewReceiptBasketPreservesConflictAndStaleVersionFailures() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"receipt_not_editable"}"""))
        server.enqueue(MockResponse().setResponseCode(412).setBody("""{"detail":"stale_version"}"""))
        val api = api()
        api.saveTokens("receipt-writer-token", "refresh-token", System.currentTimeMillis() + 60_000)

        val conflict = runCatching { api.reviewReceiptBasket("tenant-17", "receipt-42", 4) }.exceptionOrNull()
        val stale = runCatching { api.reviewReceiptBasket("tenant-17", "receipt-42", 4) }.exceptionOrNull()

        assertTrue("Basket-review 409 must remain an API failure", conflict is ApiFailure)
        assertEquals(409, (conflict as ApiFailure).status)
        assertTrue("Basket-review 412 must remain an API failure", stale is ApiFailure)
        assertEquals(412, (stale as ApiFailure).status)
        repeat(2) {
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/api/v1/tenants/tenant-17/receipts/receipt-42/basket-review", request.path)
            assertEquals("Bearer receipt-writer-token", request.getHeader("Authorization"))
            assertEquals("\"4\"", request.getHeader("If-Match"))
            assertEquals("", request.body.readUtf8())
        }
    }

    @Test fun reviewReceiptBasketPreservesGatewayAndServiceUnavailableFailures() {
        listOf(502, 503).forEach { status ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"detail":"temporary_upstream_failure"}"""))
        }
        val api = api()
        api.saveTokens("receipt-writer-token", "refresh-token", System.currentTimeMillis() + 60_000)

        listOf(502, 503).forEach { status ->
            val failure = runCatching { api.reviewReceiptBasket("tenant-17", "receipt-42", 4) }.exceptionOrNull()
            assertTrue("Basket-review HTTP $status must remain an API failure", failure is ApiFailure)
            assertEquals(status, (failure as ApiFailure).status)
            assertTrue(failure.message.orEmpty().contains("temporary_upstream_failure"))

            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/api/v1/tenants/tenant-17/receipts/receipt-42/basket-review", request.path)
            assertEquals("Bearer receipt-writer-token", request.getHeader("Authorization"))
            assertEquals("\"4\"", request.getHeader("If-Match"))
            assertEquals("", request.body.readUtf8())
        }
    }

    @Test fun reviewReceiptBasketRejectsNonpositiveExpectedVersionBeforeRequest() {
        val api = api()

        val zeroVersion = runCatching { api.reviewReceiptBasket("tenant-17", "receipt-42", 0) }.exceptionOrNull()
        val negativeVersion = runCatching { api.reviewReceiptBasket("tenant-17", "receipt-42", -1) }.exceptionOrNull()

        assertTrue("A zero basket-review version must be rejected locally", zeroVersion is IllegalArgumentException)
        assertTrue("A negative basket-review version must be rejected locally", negativeVersion is IllegalArgumentException)
        assertNull("Invalid versions must not send a request", server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun receiptItemsFetchesOrderedPagesOfEightWithExactValuesAndOwnerAuthorization() {
        val pageOneItems = (1..8).map(::receiptItemJson)
        val pageTwoItems = listOf(receiptItemJson(9))
        server.enqueue(MockResponse().setBody(receiptItemPageJson(pageOneItems, page = 1, totalItems = 9, hasMore = true)))
        server.enqueue(MockResponse().setBody(receiptItemPageJson(pageTwoItems, page = 2, totalItems = 9, hasMore = false)))

        val api = api()
        api.saveTokens("receipt-owner-token", "refresh-token", System.currentTimeMillis() + 60_000)

        val firstPage = api.receiptItems("tenant-17", "receipt-42", page = 1)
        val secondPage = api.receiptItems("tenant-17", "receipt-42", page = 2)

        assertEquals(1, firstPage.page)
        assertEquals(9, firstPage.totalItems)
        assertTrue(firstPage.hasMore)
        assertEquals(8, firstPage.items.size)
        assertEquals((1..8).map(::receiptItemId), firstPage.items.map { it.id })
        assertEquals("Synthetic item 1", firstPage.items.first().name)
        assertEquals("1.000", firstPage.items.first().quantity)
        assertEquals("0.10", firstPage.items.first().unitPrice)
        assertEquals("0.10", firstPage.items.first().lineSum)
        assertEquals(1L, firstPage.items.first().version)
        assertEquals(null, firstPage.items.first().productKey)
        assertEquals(null, firstPage.items.first().categoryCode)
        assertEquals(null, firstPage.items.first().verdict)
        assertEquals(null, firstPage.items.first().confidence)

        assertEquals(2, secondPage.page)
        assertEquals(9, secondPage.totalItems)
        assertFalse(secondPage.hasMore)
        assertEquals(listOf(receiptItemId(9)), secondPage.items.map { it.id })
        assertEquals("0.90", secondPage.items.single().lineSum)
        assertEquals(9L, secondPage.items.single().version)
        assertTrue((firstPage.items + secondPage.items).map { it.id }.distinct().size == 9)

        val firstRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val secondRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", firstRequest.method)
        assertEquals("/api/v1/tenants/tenant-17/receipts/receipt-42/items?page=1", firstRequest.path)
        assertEquals("Bearer receipt-owner-token", firstRequest.getHeader("Authorization"))
        assertEquals("GET", secondRequest.method)
        assertEquals("/api/v1/tenants/tenant-17/receipts/receipt-42/items?page=2", secondRequest.path)
        assertEquals("Bearer receipt-owner-token", secondRequest.getHeader("Authorization"))
        assertEquals(2, server.requestCount)
    }

    @Test fun foreignReceiptItemPage404IsPropagatedWithoutInventingRows() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"not found"}"""))
        val api = api()
        api.saveTokens("receipt-owner-token", "refresh-token", System.currentTimeMillis() + 60_000)

        val failure = runCatching { api.receiptItems("tenant-17", "foreign-receipt", page = 1) }.exceptionOrNull()

        assertTrue(failure is ApiFailure)
        assertEquals(404, (failure as ApiFailure).status)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/api/v1/tenants/tenant-17/receipts/foreign-receipt/items?page=1", request.path)
        assertEquals("Bearer receipt-owner-token", request.getHeader("Authorization"))
        assertEquals(1, server.requestCount)
    }

    @Test fun updateReceiptItemPatchesExactValuesWithReceiptVersionAndKeepsCashTotal() {
        val responseJson = org.json.JSONObject(receiptJson()).apply {
            put("version", 5)
            put("itemsTotal", "246.80")
            getJSONArray("items").getJSONObject(0).apply {
                put("name", "Ржаной хлеб")
                put("quantity", "2.000")
                put("unitPrice", "123.40")
                put("lineSum", "246.80")
                put("version", 2)
            }
        }
        server.enqueue(MockResponse().setBody(responseJson.toString()))
        val api = api()
        api.saveTokens("receipt-owner-token", "refresh-token", System.currentTimeMillis() + 60_000)

        val updated = api.updateReceiptItem("tenant-17", "receipt-42", "item-3", 4,
            "Ржаной хлеб", "2.000", "123.40", "246.80")

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("PATCH", request.method)
        assertEquals("/api/v1/tenants/tenant-17/receipts/receipt-42/items/item-3", request.path)
        assertEquals("Bearer receipt-owner-token", request.getHeader("Authorization"))
        assertEquals("\"4\"", request.getHeader("If-Match"))
        val body = org.json.JSONObject(request.body.readUtf8())
        assertEquals(setOf("name", "quantity", "unitPrice", "lineSum"), body.keys().asSequence().toSet())
        assertEquals("Ржаной хлеб", body.getString("name"))
        assertEquals("2.000", body.getString("quantity"))
        assertEquals("123.40", body.getString("unitPrice"))
        assertEquals("246.80", body.getString("lineSum"))
        assertEquals("245.70", updated.cashTotal)
        assertEquals("246.80", updated.itemsTotal)
        assertEquals(5L, updated.version)
        val item = updated.items.single()
        assertEquals("Ржаной хлеб", item.name)
        assertEquals("2.000", item.quantity)
        assertEquals("123.40", item.unitPrice)
        assertEquals("246.80", item.lineSum)
        assertEquals(2L, item.version)
    }

    @Test fun updateReceiptItemSurfacesPreconditionFailedVersion() {
        server.enqueue(MockResponse().setResponseCode(412).setBody("""{"detail":"stale_version"}"""))
        val api = api()
        api.saveTokens("receipt-owner-token", "refresh-token", System.currentTimeMillis() + 60_000)

        val failure = runCatching {
            api.updateReceiptItem("tenant-17", "receipt-42", "item-3", 4,
                "Ржаной хлеб", "2.000", "123.40", "246.80")
        }.exceptionOrNull()

        assertTrue("412 must be returned as an API failure", failure is ApiFailure)
        assertEquals(412, (failure as ApiFailure).status)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("PATCH", request.method)
        assertEquals("\"4\"", request.getHeader("If-Match"))
    }

    @Test fun syncReceiptTotalPostsWithCurrentVersionAndParsesExactReturnedTotals() {
        val responseJson = org.json.JSONObject(receiptJson()).apply {
            put("version", 6)
            put("cashTotal", "246.80")
            put("itemsTotal", "246.80")
        }
        server.enqueue(MockResponse().setBody(responseJson.toString()))
        val api = api()
        api.saveTokens("receipt-owner-token", "refresh-token", System.currentTimeMillis() + 60_000)

        val updated = api.syncReceiptTotal("tenant-17", "receipt-42", 5)

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/v1/tenants/tenant-17/receipts/receipt-42/sync-total", request.path)
        assertEquals("Bearer receipt-owner-token", request.getHeader("Authorization"))
        assertEquals("\"5\"", request.getHeader("If-Match"))
        assertEquals("{}", request.body.readUtf8())
        assertEquals("246.80", updated.cashTotal)
        assertEquals("246.80", updated.itemsTotal)
        assertEquals(6L, updated.version)
        assertEquals(null, updated.transactionId)
    }

    @Test fun syncReceiptTotalPreservesConflictAndStaleVersionFailures() {
        listOf(409, 412).forEach { status ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"detail":"sync_failed"}"""))
        }
        val api = api()
        api.saveTokens("receipt-owner-token", "refresh-token", System.currentTimeMillis() + 60_000)

        listOf(409, 412).forEach { status ->
            val failure = runCatching { api.syncReceiptTotal("tenant-17", "receipt-42", 5) }.exceptionOrNull()
            assertTrue("HTTP $status must remain an API failure", failure is ApiFailure)
            assertEquals(status, (failure as ApiFailure).status)
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/api/v1/tenants/tenant-17/receipts/receipt-42/sync-total", request.path)
            assertEquals("Bearer receipt-owner-token", request.getHeader("Authorization"))
            assertEquals("\"5\"", request.getHeader("If-Match"))
            assertEquals("{}", request.body.readUtf8())
        }
    }

    @Test fun addReceiptItemPostsExactDecimalStringsWithReceiptVersionAndKeepsCashTotal() {
        val responseJson = org.json.JSONObject(receiptJson()).apply {
            put("version", 9)
            put("itemsTotal", "276.30")
            put("itemCount", 2)
            getJSONArray("items").put(org.json.JSONObject(receiptItemJson(10)).apply {
                put("name", "Сыр")
                put("quantity", "2.500")
                put("unitPrice", "12.24")
                put("lineSum", "30.60")
                put("version", 1)
            })
        }
        server.enqueue(MockResponse().setResponseCode(201).setBody(responseJson.toString()))
        val api = api()
        api.saveTokens("receipt-owner-token", "refresh-token", System.currentTimeMillis() + 60_000)

        val updated = api.addReceiptItem("tenant-17", "receipt-42", 8,
            name = "Сыр", quantity = "2.500", unitPrice = "12.24", lineSum = "30.60")

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/v1/tenants/tenant-17/receipts/receipt-42/items", request.path)
        assertEquals("Bearer receipt-owner-token", request.getHeader("Authorization"))
        assertEquals("\"8\"", request.getHeader("If-Match"))
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        val body = org.json.JSONObject(request.body.readUtf8())
        assertEquals(setOf("name", "quantity", "unitPrice", "lineSum"), body.keys().asSequence().toSet())
        assertEquals("Сыр", body.getString("name"))
        assertEquals("2.500", body.getString("quantity"))
        assertEquals("12.24", body.getString("unitPrice"))
        assertEquals("30.60", body.getString("lineSum"))
        assertEquals("245.70", updated.cashTotal)
        assertEquals("276.30", updated.itemsTotal)
        assertEquals(9L, updated.version)
        assertEquals(2, updated.itemCount)
        assertNull(updated.transactionId)
        val added = updated.items.last()
        assertEquals("Сыр", added.name)
        assertEquals("2.500", added.quantity)
        assertEquals("12.24", added.unitPrice)
        assertEquals("30.60", added.lineSum)
    }

    @Test fun addReceiptItemDefaultsQuantityAndPreservesNullableAmounts() {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(201).setBody(receiptJson())) }
        val api = api()
        api.saveTokens("receipt-owner-token", "refresh-token", System.currentTimeMillis() + 60_000)

        api.addReceiptItem("tenant-17", "receipt-42", 8, name = "Свеча")

        val defaultRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val defaultBody = org.json.JSONObject(defaultRequest.body.readUtf8())
        assertEquals("Свеча", defaultBody.getString("name"))
        assertEquals("1", defaultBody.getString("quantity"))
        assertTrue(defaultBody.isNull("unitPrice"))
        assertTrue(defaultBody.isNull("lineSum"))

        api.addReceiptItem("tenant-17", "receipt-42", 9, name = "Свеча без количества", quantity = null)

        val nullableRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val nullableBody = org.json.JSONObject(nullableRequest.body.readUtf8())
        assertEquals("Свеча без количества", nullableBody.getString("name"))
        assertTrue(nullableBody.isNull("quantity"))
        assertTrue(nullableBody.isNull("unitPrice"))
        assertTrue(nullableBody.isNull("lineSum"))
    }

    @Test fun addReceiptItemPreservesValidationConflictAndStaleFailures() {
        listOf(400, 409, 412).forEach { status ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"detail":"item_add_failed"}"""))
        }
        val api = api()
        api.saveTokens("receipt-owner-token", "refresh-token", System.currentTimeMillis() + 60_000)

        listOf(400, 409, 412).forEach { status ->
            val failure = runCatching {
                api.addReceiptItem("tenant-17", "receipt-42", 8,
                    name = "Сыр", quantity = "2.500", unitPrice = "12.24", lineSum = "30.60")
            }.exceptionOrNull()
            assertTrue("HTTP $status must remain an API failure", failure is ApiFailure)
            assertEquals(status, (failure as ApiFailure).status)
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/api/v1/tenants/tenant-17/receipts/receipt-42/items", request.path)
            assertEquals("Bearer receipt-owner-token", request.getHeader("Authorization"))
            assertEquals("\"8\"", request.getHeader("If-Match"))
        }
    }

    @Test fun ambiguousAddFailureIsNotAutomaticallyRetried() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val api = api()
        api.saveTokens("receipt-owner-token", "refresh-token", System.currentTimeMillis() + 60_000)

        val failure = runCatching {
            api.addReceiptItem("tenant-17", "receipt-42", 8,
                name = "Сыр", quantity = "2.500", unitPrice = "12.24", lineSum = "30.60")
        }.exceptionOrNull()

        assertNotNull("A dropped response must remain ambiguous to the caller", failure)
        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/api/v1/tenants/tenant-17/receipts/receipt-42/items", request.path)
        assertEquals("Bearer receipt-owner-token", request.getHeader("Authorization"))
        assertEquals("\"8\"", request.getHeader("If-Match"))
        val body = org.json.JSONObject(request.body.readUtf8())
        assertEquals("Сыр", body.getString("name"))
        assertEquals("2.500", body.getString("quantity"))
        assertEquals(1, server.requestCount)
    }

    @Test fun budgetProposalAndApplyReuseCallerSuppliedIdempotencyKeys() {
        repeat(2) { server.enqueue(MockResponse().setBody(budgetProposalJson())) }
        repeat(2) { server.enqueue(MockResponse().setBody(budgetOverviewJson())) }

        val api = api()
        val proposalKey = "budget-proposal-stable-key-0001"
        val applyKey = "budget-apply-stable-key-0002"
        api.saveTokens("budget-test-token", "refresh-token",
            System.currentTimeMillis() + TimeUnit.HOURS.toMillis(12))
        repeat(2) { api.proposeBudget("tenant-17", "100000.00", idempotencyKey = proposalKey) }
        repeat(2) { api.applyBudgetProposal("tenant-17", "proposal-7", idempotencyKey = applyKey) }

        val proposalRequests = listOf(
            requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)),
            requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)),
        )
        assertTrue(proposalRequests.all { it.method == "POST" })
        assertTrue(proposalRequests.all { it.path == "/api/v1/tenants/tenant-17/budget-proposals" })
        assertEquals(listOf(proposalKey, proposalKey), proposalRequests.map { it.getHeader("Idempotency-Key") })
        assertTrue(proposalRequests.all { it.body.readUtf8() == "{\"monthlyIncome\":\"100000.00\"}" })

        val applyRequests = listOf(
            requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)),
            requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)),
        )
        assertTrue(applyRequests.all { it.method == "POST" })
        assertTrue(applyRequests.all {
            it.path == "/api/v1/tenants/tenant-17/budget-proposals/proposal-7/apply"
        })
        assertEquals(listOf(applyKey, applyKey), applyRequests.map { it.getHeader("Idempotency-Key") })
    }

    @Test fun laterTenantCreateRecoversCommittedTenantBeforePostingAgain() {
        val listRequests = AtomicInteger()
        val createRequests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/v1/me/tenants" -> {
                    if (listRequests.incrementAndGet() == 1) {
                        MockResponse().setBody("[]")
                    } else {
                        MockResponse().setBody(
                            """[{"tenantId":"tenant-17","displayName":"Synthetic Family","role":"owner","timezone":"Europe/Moscow"}]""",
                        )
                    }
                }
                "/api/v1/tenants" -> {
                    if (createRequests.incrementAndGet() == 1) {
                        // The test server records the create as committed, then loses the response.
                        MockResponse().setResponseCode(503).setBody("""{"detail":"temporary failure after commit"}""")
                    } else {
                        MockResponse().setResponseCode(201).setBody("{}")
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val api = api()
        val firstFailure = runCatching {
            api.createTenant("Synthetic Family", "Taylor Example", "100000.00")
        }.exceptionOrNull()
        assertNotNull("the first response may be ambiguous after the server committed", firstFailure)

        api.createTenant("Synthetic Family", "Taylor Example", "100000.00")

        val firstListRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val failedCreateRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val recoveryListRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", firstListRequest.method)
        assertEquals("/api/v1/me/tenants", firstListRequest.path)
        assertEquals("POST", failedCreateRequest.method)
        assertEquals("/api/v1/tenants", failedCreateRequest.path)
        assertEquals("GET", recoveryListRequest.method)
        assertEquals("/api/v1/me/tenants", recoveryListRequest.path)
        assertEquals(2, listRequests.get())
        assertEquals("only the ambiguous first create may be posted", 1, createRequests.get())
        assertEquals(null, server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun updateMemberProfilePatchesCoreProfileWithoutCreatingTenantOrMutatingTransactions() {
        server.enqueue(MockResponse().setBody(
            """{"displayName":"Taylor Example","plannedIncome":120000.50,"onboardingState":"complete","timezone":"Europe/Moscow","currency":"RUB"}""",
        ))
        val api = api()
        api.saveTokens("access-token", "refresh-token", System.currentTimeMillis() + TimeUnit.HOURS.toMillis(12))

        val profile = api.updateMemberProfile("tenant-17", "Taylor Example", "120000,5")

        assertEquals("Taylor Example", profile.displayName)
        assertEquals("120000.50", profile.plannedIncome)
        assertEquals("complete", profile.onboardingState)
        assertEquals("Europe/Moscow", profile.timezone)
        assertEquals("RUB", profile.currency)

        val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("PATCH", request.method)
        assertEquals("/api/v1/tenants/tenant-17/profile/me", request.path)
        val body = org.json.JSONObject(request.body.readUtf8())
        assertEquals(setOf("displayName", "plannedIncome", "onboardingState"),
            body.keys().asSequence().toSet())
        assertEquals("Taylor Example", body.getString("displayName"))
        assertEquals(0, java.math.BigDecimal("120000.50").compareTo(
            java.math.BigDecimal(body.get("plannedIncome").toString())))
        assertEquals("complete", body.getString("onboardingState"))

        // Saving a member profile must not create another tenant or mutate transaction history.
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun concurrentUnauthorizedRequestsRefreshRotatingTokenOnceAndKeepSession() {
        val initialRequests = CountDownLatch(2)
        val releaseUnauthorizedResponses = CountDownLatch(1)
        val secondRefreshObserved = CountDownLatch(1)
        val apiRequestCount = AtomicInteger()
        val refreshRequestCount = AtomicInteger()
        val authorizationHeaders = Collections.synchronizedList(mutableListOf<String?>())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/protocol/openid-connect/token" -> {
                    if (refreshRequestCount.incrementAndGet() == 1) {
                        secondRefreshObserved.await(300, TimeUnit.MILLISECONDS)
                        MockResponse().setBody("""{"access_token":"fresh-token","refresh_token":"rotated-token","expires_in":300}""")
                    } else {
                        secondRefreshObserved.countDown()
                        MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
                    }
                }
                request.path == "/api/v1/tenants/tenant-17/receipt-jobs/job-29" -> {
                    authorizationHeaders.add(request.getHeader("Authorization"))
                    if (apiRequestCount.incrementAndGet() <= 2) {
                        initialRequests.countDown()
                        check(initialRequests.await(5, TimeUnit.SECONDS)) { "both stale-token requests must arrive" }
                        check(releaseUnauthorizedResponses.await(5, TimeUnit.SECONDS)) { "test must release initial 401 responses" }
                        MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}""")
                    } else {
                        MockResponse().setBody(jobJson(state = "running", stage = "ocr", progress = 20))
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val api = api()
        api.saveTokens("stale-token", "refresh-token", System.currentTimeMillis() + 60_000)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                pool.submit<Boolean> {
                    runCatching { api.receiptJob("tenant-17", "job-29") }.isSuccess
                }
            }
            assertTrue("both API requests should reach the server", initialRequests.await(5, TimeUnit.SECONDS))
            releaseUnauthorizedResponses.countDown()
            val successfulCalls = results.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals("one refresh request must rotate the token for both callers", 1, refreshRequestCount.get())
            assertEquals(listOf(true, true), successfulCalls)
            assertEquals(4, apiRequestCount.get())
            assertEquals(2, authorizationHeaders.count { it == "Bearer stale-token" })
            assertEquals(2, authorizationHeaders.count { it == "Bearer fresh-token" })
            assertTrue("successful concurrent refresh must preserve the signed-in session", api.hasSession())
        } finally {
            secondRefreshObserved.countDown()
            releaseUnauthorizedResponses.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun blankRefreshAccessTokenFailsWithoutReplacingExistingSession() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/protocol/openid-connect/token" -> MockResponse().setBody(
                    """{"access_token":"","refresh_token":"rotated-token","expires_in":300}""",
                )
                "/api/v1/tenants/tenant-17/receipt-jobs/job-29" ->
                    MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val api = api()
        api.saveTokens("stale-token", "refresh-token", System.currentTimeMillis() + 60_000)

        val failure = runCatching { api.receiptJob("tenant-17", "job-29") }.exceptionOrNull()

        assertNotNull("a blank refreshed access token must fail the request", failure)
        assertTrue("invalid refresh response must not replace the existing session", api.hasSession())
        val firstRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        val refreshRequest = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("Bearer stale-token", firstRequest.getHeader("Authorization"))
        assertEquals("refresh-token", refreshRequest.body.readUtf8().substringAfter("refresh_token="))
    }

    @Test fun logoutDuringRefreshDoesNotRestoreSessionWhenRefreshCompletes() {
        val refreshStarted = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val logoutCompleted = CountDownLatch(1)
        val apiRequestCount = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/protocol/openid-connect/token" -> {
                    refreshStarted.countDown()
                    check(releaseRefresh.await(5, TimeUnit.SECONDS)) { "test must release pending refresh" }
                    MockResponse().setBody("""{"access_token":"fresh-token","refresh_token":"rotated-token","expires_in":300}""")
                }
                request.path == "/protocol/openid-connect/revoke" -> MockResponse()
                request.path == "/api/v1/tenants/tenant-17/receipt-jobs/job-29" -> {
                    if (apiRequestCount.incrementAndGet() == 1) {
                        MockResponse().setResponseCode(401).setBody("""{"detail":"expired"}""")
                    } else {
                        MockResponse().setBody(jobJson(state = "running", stage = "ocr", progress = 20))
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val api = api()
        api.saveTokens("stale-token", "refresh-token", System.currentTimeMillis() + 60_000)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val operation = pool.submit<Boolean> {
                runCatching { api.receiptJob("tenant-17", "job-29") }.isSuccess
            }
            assertTrue("request should begin token refresh", refreshStarted.await(5, TimeUnit.SECONDS))
            val logout = pool.submit<Boolean> {
                try {
                    api.logout()
                    true
                } finally {
                    logoutCompleted.countDown()
                }
            }
            // Give an implementation that does not serialize logout with refresh time to complete logout
            // before releasing the refresh response. A serialized or generation-guarded implementation
            // may correctly finish logout after the pending refresh is released.
            logoutCompleted.await(300, TimeUnit.MILLISECONDS)
            releaseRefresh.countDown()
            operation.get(10, TimeUnit.SECONDS)
            assertTrue("logout should return after the in-flight refresh is resolved", logout.get(10, TimeUnit.SECONDS))
            assertFalse("a refresh already in flight must not resurrect a logged-out session", api.hasSession())
        } finally {
            releaseRefresh.countDown()
            pool.shutdownNow()
        }
    }

    private fun api(): FinanceApi {
        val base = server.url("/").toString().removeSuffix("/")
        return FinanceApi(ApplicationProvider.getApplicationContext<Context>(),
            FinanceApiEndpoints(base, base), OkHttpClient())
    }

    private fun jobJson(state: String, stage: String, progress: Int) = """
        {"id":"job-29","tenantId":"tenant-17","documentId":"document-9","state":"$state",
         "stage":"$stage","progressPercent":$progress,"attemptCount":1,"retryable":false,"errorCode":null,
         "receiptId":"${if (state == "completed") "receipt-42" else ""}",
         "createdAt":"2026-10-08T09:00:00Z","updatedAt":"2026-10-08T09:01:00Z"}
    """.trimIndent()

    private fun receiptItemId(index: Int) = "00000000-0000-4000-8000-%012d".format(index)

    private fun receiptItemJson(index: Int) = org.json.JSONObject()
        .put("id", receiptItemId(index))
        .put("name", "Synthetic item $index")
        .put("quantity", "1.000")
        .put("unitPrice", if (index == 9) "0.90" else "0.10")
        .put("lineSum", if (index == 9) "0.90" else "0.10")
        .put("productKey", org.json.JSONObject.NULL)
        .put("provenance", "ocr")
        .put("confidence", org.json.JSONObject.NULL)
        .put("categoryCode", org.json.JSONObject.NULL)
        .put("verdict", org.json.JSONObject.NULL)
        .put("advice", org.json.JSONObject.NULL)
        .put("reviewReason", org.json.JSONObject.NULL)
        .put("reviewAction", org.json.JSONObject.NULL)
        .put("verdictSource", org.json.JSONObject.NULL)
        .put("reviewProvider", org.json.JSONObject.NULL)
        .put("reviewModelVersion", org.json.JSONObject.NULL)
        .put("reviewPromptVersion", org.json.JSONObject.NULL)
        .put("reviewAlgorithmVersion", org.json.JSONObject.NULL)
        .put("version", index)
        .toString()

    private fun receiptItemPageJson(items: List<String>, page: Int, totalItems: Int, hasMore: Boolean): String {
        val jsonItems = org.json.JSONArray()
        items.forEach { jsonItems.put(org.json.JSONObject(it)) }
        return org.json.JSONObject()
            .put("items", jsonItems)
            .put("page", page)
            .put("totalItems", totalItems)
            .put("hasMore", hasMore)
            .toString()
    }

    private fun receiptJson() = """
        {"id":"receipt-42","tenantId":"tenant-17","documentId":"document-9","state":"review_required",
         "version":1,"transactionId":null,"currency":"RUB","cashTotal":"245.70","itemsTotal":"245.70",
         "merchant":"Магазин Тест","receiptDate":"2026-10-08","selectedReader":"ocr","categoryCode":null,
         "categorySource":"unknown","categoryAlgorithmVersion":"receipt-category-v1","alcoholShare":null,
         "leisureShare":null,"leisure":false,"duplicateDecision":"unknown","duplicateOfReceiptId":null,
         "items":[{"id":"item-3","name":"Хлеб","quantity":"1","unitPrice":"245.70","lineSum":"245.70",
           "productKey":null,"provenance":"ocr","confidence":0.94,"categoryCode":null,"verdict":null,
           "advice":null,"reviewReason":null,"reviewAction":null,"verdictSource":null,"reviewProvider":null,
           "reviewModelVersion":null,"reviewPromptVersion":null,"reviewAlgorithmVersion":null,"version":1}],
         "itemCount":1,"createdAt":"2026-10-08T09:01:00Z"}
    """.trimIndent()

    private fun receiptDuplicateCandidatesJson() = """
        {"receiptId":"receipt-42","decision":"unknown","candidates":[
          {"id":"00000000-0000-4000-8000-000000000091","cashTotal":"245.70",
           "merchant":"Synthetic Market","createdAt":"2026-10-08T08:59:00Z"}]}
    """.trimIndent()

    private fun receiptReadingJson(
        text: String = "TEST MARKET TOTAL 120.00",
        words: String = """[{"text":"TOTAL","confidence":96.0,"box":{"x":31,"y":17,"width":52,"height":10}}]""",
        ocrTotal: String = "\"120.00\"",
        ocrItems: String = """[{"name":"Bread","quantity":"1","unitPrice":"120.00","lineSum":"120.00"}]""",
        vision: String = """{"store":"Synthetic Vision Mart","date":"2026-10-04","total":"900.00",
            "items":[{"name":"Model Bread","quantity":"1","unitPrice":"900.00","lineSum":"900.00"}],
            "provider":"synthetic-vision","modelVersion":"synthetic-vision-v1",
            "promptVersion":"vision-prompt-v1","fallbackReason":null}""",
        reconciliation: String = """{"algorithmVersion":"receipt-reconciliation.v1","decision":"review_required",
            "selectedReader":"ocr","mismatchFields":["total"],"ocrItemsTotal":"120.00",
            "visionItemsTotal":"900.00","allowedDifference":"0.02","ocrItemsReconciled":true,
            "visionItemsReconciled":false,"itemEvidence":[{"visionOrdinal":1,"ocrOrdinal":1,
            "status":"corroborated"}],"suggestedTopUps":[]}""",
    ) = """
        {"text":${org.json.JSONObject.quote(text)},"words":$words,"provider":"tesseract",
         "modelVersion":"tesseract-5.3.0","promptVersion":"tesseract-ocr.v2","confidence":0.9600,
         "ocrTotal":$ocrTotal,"ocrItems":$ocrItems,"reconciliation":$reconciliation,
         "visionFallbackReason":null,"ocrFallbackReason":null,"vision":$vision}
    """.trimIndent()

    private fun budgetProposalJson() = """
        {"id":"proposal-7","monthlyIncome":"100000.00","totalLimit":"70000.00",
         "limits":{"food":"20000.00"},"status":"pending","proposalSource":"history",
         "historyDays":90,"modelVersion":null}
    """.trimIndent()

    private fun budgetOverviewJson() = """
        {"currency":"RUB","month":"2026-10","familyLimits":{"food":"20000.00"},
         "personalOverrides":{},"effectiveLimits":{"food":"20000.00"},"monthlySpent":{"food":"0.00"},
         "limitStatus":{"food":"normal"},"familyVersions":{"food":1},"personalVersions":{},
         "familyTotalLimit":"50000.00","personalTotalOverride":null,"effectiveTotalLimit":"50000.00",
         "totalMonthlySpent":"0.00","totalLimitStatus":"normal","familyTotalVersion":1,
         "personalTotalVersion":0,"rolling7FoodLimit":"1000.00","personalRolling7FoodOverride":null,
         "effectiveRolling7FoodLimit":"1000.00","rolling7FoodSpent":"0.00",
         "rolling7FoodLimitStatus":"normal","familyRolling7FoodVersion":1,"personalRolling7FoodVersion":0,
         "rolling7FoodStatus":{"fromDate":"2026-09-25","toDate":"2026-10-01","limit":"1000.00",
           "spent":"0.00","remaining":"1000.00","limitStatus":"normal","usualWeeklySpend":null,
           "historyWeeks":0,"paceStatus":"insufficient_history","paceShare":null}}
    """.trimIndent()
}
