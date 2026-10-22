package io.ledgerlens.app

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.ledgerlens.app.model.*
import io.ledgerlens.app.ui.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.math.BigDecimal

class RecoveryUiTest {
    @get:Rule val compose = createComposeRule()
    private fun scrollToText(text: String, substring: Boolean = false): SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = substring))
        return compose.onAllNodesWithText(text, substring = substring).onFirst()
    }
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, name).outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun pricesRemainVisibleWhenTronBalanceIsUnavailableAndCardanoHasNoDailyLock() {
        val now = System.currentTimeMillis()
        val tron = Account("tron", "DEMO TRON", Chain.TRON, Chain.TRON.path(0), "DEMO", "DEMO ADDRESS", now)
        val ada = Account("ada", "DEMO Cardano", Chain.ADA, Chain.ADA.path(0), "DEMO", "DEMO ADDRESS", now)
        val unknown = nativePlaceholder(tron).copy(quote = Quote(BigDecimal("0.15"), QuoteState.LIVE, now))
        val known = nativePlaceholder(ada).copy(quantity = BigDecimal("10"), balanceKnown = true, quote = Quote(BigDecimal("0.36"), QuoteState.LIVE, now))
        val state = PortfolioState(listOf(tron, ada), mapOf(
            tron.id to AccountSnapshot(listOf(unknown), error = "api.trongrid.io: HTTP 429", at = 0, complete = false),
            ada.id to AccountSnapshot(listOf(known), at = now, lastAttemptAt = now)
        ), Preferences(theme = "dark", hideZeroBalance = true, hideZeroValue = true), updatedAt = now)
        compose.setContent { LensTheme("dark") { LensApp(state, emptyList(), null, {}, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, {}) } }
        scrollToText("單價（USDT） · 0.15").assertIsDisplayed()
        compose.onNodeWithText("餘額尚未取得").assertIsDisplayed()
        capture("recovery-prices.png")
        compose.onNodeWithText("帳戶").performClick()
        scrollToText("TronGrid 拒絕或限制查詢。", substring = true).assertIsDisplayed()
        capture("recovery-tron.png")
        scrollToText("Cardano 已取消每日同步限制", substring = true).assertIsDisplayed()
        compose.onNodeWithText("下次可同步持倉", substring = true).assertDoesNotExist()
        capture("recovery-cardano.png")
    }
    @Test fun settingsSaveTronGridKeyAndExplainCardanoRefresh() {
        var saved = Preferences()
        compose.setContent {
            var state by remember { mutableStateOf(PortfolioState()) }
            LensTheme("system") { LensApp(state, emptyList(), null, {}, {}, {}, {}, {}, { _, _, _ -> }, { saved = it; state = state.copy(preferences = it) }, {}, {}, {}) }
        }
        compose.onNodeWithText("設定").performClick()
        scrollToText("TronGrid API key").performTextInput("test-data-service-key")
        scrollToText("前往 TronGrid 建立 API key").assertIsDisplayed()
        scrollToText("儲存設定").performClick()
        compose.runOnIdle { assertEquals("test-data-service-key", saved.tronGridKey) }
        scrollToText("Cardano 已取消每日同步限制", substring = true).assertIsDisplayed()
        capture("recovery-settings.png")
    }
    @Test fun knownBalanceWithoutQuotesDoesNotShowZeroTotal() {
        val account = Account("eth", "DEMO ETH", Chain.ETH, Chain.ETH.path(0), "DEMO", "DEMO ADDRESS")
        val asset = nativePlaceholder(account).copy(quantity = BigDecimal.ONE, balanceKnown = true)
        val state = PortfolioState(listOf(account), mapOf(account.id to AccountSnapshot(listOf(asset))))
        compose.setContent { LensTheme("system") { LensApp(state, emptyList(), null, {}, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, {}) } }
        compose.onNodeWithText("—").assertIsDisplayed()
        compose.onNodeWithText("0.00").assertDoesNotExist()
        scrollToText("單價（USDT） · —").assertIsDisplayed()
    }
    @Test fun holdingsButtonRefreshesAgainWithoutConfusingItWithPrices() {
        val account = Account("ada", "Fixture ADA", Chain.ADA, Chain.ADA.path(0), "public", "public address")
        val state = PortfolioState(listOf(account), mapOf(account.id to AccountSnapshot(listOf(nativePlaceholder(account)))))
        var balances = 0; var prices = 0
        compose.setContent { LensTheme("light") { LensApp(state, emptyList(), null, { balances++ }, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, {}, onPrices = { prices++ }) } }
        scrollToText("更新持倉餘額").performClick().performClick()
        scrollToText("更新價格 · 前景每 30 秒").performClick()
        compose.runOnIdle { assertEquals(2, balances); assertEquals(1, prices) }
    }
}
