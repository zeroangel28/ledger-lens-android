package io.ledgerlens.app.model

import io.ledgerlens.app.data.PriceMapping
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

const val HISTORY_DAY_MS = 86_400_000L
enum class HistoryRange(val label: String) {
    WEEK("historyWeek"), MONTH("historyMonth"), QUARTER("historyQuarter"), HALF_YEAR("historyHalfYear");
    fun start(today: LocalDate): LocalDate = when (this) {
        WEEK -> today.minusWeeks(1)
        MONTH -> today.minusMonths(1)
        QUARTER -> today.minusMonths(3)
        HALF_YEAR -> today.minusMonths(6)
    }
}
fun historyToday(now: Long): LocalDate = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
data class DailyPriceHistory(val startDay: Long, val endDay: Long, val checkedAt: Long, val closes: Map<Long, BigDecimal>)
data class HistoryPoint(val day: Long, val value: BigDecimal, val missingPrices: Int)
data class PortfolioHistory(val points: List<HistoryPoint> = emptyList(), val loading: Boolean = false, val error: String? = null, val unknownBalances: Int = 0)

/** Fixed current quantities, including hidden holdings. Unknown balances are excluded, never invented. */
fun valuePortfolioHistory(assets: List<Asset>, histories: Map<String, DailyPriceHistory>, now: Long): List<HistoryPoint> {
    val today = historyToday(now).toEpochDay()
    val start = HistoryRange.HALF_YEAR.start(historyToday(now)).toEpochDay()
    val held = assets.filter { it.balanceKnown && it.quantity.signum() > 0 }
    return (start..today).map { day ->
        var total = BigDecimal.ZERO
        var missing = 0
        for (asset in held) {
            val symbol = PriceMapping.symbol(asset)
            val price = when {
                symbol == "USDT" -> BigDecimal.ONE // USDT is the valuation unit.
                symbol == null -> null
                day == today -> asset.quote?.takeIf { it.state == QuoteState.LIVE && it.price.signum() > 0 && now - it.at in 0..90_000 }?.price
                else -> histories[symbol + "USDT"]?.closes?.get(day)
            }
            if (price == null) missing++ else total += asset.quantity * price
        }
        HistoryPoint(day, total, missing)
    }
}
