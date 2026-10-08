package io.ledgerlens.app

import io.ledgerlens.app.data.*
import io.ledgerlens.app.model.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class PriceAlertTests {
    private val now = 10 * PRICE_ALERT_WINDOW_MS + 20_000
    private fun reference(at: Long = now / 60_000 * 60_000 - PRICE_ALERT_WINDOW_MS) = HourReference(BigDecimal("100"), at)
    private fun quote(price: String, at: Long = now) = Quote(BigDecimal(price), QuoteState.LIVE, at)
    @Test fun defaultsAreOptInThreePercentAndBackgroundEnabled() {
        assertEquals(PriceAlertSettings(false, 3, true), PriceAlertSettings())
        assertThrows(IllegalArgumentException::class.java) { PriceAlertSettings(thresholdPercent = 4) }
    }
    @Test fun exactThresholdTriggersInBothDirectionsWithoutRounding() {
        assertNull(evaluatePriceMove("BTCUSDT", quote("102.99999"), reference(), 3, null, now).move)
        assertEquals(BigDecimal("3.00000000"), evaluatePriceMove("BTCUSDT", quote("103"), reference(), 3, null, now).move!!.percent)
        assertNull(evaluatePriceMove("BTCUSDT", quote("104.99"), reference(), 5, null, now).move)
        assertNotNull(evaluatePriceMove("BTCUSDT", quote("105"), reference(), 5, null, now).move)
        assertNull(evaluatePriceMove("BTCUSDT", quote("97.00001"), reference(), 3, null, now).move)
        assertEquals(BigDecimal("-3.00000000"), evaluatePriceMove("BTCUSDT", quote("97"), reference(), 3, null, now).move!!.percent)
        assertNull(evaluatePriceMove("BTCUSDT", quote("95.01"), reference(), 5, null, now).move)
        assertEquals(BigDecimal("-5.00000000"), evaluatePriceMove("BTCUSDT", quote("95"), reference(), 5, null, now).move!!.percent)
    }
    @Test fun continuouslyLowPriceDoesNotRepeatAfterCooldown() {
        val first = evaluatePriceMove("BTCUSDT", quote("97"), reference(), 3, null, now)
        val later = now + PRICE_ALERT_WINDOW_MS * 2
        assertNull(evaluatePriceMove("BTCUSDT", quote("90", later), reference(later / 60_000 * 60_000 - PRICE_ALERT_WINDOW_MS), 3, first.latch, later).move)
    }
    @Test fun fallingRearmRequiresReturnInsideThresholdAndOwnCooldown() {
        val first = evaluatePriceMove("BTCUSDT", quote("97"), reference(), 3, null, now)
        assertTrue(first.latch.below); assertEquals(now, first.latch.lastFallSentAt); assertEquals(0L, first.latch.lastSentAt)
        val inside = evaluatePriceMove("BTCUSDT", quote("99"), reference(), 3, first.latch, now).latch
        assertFalse(inside.below)
        assertNull(evaluatePriceMove("BTCUSDT", quote("96"), reference(), 3, inside, now).move)
        val later = now + PRICE_ALERT_WINDOW_MS
        assertNotNull(evaluatePriceMove("BTCUSDT", quote("96", later), reference(later / 60_000 * 60_000 - PRICE_ALERT_WINDOW_MS), 3, inside, later).move)
    }
    @Test fun oppositeDirectionHasIndependentCooldownAndCannotResetPriorDirection() {
        val rise = evaluatePriceMove("BTCUSDT", quote("103"), reference(), 3, null, now)
        val fall = evaluatePriceMove("BTCUSDT", quote("97"), reference(), 3, rise.latch, now)
        assertEquals(BigDecimal("-3.00000000"), fall.move!!.percent)
        assertEquals(now, fall.latch.lastSentAt); assertEquals(now, fall.latch.lastFallSentAt)
        assertNull(evaluatePriceMove("BTCUSDT", quote("103"), reference(), 3, fall.latch, now).move)
        val fallFirst = evaluatePriceMove("ETHUSDT", quote("97"), reference(), 3, null, now)
        assertNotNull(evaluatePriceMove("ETHUSDT", quote("103"), reference(), 3, fallFirst.latch, now).move)
    }
    @Test fun clockRollbackCannotBypassFallingCooldown() {
        assertNull(evaluatePriceMove("BTCUSDT", quote("97"), reference(), 3, PriceAlertLatch(3, false, below = false, lastFallSentAt = now + 1), now).move)
    }
    @Test fun continuouslyHighPriceDoesNotRepeatAfterCooldown() {
        val first = evaluatePriceMove("BTCUSDT", quote("103"), reference(), 3, null, now)
        val later = now + PRICE_ALERT_WINDOW_MS * 2
        assertNull(evaluatePriceMove("BTCUSDT", quote("110", later), reference(later / 60_000 * 60_000 - PRICE_ALERT_WINDOW_MS), 3, first.latch, later).move)
    }
    @Test fun rearmRequiresBelowThresholdAndOneHourSincePriorNotification() {
        val first = evaluatePriceMove("BTCUSDT", quote("103"), reference(), 3, null, now)
        val down = evaluatePriceMove("BTCUSDT", quote("101"), reference(), 3, first.latch, now).latch
        assertNull(evaluatePriceMove("BTCUSDT", quote("104"), reference(), 3, down, now).move)
        val later = now + PRICE_ALERT_WINDOW_MS
        assertNotNull(evaluatePriceMove("BTCUSDT", quote("104", later), reference(later / 60_000 * 60_000 - PRICE_ALERT_WINDOW_MS), 3, down, later).move)
    }
    @Test fun invalidOrStaleDataNeverTriggersAndClockRollbackCannotBypassCooldown() {
        assertThrows(IllegalArgumentException::class.java) { evaluatePriceMove("BTCUSDT", quote("103").copy(state = QuoteState.STALE), reference(), 3, null, now) }
        assertThrows(IllegalArgumentException::class.java) { evaluatePriceMove("BTCUSDT", quote("103", now - 90_001), reference(), 3, null, now) }
        assertThrows(IllegalArgumentException::class.java) { evaluatePriceMove("BTCUSDT", quote("103", now + 1), reference(), 3, null, now) }
        assertThrows(IllegalArgumentException::class.java) { evaluatePriceMove("BTCUSDT", quote("103"), reference(now - PRICE_ALERT_WINDOW_MS - 60_000), 3, null, now) }
        assertThrows(IllegalArgumentException::class.java) { evaluatePriceMove("BTCUSDT", quote("103"), HourReference(BigDecimal.ZERO, reference().at), 3, null, now) }
        assertNull(evaluatePriceMove("BTCUSDT", quote("103"), reference(), 3, PriceAlertLatch(3, false, now + 1), now).move)
    }
    @Test fun journalPersistsCooldownAndSettingsWithoutPortfolioAccounts() {
        val journal = PriceAlertJournal(PriceAlertStatus(PriceAlertSettings(true, 5, false), now, "fixture error"), mapOf("BTCUSDT" to PriceAlertLatch(5, false, now - 100, true, now)), mapOf("BTCUSDT" to SpotMarket(true, now)))
        val encoded = PriceAlertStore.encode(journal)
        assertEquals(journal, PriceAlertStore.decode(encoded)); assertFalse(encoded.has("accounts")); assertFalse(encoded.has("keys"))
    }
    @Test fun previousJournalPreservesRiseHistoryAndEnablesNewFallAlerts() {
        val original = PriceAlertJournal(PriceAlertStatus(PriceAlertSettings(true, 3, true), now), mapOf("BTCUSDT" to PriceAlertLatch(3, true, now)))
        val legacy = PriceAlertStore.encode(original)
        legacy.getJSONObject("latches").getJSONObject("BTCUSDT").apply { remove("below"); remove("fallSent") }
        val restored = PriceAlertStore.decode(legacy)
        assertEquals(original, restored)
        assertNotNull(evaluatePriceMove("BTCUSDT", quote("97"), reference(), 3, restored.latches["BTCUSDT"], now).move)
    }
    @Test fun monitorOnlyPositiveKnownCanonicalHoldingsAndDeduplicateAccounts() {
        val btc = nativePlaceholder(Account("btc", "Fixture", Chain.BTC, Chain.BTC.path(0), "public", "address")).copy(quantity = BigDecimal.ONE, balanceKnown = true, hidden = true)
        val eth = btc.copy(id = "eth", chain = Chain.ETH)
        val usdt = eth.copy(id = "usdt", symbol = "USDT", contract = ETH_USDT)
        val spoof = eth.copy(id = "spoof", symbol = "BTC", contract = "0xspoof")
        assertEquals(listOf(btc, eth), monitoredPriceAssets(listOf(btc, btc.copy(id = "btc2"), eth, usdt, spoof, btc.copy(id = "zero", quantity = BigDecimal.ZERO), eth.copy(id = "unknown", balanceKnown = false))))
    }
    @Test fun referenceUsesOneMinuteCandleAtOneHourAgoNotDailyChange() = runBlocking {
        var path = ""; val anchor = reference().at
        val http = object : Http() { override suspend fun get(url: String, headers: Map<String, String>): String {
            path = url
            return JSONArray().put(JSONArray().put(anchor).put("100").put("110").put("90").put("105").put("1").put(anchor + 59_999)).toString()
        } }
        assertEquals(reference(), BinancePrices(http).hourReference("BTCUSDT", now))
        assertTrue(path.contains("interval=1m")); assertTrue(path.contains("startTime=$anchor")); assertTrue(path.endsWith("limit=1"))
    }
    @Test fun malformedMissingReferenceOrProviderFailureCannotBecomeZeroPrice() = runBlocking {
        val missing = BinancePrices(object : Http() { override suspend fun get(url: String, headers: Map<String, String>) = "[]" })
        try { missing.hourReference("BTCUSDT", now); fail("Missing reference should fail") } catch (_: IllegalStateException) { }
        val limited = BinancePrices(object : Http() { override suspend fun get(url: String, headers: Map<String, String>): String = throw ApiException("binance", 429) })
        try { limited.hourReference("BTCUSDT", now); fail("Rate limit should fail") } catch (e: ApiException) { assertEquals(429, e.status) }
    }
}
