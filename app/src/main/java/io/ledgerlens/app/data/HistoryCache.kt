package io.ledgerlens.app.data

import android.content.Context
import android.util.AtomicFile
import io.ledgerlens.app.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal

/** Public Binance candles only. This cache contains no account identities or holding quantities. */
class HistoryCache(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "spot-history.json"))
    fun load(): Map<String, DailyPriceHistory> = try {
        if (!file.baseFile.exists()) emptyMap() else decode(JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }))
    } catch (_: Exception) { emptyMap() } // A disposable public cache can safely be fetched again.
    fun save(data: Map<String, DailyPriceHistory>) {
        val stream = file.startWrite()
        try { stream.write(encode(data).toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    companion object {
        fun encode(data: Map<String, DailyPriceHistory>): JSONObject {
            val pairs = JSONObject()
            data.forEach { (pair, h) ->
                val rows = JSONArray(); h.closes.toSortedMap().forEach { (day, price) -> rows.put(JSONArray().put(day).put(price.toPlainString())) }
                pairs.put(pair, JSONObject().put("start", h.startDay).put("end", h.endDay).put("checked", h.checkedAt).put("closes", rows))
            }
            return JSONObject().put("schema", 1).put("pairs", pairs)
        }
        fun decode(root: JSONObject): Map<String, DailyPriceHistory> {
            require(root.getInt("schema") == 1)
            val pairs = root.getJSONObject("pairs"); require(pairs.length() <= 500)
            return pairs.keys().asSequence().associateWith { pair ->
                require(pair.matches(Regex("[A-Z0-9]{1,32}USDT")))
                val h = pairs.getJSONObject(pair); val start = h.getLong("start"); val end = h.getLong("end")
                require(end - start in 1..190)
                val rows = h.getJSONArray("closes"); require(rows.length() <= end - start)
                val closes = mutableMapOf<Long, BigDecimal>()
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONArray(i); val day = row.getLong(0); val price = BigDecimal(row.getString(1))
                    require(day in start until end && price.signum() > 0 && day !in closes)
                    closes[day] = price
                }
                DailyPriceHistory(start, end, h.getLong("checked"), closes)
            }
        }
    }
}
