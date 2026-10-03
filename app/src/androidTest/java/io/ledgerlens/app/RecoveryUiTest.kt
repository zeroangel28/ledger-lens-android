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
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, name).outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun pricesRemainVisibleWhenTronBalanceIsUnavailableAndCardanoShowsNextSync() {
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
        compose.onNodeWithText("單價（USDT） · 0.15").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("餘額尚未取得").assertIsDisplayed()
        capture("recovery-prices.png")
        compose.onNodeWithText("帳戶").performClick()
        compose.onNodeWithText("TronGrid 拒絕或限制查詢。", substring = true).performScrollTo().assertIsDisplayed()
        capture("recovery-tron.png")
        compose.onNodeWithText("下次可同步持倉", substring = true).performScrollTo().assertIsDisplayed()
        capture("recovery-cardano.png")
    }
    @Test fun settingsSaveTronGridKeyAndExplainDailyCardanoBudget() {
        var saved = Preferences()
        compose.setContent {
            var state by remember { mutableStateOf(PortfolioState()) }
            LensTheme("system") { LensApp(state, emptyList(), null, {}, {}, {}, {}, {}, { _, _, _ -> }, { saved = it; state = state.copy(preferences = it) }, {}, {}, {}) }
        }
        compose.onNodeWithText("設定").performClick()
        compose.onNodeWithText("TronGrid API key").performScrollTo().performTextInput("test-data-service-key")
        compose.onNodeWithText("前往 TronGrid 建立 API key").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("儲存設定").onFirst().performScrollTo().performClick()
        compose.runOnIdle { assertEquals("test-data-service-key", saved.tronGridKey) }
        compose.onNodeWithText("Cardano 日常持倉每帳戶 24 小時同步一次", substring = true).performScrollTo().assertIsDisplayed()
        capture("recovery-settings.png")
    }
    @Test fun knownBalanceWithoutQuotesDoesNotShowZeroTotal() {
        val account = Account("eth", "DEMO ETH", Chain.ETH, Chain.ETH.path(0), "DEMO", "DEMO ADDRESS")
        val asset = nativePlaceholder(account).copy(quantity = BigDecimal.ONE, balanceKnown = true)
        val state = PortfolioState(listOf(account), mapOf(account.id to AccountSnapshot(listOf(asset))))
        compose.setContent { LensTheme("system") { LensApp(state, emptyList(), null, {}, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, {}) } }
        compose.onNodeWithText("—").assertIsDisplayed()
        compose.onNodeWithText("0.00").assertDoesNotExist()
        compose.onNodeWithText("單價（USDT） · —").performScrollTo().assertIsDisplayed()
    }
}
