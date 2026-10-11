package com.decorix.finance

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinanceProductCatalogScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun blankCatalogShowsCoreFilteredThreePurchaseCatalogWithoutLocalThresholdGuessing() {
        val loadedQueries = mutableListOf<String>()
        show(
            catalog = catalog(query = "", products = listOf(product("Tea Green 500g", purchaseCount = 3))),
            onSearch = loadedQueries::add,
        )

        compose.onNodeWithText("Товары").assertIsDisplayed()
        compose.onNodeWithText("Tea Green 500g").assertIsDisplayed()
        compose.onNodeWithText("3 покупки").assertIsDisplayed()
        compose.onNodeWithTag("product-metric-usual-Tea Green 500g").performScrollTo()
            .assertTextContains("110", substring = true)
        compose.onNodeWithTag("product-metric-last-Tea Green 500g")
            .assertTextContains("160", substring = true)
            .assertTextContains("20", substring = true).assertTextContains("2026", substring = true)
        compose.onNodeWithTag("product-metric-cheapest-Tea Green 500g")
            .assertTextContains("100", substring = true)
            .assertTextContains("Market A", substring = true)
        compose.onNodeWithTag("product-metric-spent-Tea Green 500g")
            .assertTextContains("380", substring = true)
        compose.onNodeWithText("2026", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("История цены: Tea Green 500g").assertIsDisplayed()
        assertEquals("Showing Core's catalog must not trigger another request", emptyList<String>(), loadedQueries)
    }

    @Test fun searchCanShowOnePurchaseProductWithoutInventedMedianBaselineOrChart() {
        val loadedQueries = mutableListOf<String>()
        show(
            catalog = catalog(query = "milk", mode = "search", products = listOf(product("Milk Domik 930ml", 1))),
            requestedQuery = "milk",
            onSearch = loadedQueries::add,
        )

        compose.onNodeWithText("Milk Domik 930ml").assertIsDisplayed()
        compose.onNodeWithText("1 покупка").assertIsDisplayed()
        compose.onNodeWithText("Недостаточно сопоставимых покупок.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("product-metric-usual-Milk Domik 930ml").performScrollTo()
            .assertTextContains("100", substring = true)
        compose.onNodeWithTag("product-metric-last-Milk Domik 930ml").assertTextContains("100", substring = true)
            .assertTextContains("2026", substring = true)
        compose.onNodeWithTag("product-metric-cheapest-Milk Domik 930ml")
            .assertTextContains("100", substring = true)
            .assertTextContains("Market A", substring = true)
        compose.onNodeWithTag("product-metric-spent-Milk Domik 930ml")
            .assertTextContains("100", substring = true)
        compose.onNodeWithText("2026", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("До последней покупки").assertDoesNotExist()
        compose.onNodeWithContentDescription("История цены: Milk Domik 930ml").assertDoesNotExist()
        assertEquals("Rendering a Core search response must not refetch", emptyList<String>(), loadedQueries)
    }

    @Test fun multiplePurchasesWithoutComparableBaselineShowInsufficientHistoryNotSinglePurchaseCopy() {
        val sameTimeObservations = product("Same-time rice", 2).copy(
            hasBaseline = false,
            baselineUnitPrice = null,
            change = null,
            relative = null,
            signal = false,
            direction = null,
            priorPurchases = 0,
            history = product("Same-time rice", 2).history.map {
                it.copy(purchasedAt = "2026-09-10T10:00:00Z")
            },
        )
        val language = mutableStateOf("ru")
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    TextButton(onClick = { language.value = if (language.value == "ru") "en" else "ru" }) {
                        Text(if (language.value == "ru") "EN" else "RU")
                    }
                    ProductCatalogScreen(
                        modifier = Modifier.fillMaxSize(), language = language.value, currency = "RUB",
                        catalog = catalog("rice", listOf(sameTimeObservations), mode = "search"),
                        requestedQuery = "rice", loading = false, error = null, onSearch = {}, onRetry = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Недостаточно сопоставимых покупок.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Пока одна покупка", substring = true).assertDoesNotExist()
        compose.onNodeWithText("До последней покупки").assertDoesNotExist()
        compose.onNodeWithContentDescription("История цены: Same-time rice").assertIsDisplayed()

        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText("Not enough comparable purchases.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("One purchase so far", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Before latest purchase").assertDoesNotExist()
        compose.onNodeWithContentDescription("Price history: Same-time rice").assertIsDisplayed()
    }

    @Test fun blankCatalogExplainsThreeConfirmedPurchaseThreshold() {
        show(catalog = catalog(query = "", products = emptyList()))

        compose.onNodeWithText("Каталог появится после трёх подтверждённых покупок товара.")
            .assertIsDisplayed()
    }

    @Test fun searchSubmitsTypedQueryExactlyOnce() {
        val loadedQueries = mutableListOf<String>()
        show(catalog = catalog(query = "", products = emptyList()), onSearch = loadedQueries::add)

        compose.onNodeWithText("Поиск товаров").performTextInput("milk")
        compose.onNodeWithText("Найти").performClick()

        assertEquals(listOf("milk"), loadedQueries)
    }

    @Test fun loadingStateIsVisibleAndLocalized() {
        show(loading = true)
        compose.onNodeWithText("Загрузка…").assertIsDisplayed()
    }

    @Test fun failureHidesMachineErrorAndRetryCallsCallback() {
        val retries = mutableListOf<Unit>()
        compose.setContent {
            MaterialTheme {
                ProductCatalogScreen(
                    modifier = Modifier.fillMaxSize(), language = "ru", currency = "RUB", catalog = null,
                    requestedQuery = "чай", loading = false, error = "service unavailable",
                    onSearch = {}, onRetry = { retries += Unit },
                )
            }
        }
        compose.onNodeWithText("История цен временно недоступна.").assertIsDisplayed()
        compose.onNodeWithText("service unavailable").assertDoesNotExist()
        compose.onNodeWithText("Повторить").performClick()
        assertEquals(1, retries.size)
    }

    @Test fun searchWithNoMatchesHasLocalizedEmptyState() {
        compose.setContent {
            MaterialTheme {
                ProductCatalogScreen(
                    modifier = Modifier.fillMaxSize(), language = "ru", currency = "RUB",
                    catalog = catalog(query = "чай", mode = "search", products = emptyList()),
                    requestedQuery = "чай", loading = false, error = null, onSearch = {}, onRetry = {},
                )
            }
        }
        compose.onNodeWithText("Совпадений нет.").assertIsDisplayed()
    }

    @Test fun catalogCardLocalizesCoreMetricsAndActualHistoryInRussianAndEnglish() {
        val item = product("Tea Green 500g", 3)
        val language = mutableStateOf("ru")
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    TextButton(onClick = { language.value = if (language.value == "ru") "en" else "ru" }) {
                        Text(if (language.value == "ru") "EN" else "RU")
                    }
                    ProductCatalogScreen(
                        modifier = Modifier.fillMaxSize(), language = language.value, currency = "RUB",
                        catalog = catalog(query = "", products = listOf(item)), requestedQuery = "",
                        loading = false, error = null, onSearch = {}, onRetry = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Обычная цена").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Последняя покупка").assertIsDisplayed()
        compose.onNodeWithText("Самая низкая цена").assertIsDisplayed()
        compose.onNodeWithText("Потрачено").assertIsDisplayed()
        compose.onNodeWithText("До последней покупки").assertIsDisplayed()
        compose.onNodeWithText("Market C", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("История цены: Tea Green 500g").assertIsDisplayed()

        compose.onNodeWithText("EN").performClick()
        compose.onNodeWithText("Products").assertIsDisplayed()
        compose.onNodeWithText("Usual price").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Last purchase").assertIsDisplayed()
        compose.onNodeWithText("Lowest price").assertIsDisplayed()
        compose.onNodeWithText("Spent").assertIsDisplayed()
        compose.onNodeWithText("Before latest purchase").assertIsDisplayed()
        compose.onNodeWithText("Market C", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Price history: Tea Green 500g").assertIsDisplayed()
    }

    @Test fun coreSinglePointHistoryNeverDrawsChartEvenIfResponseFlagIsIncorrect() {
        val onePointButFlagged = product("Rice 1kg", 1, chartAvailable = true)
        show(catalog = catalog(query = "rice", mode = "search", products = listOf(onePointButFlagged)),
            requestedQuery = "rice")

        compose.onNodeWithText("Rice 1kg").assertIsDisplayed()
        compose.onNodeWithContentDescription("История цены: Rice 1kg").assertDoesNotExist()
        compose.onNodeWithText("До последней покупки").assertDoesNotExist()
    }

    private fun show(
        catalog: FinanceProductCatalog? = null,
        requestedQuery: String = "",
        loading: Boolean = false,
        error: String? = null,
        onSearch: (String) -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                ProductCatalogScreen(
                    modifier = Modifier.fillMaxSize(), language = "ru", currency = "RUB", catalog = catalog,
                    requestedQuery = requestedQuery, loading = loading, error = error,
                    onSearch = onSearch, onRetry = onRetry,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun catalog(query: String, products: List<FinanceProductCard>, mode: String = "catalog") =
        FinanceProductCatalog(mode = mode, query = query, products = products)

    private fun product(
        name: String,
        purchaseCount: Int,
        chartAvailable: Boolean = purchaseCount >= 2,
    ) = FinanceProductCard(
        productName = name,
        purchaseCount = purchaseCount,
        usualUnitPrice = if (purchaseCount >= 2) "110.000000" else "100.000000",
        hasBaseline = purchaseCount >= 2,
        baselineUnitPrice = if (purchaseCount >= 2) "100.000000" else null,
        lastUnitPrice = when { purchaseCount >= 3 -> "160.000000"; purchaseCount == 2 -> "120.000000"; else -> "100.000000" },
        lastPurchasedAt = when { purchaseCount >= 3 -> "2026-09-20T10:00:00Z"; purchaseCount == 2 -> "2026-09-10T10:00:00Z"; else -> "2026-09-01T10:00:00Z" },
        lastMerchant = when { purchaseCount >= 3 -> "Market C"; purchaseCount == 2 -> "Market B"; else -> "Market A" },
        cheapestUnitPrice = "100.000000",
        cheapestMerchant = "Market A",
        totalSpent = when { purchaseCount >= 3 -> "380.00"; purchaseCount == 2 -> "220.00"; else -> "100.00" },
        change = if (purchaseCount >= 2) "60.000000" else null,
        relative = if (purchaseCount >= 2) "0.600000" else null,
        signal = false,
        direction = if (purchaseCount >= 2) "up" else null,
        priorPurchases = if (purchaseCount >= 2) purchaseCount - 1 else 0,
        chartAvailable = chartAvailable,
        history = if (purchaseCount >= 2) listOf(
            FinanceProductCatalogPoint("00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000011", "2026-09-01T10:00:00Z", "Market A", name,
                "100.000000", false),
            FinanceProductCatalogPoint("00000000-0000-4000-8000-000000000002", "00000000-0000-4000-8000-000000000012", "2026-09-10T10:00:00Z", "Market B", name,
                "120.000000", false),
            FinanceProductCatalogPoint("00000000-0000-4000-8000-000000000003", "00000000-0000-4000-8000-000000000013", "2026-09-20T10:00:00Z", "Market C", name,
                "160.000000", false),
        ).take(purchaseCount) else listOf(
            FinanceProductCatalogPoint("00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000011", "2026-09-01T10:00:00Z", "Market A", name,
                "100.000000", true),
        ),
    )
}
