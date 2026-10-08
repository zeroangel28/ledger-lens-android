package io.ledgerlens.app.alerts

import android.content.Context
import android.app.Application
import io.ledgerlens.app.data.*
import io.ledgerlens.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Foreground and WorkManager share this coordinator; background code only reads the portfolio. */
class PriceAlertController private constructor(private val context: Application) {
    private val store = PriceAlertStore(context)
    private var locked = false
    private var journal = try { store.load() } catch (_: Exception) { locked = true; PriceAlertJournal(PriceAlertStatus(error = "Price alert storage unavailable")) }
    private val mutableStatus = MutableStateFlow(journal.status)
    val status = mutableStatus.asStateFlow()
    private val journalMutex = Mutex()
    private val checkMutex = Mutex()
    private val references = mutableMapOf<String, HourReference>()
    private val notifications = PriceNotifications(context)
    @Volatile private var presentation: Pair<String, Boolean>? = null
    @Volatile var foreground = false
    private set
    fun setForeground(active: Boolean) { foreground = active }
    fun setPresentation(language: String, privacy: Boolean) { presentation = language to privacy }
    fun schedule() { PriceAlertWorker.schedule(context, journal.status.settings, !locked) }
    suspend fun updateSettings(settings: PriceAlertSettings) = journalMutex.withLock {
        check(!locked) { "Price alert storage unavailable" }
        val old = journal.status.settings
        val records = if (old.enabled != settings.enabled || old.thresholdPercent != settings.thresholdPercent) journal.latches.mapValues { (_, l) -> l.copy(above = false) } else journal.latches
        val updated = journal.copy(status = journal.status.copy(settings = settings, error = null), latches = records)
        withContext(Dispatchers.IO) { store.save(updated) }
        journal = updated; mutableStatus.value = updated.status; schedule()
    }
    suspend fun reportFailure(message: String) = journalMutex.withLock { mutableStatus.value = journal.status.copy(error = message) }
    fun testNotification(state: PortfolioState) = notifications.post(null, state.preferences.language, state.preferences.privacy)

    suspend fun check(state: PortfolioState, supplied: Map<String, Quote>? = null) {
        if (locked || !status.value.settings.enabled || !checkMutex.tryLock()) return
        try {
            if (supplied == null && (!status.value.settings.background || foreground)) return
            if (!notifications.available()) { reportFailure("Price alert notifications blocked"); return }
            val startSettings = status.value.settings
            val assets = monitoredPriceAssets(state.assets)
            val saved = journalMutex.withLock { journal }
            val markets = (state.spotMarkets.keys + saved.markets.keys).associateWith { pair -> listOfNotNull(state.spotMarkets[pair], saved.markets[pair]).maxBy { it.checkedAt } }
            val prices = BinancePrices(Http(), markets)
            val batch = if (supplied == null) prices.prices(assets) else PriceBatch(supplied)
            val now = System.currentTimeMillis()
            val evaluations = mutableMapOf<String, Pair<Quote, HourReference>>()
            val errors = mutableListOf<String>(); batch.error?.let { errors += it }
            val activePairs = assets.mapNotNull { PriceMapping.symbol(it)?.plus("USDT") }.toSet()
            references.keys.retainAll(activePairs)
            val concurrent = Semaphore(3)
            val fetched = coroutineScope { assets.map { asset -> async { concurrent.withPermit {
                val pair = PriceMapping.symbol(asset)!! + "USDT"
                val quote = batch.quotes[asset.id]
                if (quote == null || quote.state != QuoteState.LIVE || quote.price.signum() <= 0 || now - quote.at !in 0..90_000) return@withPermit Triple(pair, null, null)
                try {
                    val anchor = now / 60_000 * 60_000 - PRICE_ALERT_WINDOW_MS
                    val reference = references[pair]?.takeIf { it.at == anchor } ?: prices.hourReference(pair, now)
                    Triple(pair, quote to reference, null)
                } catch (e: CancellationException) { throw e } catch (e: Exception) { Triple(pair, null, "$pair: ${e.message ?: "One-hour price unavailable"}") }
            } } }.awaitAll() }
            for ((pair, data, error) in fetched) {
                if (data != null) { evaluations[pair] = data; references[pair] = data.second }
                error?.let { errors += it }
            }
            journalMutex.withLock {
                // A user can disable/change alerts during network I/O; discard that obsolete check.
                if (journal.status.settings != startSettings || !startSettings.enabled) return@withLock
                if (supplied == null && foreground) return@withLock
                val records = journal.latches.toMutableMap(); val moves = mutableListOf<PriceMove>()
                var checkedCount = 0
                val checkedAt = System.currentTimeMillis()
                for ((pair, data) in evaluations) {
                    if (checkedAt - data.first.at !in 0..90_000 || checkedAt - data.second.at !in PRICE_ALERT_WINDOW_MS until PRICE_ALERT_WINDOW_MS + 60_000) { errors += "$pair: One-hour reference expired"; continue }
                    val result = evaluatePriceMove(pair, data.first, data.second, startSettings.thresholdPercent, records[pair], checkedAt)
                    checkedCount++
                    records[pair] = result.latch; result.move?.let { moves += it }
                }
                // Retain cooldowns across portfolio removal/reimport. Expired records can be pruned.
                val kept = records.filter { (pair, latch) -> pair in activePairs || checkedAt < latch.lastSentAt || checkedAt - latch.lastSentAt < PRICE_ALERT_WINDOW_MS }
                val updated = journal.copy(status = journal.status.copy(lastCheckedAt = if (checkedCount > 0) checkedAt else journal.status.lastCheckedAt, error = errors.distinct().takeIf { it.isNotEmpty() }?.joinToString("\n")), latches = kept, markets = markets + prices.cache)
                withContext(Dispatchers.IO) { store.save(updated) }
                journal = updated; mutableStatus.value = updated.status
                // Persistence precedes delivery, avoiding duplicate alerts after a crash/restart.
                val display = presentation ?: (state.preferences.language to state.preferences.privacy)
                moves.forEach { if (!notifications.post(it, display.first, display.second)) mutableStatus.value = updated.status.copy(error = "Price alert notifications blocked") }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { reportFailure(e.message ?: "Price alert check failed") }
        finally { checkMutex.unlock() }
    }
    companion object {
        @Volatile private var instance: PriceAlertController? = null
        fun get(context: Context): PriceAlertController = instance ?: synchronized(this) { instance ?: PriceAlertController(context.applicationContext as Application).also { instance = it } }
    }
}
