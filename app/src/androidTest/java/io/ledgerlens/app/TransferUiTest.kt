package io.ledgerlens.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
import io.ledgerlens.app.model.*
import io.ledgerlens.app.crypto.AddressCodec
import io.ledgerlens.app.ui.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.math.BigDecimal

class TransferUiTest {
    @get:Rule val compose = createComposeRule()
    private fun fixture(): Account {
        val i = InstrumentationRegistry.getInstrumentation()
        val row = JSONArray(i.context.assets.open("transaction-fixtures.json").bufferedReader().use { it.readText() }).getJSONObject(5).getJSONObject("account")
        return Account("fixture-ada", "DEMO Cardano", Chain.ADA, Chain.ADA.path(0), row.getString("publicKey"), row.getString("address"))
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun receivingShowsAddressAndQrWithoutDeviceOrNetwork() {
        val account = fixture()
        compose.setContent { LensTheme("dark") { ReceiveScreen(account, Strings("zh-TW"), false, null, {}, {}, {}) } }
        compose.onNodeWithContentDescription("主網收款地址 QR 碼").assertIsDisplayed()
        compose.onNodeWithText(account.address).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("複製地址").performScrollTo().performClick()
        compose.onNodeWithText("已複製").assertIsDisplayed()
        capture("v020-receive.png")
    }
    @Test fun nightReviewSeparatesMinimumAdaAndOnlyExplicitConfirmationCallsSigner() {
        val account = fixture(); val asset = nativePlaceholder(account).copy(id = "night", symbol = "NIGHT", contract = NIGHT_UNIT, decimals = 6)
        val draft = TransferDraft(asset.id, account.id, Chain.ADA, "NIGHT", account.address, BigDecimal("1.5"), BigDecimal("0.18"), BigDecimal("1.2"), "{}", System.currentTimeMillis() + 300000)
        var confirmations = 0
        compose.setContent { LensTheme("dark") { SendScreen(TransferUi(asset.id, draft = draft), asset, account, Strings("zh-TW"), "USB", {}, {}, { _, _, _ -> }, {}, {}, { confirmations++ }) } }
        compose.onNodeWithText("1.2 ADA").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("隨 NIGHT 轉帳附帶 ADA（收款方收到）").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, confirmations) }
        capture("v020-review.png")
        compose.onNodeWithText("確認並交由 Ledger 簽署").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, confirmations) }
    }
    @Test fun simplifiedChineseWorkingStateDisablesSigningButton() {
        val account = fixture(); val asset = nativePlaceholder(account)
        val draft = TransferDraft(asset.id, account.id, Chain.ADA, "ADA", account.address, BigDecimal.ONE, BigDecimal("0.18"), BigDecimal.ZERO, "{}", System.currentTimeMillis() + 300000)
        compose.setContent { LensTheme("light") { SendScreen(TransferUi(asset.id, draft = draft, working = true, stage = "ledgerApproval"), asset, account, Strings("zh-CN"), "BLE", {}, {}, { _, _, _ -> }, {}, {}, {}) } }
        compose.onNodeWithText("确认并交由 Ledger 签署").performScrollTo().assertIsNotEnabled()
    }
    @Test fun englishEntryHasPasteQrAndMaximumButNoAutomaticSigning() {
        val account = fixture(); val asset = nativePlaceholder(account)
        compose.setContent { LensTheme("light") { SendScreen(TransferUi(asset.id), asset, account, Strings("en"), null, {}, {}, { _, _, _ -> }, {}, {}, {}) } }
        compose.onNodeWithText("Paste address").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Scan QR").assertIsDisplayed()
        compose.onNodeWithText("Maximum · reserve fees").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Estimate fees and review").performScrollTo().assertIsNotEnabled()
        capture("v020-send-light.png")
    }
    @Test fun generatedBitcoinAddressCanBeViewedCopiedAndVerifiedWithoutChangingSourceAccount() {
        val key = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
        val original = Account("btc", "DEMO Bitcoin", Chain.BTC, Chain.BTC.path(0), key, AddressCodec.btc(key, 0, 0))
        var a by mutableStateOf(original); var verified = -1
        compose.setContent { LensTheme("dark") { ReceiveScreen(a, Strings("zh-TW"), false, "USB", {}, {}, { verified = it }, onGenerate = { a = a.copy(receiveIndex = a.receiveIndex + 1) }) } }
        compose.onNodeWithText("產生新收款地址").performScrollTo().performClick()
        compose.onNodeWithText("收款地址 #2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("m/84'/0'/0'/0/1").assertIsDisplayed()
        compose.onNodeWithText("bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g").performScrollTo().assertIsDisplayed()
        capture("v021-receive-btc-dark.png")
        compose.onNodeWithText("在 Ledger 核對地址").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, verified); assertEquals(original.address, a.address) }
        compose.onNodeWithContentDescription("上一個收款地址").performScrollTo().performClick()
        compose.onNodeWithText(original.address).performScrollTo().assertIsDisplayed()
    }
    @Test fun receivingHistoryRemainsUsableWithLargeTextAndLightTheme() {
        val a = fixture().copy(receiveIndex = 2)
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) { LensTheme("light") { ReceiveScreen(a, Strings("en"), false, null, {}, {}, {}, onGenerate = {}) } } }
        compose.onNodeWithText("Generate new receiving address").performScrollTo().assertIsEnabled()
        compose.onNodeWithText(a.receivingAddress().address).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous receiving address").performScrollTo().performClick()
        compose.onNodeWithText("Receiving address #2").assertIsDisplayed()
        capture("v021-receive-ada-large-light.png")
    }
    @Test fun historicalAddressSelectionSurvivesStateRestoration() {
        val a = fixture().copy(receiveIndex = 2)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { LensTheme("dark") { ReceiveScreen(a, Strings("en"), false, null, {}, {}, {}) } }
        compose.onNodeWithContentDescription("Previous receiving address").performScrollTo().performClick()
        compose.onNodeWithText("Receiving address #2").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Receiving address #2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(a.receivingAddress(1).address).performScrollTo().assertIsDisplayed()
    }
}
