package io.ledgerlens.app.data

import io.ledgerlens.app.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder

/** Symbols are mapped from native chain identity or verified contracts, never token names. */
object PriceMapping {
    private val contracts = mapOf(
        "ETH:0xdac17f958d2ee523a2206206994597c13d831ec7" to "USDT",
        "ETH:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48" to "USDC",
        "ETH:0x6b175474e89094c44da98b954eedeac495271d0f" to "DAI",
        "ETH:0x2260fac5e5542a773aa44fbcfedf7c193bc2c599" to "WBTC",
        "ETH:0x514910771af9ca656af840dff83e8264ecf986ca" to "LINK",
        "ETH:0x7d1afa7b718fb893db30a3abc0cfc608aacfebb0" to "MATIC",
        "TRON:TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t" to "USDT",
        // Cardano native asset: the full policy ID + hex asset name, verified against
        // Midnight's NIGHT identity and cardano-foundation/cardano-token-registry.
        "ADA:0691b2fecca1ac4f53cb6dfb00b7013e561d1f34403b957cbb5af1fa4e49474854" to "NIGHT"
    )
    fun symbol(a: Asset): String? {
        val contract = a.contract ?: return a.chain.symbol
        // Ethereum and Cardano identities are hex; TRON's Base58 identity is case-sensitive.
        val identity = if (a.chain == Chain.ETH || a.chain == Chain.ADA) contract.lowercase() else contract
        return contracts["${a.chain.name}:$identity"]
    }
}
data class PriceBatch(val quotes: Map<String, Quote>, val error: String? = null)

class BinancePrices(private val http: Http, cached: Map<String, SpotMarket> = emptyMap(), private val clock: () -> Long = System::currentTimeMillis) {
    private val listings = cached.toMutableMap()
    private val mutex = Mutex()
    val cache: Map<String, SpotMarket> get() = listings.toMap()
    private suspend fun readSpot(path: String): String {
        var failure: Exception? = null
        for (base in listOf("https://data-api.binance.vision", "https://api.binance.com")) {
            try { return http.get(base + path) }
            catch (e: CancellationException) { throw e }
            catch (e: ApiException) {
                if (e.status in listOf(400, 401, 403, 418, 429, 451)) throw e
                failure = e
            } catch (e: Exception) { failure = e }
        }
        throw failure ?: IllegalStateException("Binance Spot unavailable")
    }
    suspend fun hourReference(pair: String, now: Long): HourReference {
        require(pair.matches(Regex("[A-Z0-9]{1,32}USDT")))
        val anchor = now / 60_000 * 60_000 - PRICE_ALERT_WINDOW_MS
        val rows = JSONArray(readSpot("/api/v3/klines?symbol=$pair&interval=1m&startTime=$anchor&endTime=${anchor + 59_999}&limit=1"))
        check(rows.length() == 1) { "One-hour Binance reference unavailable" }
        val row = rows.getJSONArray(0)
        check(row.getLong(0) == anchor && row.getLong(6) == anchor + 59_999) { "Unexpected Binance reference timestamp" }
        val price = BigDecimal(row.getString(1)); require(price.signum() > 0)
        return HourReference(price, anchor)
    }
    suspend fun prices(assets: List<Asset>): PriceBatch = mutex.withLock {
        val now = clock()
        val bySymbol = mutableMapOf<String, Quote>()
        val errors = mutableListOf<String>()
        val requested = assets.mapNotNull { PriceMapping.symbol(it) }.filter { it != "USDT" }.distinct()
        val live = mutableListOf<String>()
        for (symbol in requested) {
            val pair = symbol + "USDT"
            try {
                val cached = listings[pair]
                val tradable = if (cached != null && now >= cached.checkedAt && now - cached.checkedAt < 86_400_000L) cached.tradable else {
                    val result = try {
                        val rows = JSONObject(readSpot("/api/v3/exchangeInfo?symbol=$pair&showPermissionSets=false")).getJSONArray("symbols")
                        check(rows.length() == 1) { "Binance returned incomplete exchange info" }
                        val row = rows.getJSONObject(0)
                        check(row.getString("symbol") == pair && row.getString("quoteAsset") == "USDT" && row.getString("baseAsset") == symbol)
                        row.getString("status") == "TRADING" && row.getBoolean("isSpotTradingAllowed")
                    } catch (e: ApiException) {
                        if (e.status == 400 && e.apiCode == -1121) false else throw e
                    }
                    listings[pair] = SpotMarket(result, now)
                    result
                }
                if (tradable) live += symbol else bySymbol[symbol] = Quote(BigDecimal.ZERO, QuoteState.MISSING, now)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { errors += "$pair: ${e.message ?: "Market query failed"}" }
        }
        suspend fun fetch(symbols: List<String>) {
            if (symbols.isEmpty()) return
            val pairs = symbols.map { it + "USDT" }
            try {
                val query = if (pairs.size == 1) "symbol=${pairs.single()}" else "symbols=" + URLEncoder.encode(JSONArray(pairs).toString(), "UTF-8")
                val text = readSpot("/api/v3/ticker/price?$query")
                val rows = if (pairs.size == 1) JSONArray().put(JSONObject(text)) else JSONArray(text)
                val seen = mutableSetOf<String>()
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i); val pair = row.getString("symbol")
                    check(pair in pairs && seen.add(pair)) { "Binance returned an unexpected price pair" }
                    val price = BigDecimal(row.getString("price")); require(price.signum() > 0)
                    bySymbol[pair.removeSuffix("USDT")] = Quote(price, QuoteState.LIVE, clock())
                }
                (pairs.toSet() - seen).forEach { errors += "$it: Binance did not return a quote" }
            } catch (e: CancellationException) { throw e }
            catch (e: ApiException) {
                if (e.status == 400 && e.apiCode == -1121 && symbols.size > 1) {
                    // A delisting invalidates a batch: isolate it once rather than discard valid quotes.
                    for (symbol in symbols) fetch(listOf(symbol))
                } else if (e.status == 400 && e.apiCode == -1121) {
                    val symbol = symbols.single()
                    listings[symbol + "USDT"] = SpotMarket(false, clock())
                    bySymbol[symbol] = Quote(BigDecimal.ZERO, QuoteState.MISSING, clock())
                } else errors += "${pairs.joinToString()}: ${e.message}"
            } catch (e: Exception) { errors += "${pairs.joinToString()}: ${e.message ?: "Price query failed"}" }
        }
        // Bound URL sizes for portfolios with many verified assets.
        for (batch in live.chunked(50)) fetch(batch)
        val quotes = assets.mapNotNull { a ->
            val symbol = PriceMapping.symbol(a)
            val quote = when {
                symbol == "USDT" -> Quote(BigDecimal.ONE, QuoteState.LIVE, now)
                symbol == null -> Quote(BigDecimal.ZERO, QuoteState.MISSING, now)
                else -> bySymbol[symbol]
            }
            quote?.let { a.id to it }
        }.toMap()
        PriceBatch(quotes, errors.distinct().takeIf { it.isNotEmpty() }?.joinToString("\n"))
    }
}
