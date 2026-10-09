package io.ledgerlens.app

import io.ledgerlens.app.data.*
import io.ledgerlens.app.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class PortfolioHistoryTests {
    private val now = Instant.parse("2026-10-09T12:00:00Z").toEpochMilli()
    private val today = historyToday(now).toEpochDay()
    private val start = HistoryRange.HALF_YEAR.start(historyToday(now)).toEpochDay()
    private fun asset(id: String = "btc", qty: String = "2") = Asset(id, id, Chain.BTC, "BTC", "Bitcoin", BigDecimal(qty), quote = Quote(BigDecimal("120"), QuoteState.LIVE, now))
    private fun candle(day: Long, close: String = "100") = JSONArray().put(day * HISTORY_DAY_MS).put("999").put("1000").put("1").put(close).put("1").put((day + 1) * HISTORY_DAY_MS - 1).put("0").put(1).put("0").put("0").put("0")
    private fun history(day: Long = today, checked: Long = now, closes: Map<Long, BigDecimal> = mapOf(today - 1 to BigDecimal("100"))) = DailyPriceHistory(HistoryRange.HALF_YEAR.start(LocalDate.ofEpochDay(day)).toEpochDay(), day, checked, closes)
    @Test fun calendarRangesHandleMonthEndsAndLeapYears() {
        val end = LocalDate.parse("2024-08-31")
        assertEquals(LocalDate.parse("2024-08-24"), HistoryRange.WEEK.start(end))
        assertEquals(LocalDate.parse("2024-07-31"), HistoryRange.MONTH.start(end))
        assertEquals(LocalDate.parse("2024-05-31"), HistoryRange.QUARTER.start(end))
        assertEquals(LocalDate.parse("2024-02-29"), HistoryRange.HALF_YEAR.start(end))
        assertEquals(LocalDate.parse("2026-10-09"), historyToday(Instant.parse("2026-10-09T23:59:59Z").toEpochMilli()))
    }
    @Test fun fixedQuantitiesIncludeHiddenAndMultipleAccountsButExcludeUnknownBalances() {
        val assets = listOf(asset().copy(hidden = true), asset("second", "3"), asset("unknown", "99").copy(balanceKnown = false))
        val points = valuePortfolioHistory(assets, mapOf("BTCUSDT" to history()), now)
        assertEquals(start, points.first().day); assertEquals(today, points.last().day)
        assertEquals(BigDecimal("500"), points[points.lastIndex - 1].value)
        assertEquals(BigDecimal("600"), points.last().value)
        assertEquals(BigDecimal.ZERO, points.first().value); assertEquals(2, points.first().missingPrices)
    }
    @Test fun usdtIsTheUnitAndSpoofedOrUnknownTokensNeverAcquireHistoryByTicker() {
        val usdt = asset("usdt", "10").copy(chain = Chain.ETH, symbol = "USDT", contract = ETH_USDT)
        val spoof = usdt.copy(id = "spoof", contract = "0xnot-allowlisted", symbol = "BTC")
        val points = valuePortfolioHistory(listOf(usdt, spoof), mapOf("BTCUSDT" to history()), now)
        assertTrue(points.all { it.value == BigDecimal("10") && it.missingPrices == 1 })
    }
    @Test fun preListingDaysAreZeroWithoutBackfillOrCarryForward() {
        val points = valuePortfolioHistory(listOf(asset()), mapOf("BTCUSDT" to history()), now)
        assertEquals(BigDecimal.ZERO, points.first().value)
        assertEquals(BigDecimal.ZERO, points[points.lastIndex - 2].value)
        assertEquals(BigDecimal("200"), points[points.lastIndex - 1].value)
    }
    @Test fun invalidCurrentQuotesAreZeroAndDoNotBorrowPastPrices() {
        for (quote in listOf(Quote(BigDecimal("120"), QuoteState.STALE, now), Quote(BigDecimal("120"), QuoteState.LIVE, now - 90_001), Quote(BigDecimal("120"), QuoteState.LIVE, now + 1))) {
            val points = valuePortfolioHistory(listOf(asset().copy(quote = quote)), mapOf("BTCUSDT" to history()), now)
            assertEquals(BigDecimal.ZERO, points.last().value); assertEquals(1, points.last().missingPrices)
            assertEquals(BigDecimal("200"), points[points.lastIndex - 1].value)
        }
    }
    @Test fun dailyCandlesUseCloseAndOnlyCompletedUtcDates() = runBlocking {
        var path = ""
        val prices = BinancePrices(object : Http() { override suspend fun get(url: String, headers: Map<String, String>): String { path = url; return JSONArray().put(candle(today - 1, "123.45")).toString() } })
        assertEquals(mapOf(today - 1 to BigDecimal("123.45")), prices.dailyCloses("BTCUSDT", start, today))
        assertTrue(path.contains("interval=1d")); assertTrue(path.contains("startTime=${start * HISTORY_DAY_MS}")); assertTrue(path.contains("endTime=${today * HISTORY_DAY_MS - 1}")); assertTrue(path.endsWith("limit=1000"))
    }
    @Test fun malformedOutOfRangeDuplicateOrUnclosedCandlesFailInsteadOfCachingFalseHistory() = runBlocking {
        for (rows in listOf(JSONArray().put(candle(today)), JSONArray().put(candle(today - 1)).put(candle(today - 1)), JSONArray().put(candle(today - 1, "0")), JSONArray().put(candle(today - 1).put(6, today * HISTORY_DAY_MS)))) {
            val prices = BinancePrices(object : Http() { override suspend fun get(url: String, headers: Map<String, String>) = rows.toString() })
            try { prices.dailyCloses("BTCUSDT", start, today); fail("Invalid candle accepted") } catch (_: IllegalStateException) { }
        }
    }
    @Test fun emptyAndDelistedMarketHistoryAreMissingRatherThanProviderErrors() = runBlocking {
        val empty = BinancePrices(object : Http() { override suspend fun get(url: String, headers: Map<String, String>) = "[]" })
        assertTrue(empty.dailyCloses("NIGHTUSDT", start, today).isEmpty())
        val delisted = BinancePrices(object : Http() { override suspend fun get(url: String, headers: Map<String, String>): String = throw ApiException("binance", 400, -1121) })
        assertTrue(delisted.dailyCloses("BTCUSDT", start, today).isEmpty())
    }
    @Test fun oneDailyReadCoversAllRangesDuplicateAccountsAndRestart() = runBlocking {
        var requests = 0; var saved = emptyMap<String, DailyPriceHistory>()
        val prices = BinancePrices(object : Http() { override suspend fun get(url: String, headers: Map<String, String>): String { requests++; return JSONArray().put(candle(today - 1)).toString() } })
        val repository = HistoryRepository(prices, save = { saved = it }, clock = { now })
        val assets = listOf(asset(), asset("second"))
        repository.load(assets)
        HistoryRange.entries.forEach { repository.load(assets) }
        HistoryRepository(prices, HistoryCache.decode(HistoryCache.encode(saved)), clock = { now + 30_000 }).load(assets)
        assertEquals(1, requests)
    }
    @Test fun missingHistoryIsCachedForTheDayAndUsdtOrUnknownsNeedNoRequest() = runBlocking {
        var requests = 0
        val repository = HistoryRepository(BinancePrices(object : Http() { override suspend fun get(url: String, headers: Map<String, String>): String { requests++; return "[]" } }), clock = { now })
        val usdt = asset().copy(chain = Chain.ETH, contract = ETH_USDT)
        val unknown = usdt.copy(contract = "0xunknown")
        repository.load(listOf(usdt, unknown)); assertEquals(0, requests)
        repository.load(listOf(asset())); repository.load(listOf(asset())); assertEquals(1, requests)
    }
    @Test fun utcDayChangeAndFutureCacheTimestampsRequireRefresh() = runBlocking {
        var time = now; var requests = 0
        val prices = BinancePrices(object : Http() { override suspend fun get(url: String, headers: Map<String, String>): String { requests++; return "[]" } })
        val repository = HistoryRepository(prices, mapOf("BTCUSDT" to history()), clock = { time })
        repository.load(listOf(asset())); assertEquals(0, requests)
        time += HISTORY_DAY_MS; repository.load(listOf(asset())); assertEquals(1, requests)
        HistoryRepository(prices, mapOf("BTCUSDT" to history(checked = now + 1)), clock = { now }).load(listOf(asset())); assertEquals(2, requests)
    }
    @Test fun providerFailureRetainsOldPricesAndRespectsRetryAfterWithoutPoisoningCache() = runBlocking {
        var requests = 0; var time = now
        val old = history(today - 1, now - HISTORY_DAY_MS, mapOf(today - 2 to BigDecimal("100")))
        val repository = HistoryRepository(BinancePrices(object : Http() { override suspend fun get(url: String, headers: Map<String, String>): String { requests++; throw ApiException("binance", 429, retryAfterSeconds = 900) } }), mapOf("BTCUSDT" to old), clock = { time })
        val result = repository.load(listOf(asset()))
        assertEquals(old, result.histories["BTCUSDT"]); assertTrue(result.error!!.contains("429"))
        time += 301_000; repository.load(listOf(asset())); assertEquals(1, requests)
        time += 600_000; repository.load(listOf(asset())); assertEquals(2, requests)
    }
    @Test fun publicCacheRoundTripExcludesPortfolioDataAndRejectsInvalidRows() {
        val encoded = HistoryCache.encode(mapOf("BTCUSDT" to history()))
        assertEquals(mapOf("BTCUSDT" to history()), HistoryCache.decode(encoded))
        assertFalse(encoded.toString().contains("account")); assertFalse(encoded.toString().contains("quantity")); assertFalse(encoded.toString().contains("address"))
        encoded.getJSONObject("pairs").getJSONObject("BTCUSDT").getJSONArray("closes").getJSONArray(0).put(1, "-1")
        assertThrows(IllegalArgumentException::class.java) { HistoryCache.decode(encoded) }
    }
    @Test fun cancellationIsPropagatedAndDoesNotCacheAnEmptySuccess() = runBlocking {
        var requests = 0
        val repository = HistoryRepository(BinancePrices(object : Http() { override suspend fun get(url: String, headers: Map<String, String>): String { requests++; if (requests == 1) throw CancellationException("fixture"); return "[]" } }), clock = { now })
        try { repository.load(listOf(asset())); fail("Cancelled read accepted") } catch (_: CancellationException) { }
        repository.load(listOf(asset())); assertEquals(2, requests)
    }
}
