package com.decorix.finance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FinanceTransactionsPaginationApiTest {
    private val server = MockWebServer()
    private val tokenVault by lazy {
        TokenVault.isolatedForTesting(
            ApplicationProvider.getApplicationContext(),
            "finance_transactions_page_api_test_session",
            "finance-transactions-page-api-test-v1",
        )
    }

    @get:Rule val serverRule = object : ExternalResource() {
        override fun before() {
            tokenVault.clear()
            server.start()
        }
        override fun after() {
            server.shutdown()
            tokenVault.clear()
        }
    }

    @Test fun filteredPagesKeepAuthenticationTransactionFieldsAndOpaqueCursor() {
        server.enqueue(MockResponse().setBody(pageJson(FIRST_TRANSACTION, CURSOR)))
        server.enqueue(MockResponse().setBody(pageJson(SECOND_TRANSACTION, null)))
        val api = api()

        val first = api.transactions(
            tenantId = TENANT_ID,
            search = SEARCH,
            type = "expense",
            from = FROM,
            to = TO,
            memberId = MEMBER_ID,
        )

        assertEquals(listOf(firstTransaction()), first.items)
        assertEquals(CURSOR, first.nextCursor)
        assertFilteredRequest(requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)), cursor = null)

        val second = api.transactions(
            tenantId = TENANT_ID,
            search = SEARCH,
            type = "expense",
            cursor = first.nextCursor,
            from = FROM,
            to = TO,
            memberId = MEMBER_ID,
        )

        assertEquals(listOf(secondTransaction()), second.items)
        assertEquals(null, second.nextCursor)
        assertFilteredRequest(requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)), cursor = CURSOR)
    }

    private fun assertFilteredRequest(request: okhttp3.mockwebserver.RecordedRequest, cursor: String?) {
        assertEquals("GET", request.method)
        assertEquals("/api/v1/tenants/$TENANT_ID/transactions", request.requestUrl?.encodedPath)
        assertEquals("Bearer synthetic-access-token", request.getHeader("Authorization"))
        val url = requireNotNull(request.requestUrl)
        assertEquals("50", url.queryParameter("pageSize"))
        assertEquals(SEARCH, url.queryParameter("search"))
        assertEquals("expense", url.queryParameter("type"))
        assertEquals(FROM, url.queryParameter("from"))
        assertEquals(TO, url.queryParameter("to"))
        assertEquals(MEMBER_ID, url.queryParameter("memberId"))
        assertEquals(cursor, url.queryParameter("cursor"))
    }

    private fun api(): FinanceApi {
        val base = server.url("/").toString().removeSuffix("/")
        return FinanceApi(ApplicationProvider.getApplicationContext<Context>(),
            FinanceApiEndpoints(base, base), OkHttpClient(), tokenVault).also {
            it.saveTokens("synthetic-access-token", "synthetic-refresh-token", System.currentTimeMillis() + 60_000)
        }
    }

    private fun pageJson(transaction: String, nextCursor: String?) =
        """{"items":[$transaction],"nextCursor":${nextCursor?.let { "\"$it\"" } ?: "null"}}"""

    private fun firstTransaction() = FinanceTransaction(
        id = "00000000-0000-4000-8000-000000000091",
        tenantId = TENANT_ID,
        type = "expense",
        amount = "1234.56",
        currency = "RUB",
        categoryCode = "food",
        subcategoryCode = "coffee",
        description = "Synthetic coffee & tea",
        source = "manual",
        occurredAt = "2026-10-02T08:15:00Z",
        accountId = "00000000-0000-4000-8000-000000000092",
        status = "posted",
        version = 7,
        createdAt = "2026-10-02T08:16:00Z",
        memberName = "Test member",
        debtId = null,
        ownerUserId = MEMBER_ID,
    )

    private fun secondTransaction() = FinanceTransaction(
        id = "00000000-0000-4000-8000-000000000093",
        tenantId = TENANT_ID,
        type = "expense",
        amount = "78.90",
        currency = "RUB",
        categoryCode = "transport",
        subcategoryCode = null,
        description = "Synthetic next page",
        source = "bank_import",
        occurredAt = "2026-10-03T09:20:00Z",
        accountId = null,
        status = "posted",
        version = 2,
        createdAt = "2026-10-03T09:21:00Z",
        memberName = null,
        debtId = "00000000-0000-4000-8000-000000000094",
        ownerUserId = null,
    )

    private companion object {
        const val TENANT_ID = "00000000-0000-4000-8000-000000000090"
        const val MEMBER_ID = "00000000-0000-4000-8000-000000000095"
        const val SEARCH = "coffee & tea / 2"
        const val FROM = "2026-10-01"
        const val TO = "2026-10-31"
        const val CURSOR = "v1:next/eyJpZCI6IjEyMyJ9+==&opaque"
        const val FIRST_TRANSACTION = """
            {"id":"00000000-0000-4000-8000-000000000091","tenantId":"00000000-0000-4000-8000-000000000090",
             "type":"expense","amount":"1234.56","currency":"RUB","categoryCode":"food","subcategoryCode":"coffee",
             "description":"Synthetic coffee & tea","source":"manual","occurredAt":"2026-10-02T08:15:00Z",
             "accountId":"00000000-0000-4000-8000-000000000092","status":"posted","version":7,
             "createdAt":"2026-10-02T08:16:00Z","memberName":"Test member","debtId":null,
             "ownerUserId":"00000000-0000-4000-8000-000000000095"}
        """
        const val SECOND_TRANSACTION = """
            {"id":"00000000-0000-4000-8000-000000000093","tenantId":"00000000-0000-4000-8000-000000000090",
             "type":"expense","amount":"78.90","currency":"RUB","categoryCode":"transport","subcategoryCode":null,
             "description":"Synthetic next page","source":"bank_import","occurredAt":"2026-10-03T09:20:00Z",
             "accountId":null,"status":"posted","version":2,"createdAt":"2026-10-03T09:21:00Z",
             "memberName":null,"debtId":"00000000-0000-4000-8000-000000000094","ownerUserId":null}
        """
    }
}
