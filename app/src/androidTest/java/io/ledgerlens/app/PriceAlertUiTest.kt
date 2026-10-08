package io.ledgerlens.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.ledgerlens.app.model.*
import io.ledgerlens.app.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PriceAlertUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun optInThresholdBackgroundAndTestAreExplicitActions() {
        var status by mutableStateOf(PriceAlertStatus()); var tests = 0
        compose.setContent { LensTheme("dark") { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            PriceAlertSettingsPanel(status, Strings("zh-TW"), { status = status.copy(settings = it) }, { tests++ }, {})
        } } }
        compose.runOnIdle { assertFalse(status.settings.enabled); assertEquals(3, status.settings.thresholdPercent); assertEquals(0, tests) }
        compose.onNodeWithText("±5%").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(5, status.settings.thresholdPercent); assertEquals(0, tests) }
        compose.onNodeWithContentDescription("啟用漲跌通知").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(status.settings.enabled) }
        compose.onNodeWithContentDescription("背景檢查").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(status.settings.background) }
        compose.onNodeWithText("發送測試通知").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, tests) }
    }
    @Test fun blockedNotificationsShowActionInLightThemeWithLargeText() {
        var opened = 0
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) { LensTheme("light") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
                PriceAlertSettingsPanel(PriceAlertStatus(error = "Price alert notifications blocked"), Strings("en"), {}, {}, { opened++ })
            }
        } } }
        compose.onNodeWithText(Strings("en")["alertBlocked"]).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open system notification settings").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }
}
