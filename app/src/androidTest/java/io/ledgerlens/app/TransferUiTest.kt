package io.ledgerlens.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.ledgerlens.app.model.*
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
}
