package io.ledgerlens.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.ledgerlens.app.model.*
import io.ledgerlens.app.ui.*
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class HistoryChartUiTest {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.parse("2026-10-09")
    private fun fixture() = PortfolioHistory((HistoryRange.HALF_YEAR.start(today).toEpochDay()..today.toEpochDay()).map { HistoryPoint(it, BigDecimal(it - today.toEpochDay() + 1000), 0) })
    @Test fun defaultsToOneMonthAndSupportsFourRangesDateButtonsAndDataTable() {
        compose.setContent { LensTheme("dark") { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { HistoryChartCard(fixture(), false, Strings("zh-TW")) } } }
        compose.onNodeWithText("一個月").assertIsSelected()
        compose.onNodeWithText("半年").performClick().assertIsSelected()
        compose.onNodeWithText("三個月").performClick().assertIsSelected()
        compose.onNodeWithText("一星期").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("前一天").performScrollTo().performClick()
        compose.onNodeWithText("999.00 USDT").assertExists()
        compose.onNodeWithText("查看每日資料").performScrollTo().performClick()
        compose.onNodeWithText("2026/10/02 UTC").assertExists()
    }
    @Test fun privacyModeRemovesChartValuesAndTableFromSemantics() {
        compose.setContent { LensTheme("dark") { HistoryChartCard(fixture(), true, Strings("en")) } }
        compose.onNodeWithText("Chart and estimates hidden in privacy mode").assertExists()
        compose.onNodeWithText("1000.00 USDT").assertDoesNotExist()
        compose.onNodeWithText("View daily data").assertDoesNotExist()
    }
    @Test fun largeTextLightThemeKeepsMissingDataAndErrorExplanationsAccessible() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) { LensTheme("light") {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                HistoryChartCard(fixture().copy(points = fixture().points.map { it.copy(missingPrices = 1) }, unknownBalances = 1, error = "binance: HTTP 429"), false, Strings("en"))
            }
        } } }
        compose.onNodeWithText(Strings("en")["historyMissing"]).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(Strings("en")["historyUnknown"]).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry historical prices").performScrollTo().assertIsDisplayed()
    }
}
