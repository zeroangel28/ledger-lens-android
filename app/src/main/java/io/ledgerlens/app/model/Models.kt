package io.ledgerlens.app.model

import java.math.BigDecimal

enum class Chain(val symbol: String, val title: String, val coinType: Int, val decimals: Int) {
    BTC("BTC", "Bitcoin", 0, 8), ETH("ETH", "Ethereum", 60, 18), TRON("TRX", "TRON", 195, 6), ADA("ADA", "Cardano", 1815, 6);
    fun path(index: Int) = when (this) {
        BTC -> "84'/0'/$index'"
        ADA -> "1852'/1815'/$index'"
        else -> "44'/$coinType'/$index'/0/0"
    }
}
data class Account(val id: String, val name: String, val chain: Chain, val path: String, val publicKey: String, val address: String, val importedAt: Long = System.currentTimeMillis())
enum class QuoteState { LIVE, MISSING, STALE }
data class Quote(val price: BigDecimal, val state: QuoteState, val at: Long)
data class SpotMarket(val tradable: Boolean, val checkedAt: Long)
data class Asset(
    val id: String, val accountId: String, val chain: Chain, val symbol: String, val name: String,
    val quantity: BigDecimal, val contract: String? = null, val decimals: Int = 0,
    val quote: Quote? = null, val hidden: Boolean = false, val balanceKnown: Boolean = true
) {
    val value: BigDecimal get() = quantity.multiply(quote?.price ?: BigDecimal.ZERO)
}
data class Preferences(val language: String = "zh-TW", val theme: String = "system", val hideZeroBalance: Boolean = false, val hideZeroValue: Boolean = false, val revealHidden: Boolean = false, val privacy: Boolean = false, val scanGap: Int = 20, val scanMax: Int = 200, val blockfrostKey: String = "", val alchemyKey: String = "", val tronGridKey: String = "")
data class AccountSnapshot(val assets: List<Asset>, val error: String? = null, val at: Long = System.currentTimeMillis(), val scanLimited: Boolean = false, val warning: String? = null, val complete: Boolean = true, val lastAttemptAt: Long = 0)
const val CARDANO_SYNC_INTERVAL_MS = 24L * 60 * 60 * 1000
fun cardanoSyncDue(snapshot: AccountSnapshot?, now: Long): Boolean = snapshot == null || snapshot.lastAttemptAt == 0L || now - snapshot.lastAttemptAt >= CARDANO_SYNC_INTERVAL_MS
data class PortfolioState(val accounts: List<Account> = emptyList(), val snapshots: Map<String, AccountSnapshot> = emptyMap(), val preferences: Preferences = Preferences(), val busy: Boolean = false, val message: String? = null, val priceError: String? = null, val updatedAt: Long? = null, val spotMarkets: Map<String, SpotMarket> = emptyMap(), val priceBusy: Boolean = false, val transfers: List<TransferRecord> = emptyList()) {
    val assets get() = accounts.flatMap { snapshots[it.id]?.assets.orEmpty() }
    val total get() = assets.fold(BigDecimal.ZERO) { sum, a -> sum + a.value }
    val visible get() = assets.filter { isVisible(it, preferences) }
    val hasKnownHoldings get() = assets.any { it.balanceKnown }
    val hasKnownValuation get() = assets.any { it.balanceKnown && it.quote != null }
    val valuationComplete get() = accounts.all { snapshots[it.id]?.let { s -> s.complete && s.error == null && !s.scanLimited && s.warning == null } == true } && assets.all { it.balanceKnown && it.quote != null && it.quote.state != QuoteState.STALE }
}
fun isVisible(a: Asset, p: Preferences): Boolean = (p.revealHidden || !a.hidden) && (!p.hideZeroBalance || !a.balanceKnown || a.quantity.signum() != 0) && (!p.hideZeroValue || !a.balanceKnown || a.quote == null || a.quote.state == QuoteState.STALE || a.value.signum() != 0)
fun nativePlaceholder(a: Account) = Asset("${a.id}:native", a.id, a.chain, a.chain.symbol, a.chain.title, BigDecimal.ZERO, decimals = a.chain.decimals, balanceKnown = false)

/** Only a complete discovery may turn absent assets into zero balances. */
fun mergeSnapshot(fresh: AccountSnapshot, old: AccountSnapshot?): AccountSnapshot {
    val current = fresh.assets.map { a ->
        val previous = old?.assets?.find { it.id == a.id }
        a.copy(hidden = previous?.hidden ?: false, quote = previous?.quote?.copy(state = QuoteState.STALE))
    }
    val missing = old?.assets.orEmpty().filter { previous -> current.none { it.id == previous.id } }.map {
        if (fresh.complete) it.copy(quantity = BigDecimal.ZERO, balanceKnown = true, quote = it.quote?.copy(state = QuoteState.STALE))
        else it.copy(quote = it.quote?.copy(state = QuoteState.STALE))
    }
    return fresh.copy(assets = current + missing)
}
fun rawAmount(raw: String, decimals: Int): BigDecimal {
    require(decimals in 0..255)
    return BigDecimal(raw).movePointLeft(decimals)
}
