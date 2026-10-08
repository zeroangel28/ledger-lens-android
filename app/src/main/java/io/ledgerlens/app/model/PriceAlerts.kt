package io.ledgerlens.app.model

import io.ledgerlens.app.data.PriceMapping
import java.math.BigDecimal
import java.math.RoundingMode

const val PRICE_ALERT_WINDOW_MS = 3_600_000L
data class PriceAlertSettings(val enabled: Boolean = false, val thresholdPercent: Int = 3, val background: Boolean = true) {
    init { require(thresholdPercent in listOf(3, 5)) }
}
data class HourReference(val price: BigDecimal, val at: Long)
data class PriceAlertLatch(val threshold: Int, val above: Boolean, val lastSentAt: Long = 0, val below: Boolean = false, val lastFallSentAt: Long = 0)
data class PriceMove(val pair: String, val price: BigDecimal, val reference: BigDecimal, val percent: BigDecimal, val at: Long)
data class PriceAlertStatus(val settings: PriceAlertSettings = PriceAlertSettings(), val lastCheckedAt: Long = 0, val error: String? = null)
data class AlertEvaluation(val latch: PriceAlertLatch, val move: PriceMove?)

/** Holding identity and quantity select pairs; hidden assets still belong to the portfolio. */
fun monitoredPriceAssets(assets: List<Asset>) = assets.filter {
    it.balanceKnown && it.quantity.signum() > 0 && PriceMapping.symbol(it)?.let { symbol -> symbol != "USDT" } == true
}.distinctBy { PriceMapping.symbol(it) }

/** Minute-open reference is 60–61 minutes old. Exact decimal comparison prevents rounded triggers. */
fun evaluatePriceMove(pair: String, quote: Quote, reference: HourReference, threshold: Int, previous: PriceAlertLatch?, now: Long): AlertEvaluation {
    require(threshold in listOf(3, 5) && pair.matches(Regex("[A-Z0-9]{1,32}USDT")))
    require(quote.state == QuoteState.LIVE && quote.price.signum() > 0 && reference.price.signum() > 0)
    require(now - quote.at in 0..90_000 && now - reference.at in PRICE_ALERT_WINDOW_MS until PRICE_ALERT_WINDOW_MS + 60_000)
    val change = (quote.price - reference.price) * BigDecimal(100)
    val boundary = reference.price * BigDecimal(threshold)
    val above = change >= boundary
    val below = change <= -boundary
    val lastRise = previous?.lastSentAt ?: 0
    val lastFall = previous?.lastFallSentAt ?: 0
    val changedThreshold = previous == null || previous.threshold != threshold
    fun eligible(last: Long) = last == 0L || now >= last && now - last >= PRICE_ALERT_WINDOW_MS
    val rise = above && (changedThreshold || previous?.above != true) && eligible(lastRise)
    val fall = below && (changedThreshold || previous?.below != true) && eligible(lastFall)
    val move = if (rise || fall) PriceMove(pair, quote.price, reference.price, change.divide(reference.price, 8, RoundingMode.HALF_UP), now) else null
    return AlertEvaluation(PriceAlertLatch(threshold, above, if (rise) now else lastRise, below, if (fall) now else lastFall), move)
}
