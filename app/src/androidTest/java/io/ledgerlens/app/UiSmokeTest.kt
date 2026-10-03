package io.ledgerlens.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class UiSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun emptyPortfolioCanPreviewAndSwitchLanguages() {
        compose.onNodeWithText("從你的 Ledger 開始").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("預覽介面").performScrollTo().performClick()
        compose.onNodeWithText("示範資料 · 不代表真實持倉或即時價格").assertExists()
        compose.onNodeWithText("離開預覽").performClick()
        compose.onNodeWithText("設定", useUnmergedTree = false).performClick()
        compose.onNodeWithText("English").performClick()
        compose.onNodeWithText("Portfolio").performClick()
        compose.onNodeWithText("Your Ledger. Your overview.").performScrollTo().assertIsDisplayed()
    }
}
