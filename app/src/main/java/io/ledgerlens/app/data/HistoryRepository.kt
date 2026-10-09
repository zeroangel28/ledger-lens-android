package io.ledgerlens.app.data

import io.ledgerlens.app.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

data class HistoryBatch(val histories: Map<String, DailyPriceHistory>, val error: String? = null)

/** One six-month read per held pair per UTC day; switching ranges only slices local data. */
class HistoryRepository(
    private val prices: BinancePrices,
    initial: Map<String, DailyPriceHistory> = emptyMap(),
    private val save: suspend (Map<String, DailyPriceHistory>) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()
    private val cache = initial.toMutableMap()
    private val failures = mutableMapOf<String, Pair<Long, String>>()
    suspend fun load(assets: List<Asset>): HistoryBatch = mutex.withLock {
        val now = clock(); val today = historyToday(now)
        val start = HistoryRange.HALF_YEAR.start(today).toEpochDay(); val end = today.toEpochDay()
        val pairs = assets.filter { it.balanceKnown && it.quantity.signum() > 0 }.mapNotNull { PriceMapping.symbol(it)?.takeIf { s -> s != "USDT" }?.plus("USDT") }.toSet()
        val semaphore = Semaphore(3)
        val needed = pairs.filter { pair -> cache[pair]?.let { it.startDay <= start && it.endDay == end && now - it.checkedAt in 0 until HISTORY_DAY_MS } != true && failures[pair]?.let { now < it.first } != true }
        val results = coroutineScope { needed.map { pair -> async { semaphore.withPermit {
            try { Triple(pair, DailyPriceHistory(start, end, now, prices.dailyCloses(pair, start, end)), null as Exception?) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Triple(pair, null, e) }
        } } }.awaitAll() }
        var changed = false
        for ((pair, data, error) in results) {
            if (data != null) { cache[pair] = data; failures.remove(pair); changed = true }
            else if (error != null) {
                val retryMs = ((error as? ApiException)?.retryAfterSeconds?.times(1000) ?: 300_000L).coerceAtLeast(300_000L)
                failures[pair] = now + retryMs to "$pair: ${error.message ?: "Historical prices unavailable"}"
            }
        }
        // Bound public cache growth; no address, quantity or portfolio values are persisted here.
        val retained = cache.filterValues { now >= it.checkedAt && now - it.checkedAt <= 31 * HISTORY_DAY_MS }.toMutableMap()
        cache.clear(); cache.putAll(retained)
        val errors = pairs.mapNotNull { failures[it]?.second }.toMutableList()
        if (changed) try { save(cache.toMap()) } catch (e: CancellationException) { throw e } catch (_: Exception) { errors += "History cache could not be saved" }
        HistoryBatch(cache.filterKeys { it in pairs }, errors.takeIf { it.isNotEmpty() }?.distinct()?.joinToString("\n"))
    }
}
