package io.ledgerlens.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.ledgerlens.app.model.*
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PriceAlertJournal(val status: PriceAlertStatus = PriceAlertStatus(), val latches: Map<String, PriceAlertLatch> = emptyMap(), val markets: Map<String, SpotMarket> = emptyMap())

/** Separate encrypted file: background alerts never overwrite accounts, balances or transfer state. */
class PriceAlertStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "price-alerts.enc"))
    private fun key(): SecretKey {
        val alias = "ledger-lens-price-alerts"
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (keys.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); generateKey()
        }
    }
    fun load(): PriceAlertJournal {
        if (!file.baseFile.exists()) return PriceAlertJournal()
        val data = file.readFully(); require(data.size > 13 && data[0].toInt() == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(1, 13)))
        return decode(JSONObject(cipher.doFinal(data.copyOfRange(13, data.size)).toString(Charsets.UTF_8)))
    }
    fun save(journal: PriceAlertJournal) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = byteArrayOf(12) + cipher.iv + cipher.doFinal(encode(journal).toString().toByteArray(Charsets.UTF_8))
        val out = file.startWrite()
        try { out.write(data); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }
    companion object {
        fun encode(j: PriceAlertJournal): JSONObject {
            val s = j.status; val p = s.settings
            val records = JSONObject(); j.latches.forEach { (pair, l) -> records.put(pair, JSONObject().put("threshold", l.threshold).put("above", l.above).put("sent", l.lastSentAt).put("below", l.below).put("fallSent", l.lastFallSentAt)) }
            val markets = JSONObject(); j.markets.forEach { (pair, m) -> markets.put(pair, JSONObject().put("tradable", m.tradable).put("at", m.checkedAt)) }
            return JSONObject().put("schema", 1).put("enabled", p.enabled).put("threshold", p.thresholdPercent).put("background", p.background).put("checked", s.lastCheckedAt).put("error", s.error).put("latches", records).put("markets", markets)
                .put("backgroundAttempt", s.lastBackgroundAttemptAt).put("backgroundChecked", s.lastBackgroundCheckAt)
                .put("backgroundResult", s.backgroundResult?.name).put("backgroundError", s.backgroundError)
                .put("backgroundTest", s.lastBackgroundTestAt).put("backgroundTestError", s.backgroundTestError)
        }
        fun decode(root: JSONObject): PriceAlertJournal {
            require(root.getInt("schema") == 1)
            val settings = PriceAlertSettings(root.getBoolean("enabled"), root.getInt("threshold"), root.getBoolean("background"))
            val latches = root.getJSONObject("latches").let { all -> all.keys().asSequence().associateWith { key ->
                require(key.matches(Regex("[A-Z0-9]{1,32}USDT")))
                all.getJSONObject(key).let { PriceAlertLatch(it.getInt("threshold"), it.getBoolean("above"), it.getLong("sent"), it.optBoolean("below", false), it.optLong("fallSent", 0)) }
            } }
            val markets = root.getJSONObject("markets").let { all -> all.keys().asSequence().associateWith { key -> all.getJSONObject(key).let { SpotMarket(it.getBoolean("tradable"), it.getLong("at")) } } }
            val result = root.optString("backgroundResult").takeIf { it.isNotEmpty() }?.let { value -> AlertCheckResult.entries.firstOrNull { it.name == value } }
            val status = PriceAlertStatus(settings, root.getLong("checked"), root.optString("error").takeIf { it.isNotEmpty() },
                root.optLong("backgroundAttempt"), root.optLong("backgroundChecked"), result, root.optString("backgroundError").takeIf { it.isNotEmpty() },
                root.optLong("backgroundTest"), root.optString("backgroundTestError").takeIf { it.isNotEmpty() })
            return PriceAlertJournal(status, latches, markets)
        }
    }
}
