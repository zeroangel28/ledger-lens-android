package io.ledgerlens.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.ledgerlens.app.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "portfolio.enc"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("ledger-lens-watch-only", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("ledger-lens-watch-only", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()); generateKey()
        }
    }
    @Synchronized fun load(): PortfolioState {
        if (!file.baseFile.exists()) return PortfolioState()
        val raw = file.readFully(); require(raw.size > 13)
        val size = raw[0].toInt() and 255; require(size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(1, size + 1)))
        return decode(JSONObject(cipher.doFinal(raw.copyOfRange(size + 1, raw.size)).toString(Charsets.UTF_8)))
    }
    @Synchronized fun save(state: PortfolioState) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(encode(state).toString().toByteArray(Charsets.UTF_8))
        val out = file.startWrite()
        try { out.write(data); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }
    companion object {
        fun encode(s: PortfolioState): JSONObject {
            val accounts = JSONArray(s.accounts.map { a -> JSONObject().put("id", a.id).put("name", a.name).put("chain", a.chain.name).put("path", a.path).put("key", a.publicKey).put("address", a.address).put("at", a.importedAt) })
            val p = s.preferences
            val prefs = JSONObject().put("language", p.language).put("theme", p.theme).put("zeroBalance", p.hideZeroBalance).put("zeroValue", p.hideZeroValue).put("hidden", p.revealHidden).put("privacy", p.privacy).put("gap", p.scanGap).put("max", p.scanMax).put("blockfrost", p.blockfrostKey).put("alchemy", p.alchemyKey).put("tronGrid", p.tronGridKey)
            val snapshots = JSONObject()
            s.snapshots.forEach { (id, snapshot) ->
                val assets = JSONArray(snapshot.assets.map { a ->
                    JSONObject().put("id", a.id).put("account", a.accountId).put("chain", a.chain.name).put("symbol", a.symbol).put("name", a.name).put("quantity", a.quantity.toPlainString()).put("contract", a.contract).put("decimals", a.decimals).put("hidden", a.hidden).put("balanceKnown", a.balanceKnown).apply {
                        a.quote?.let { put("quote", JSONObject().put("price", it.price.toPlainString()).put("state", it.state.name).put("at", it.at)) }
                    }
                })
                snapshots.put(id, JSONObject().put("assets", assets).put("at", snapshot.at).put("error", snapshot.error).put("limited", snapshot.scanLimited).put("warning", snapshot.warning).put("complete", snapshot.complete).put("lastAttemptAt", snapshot.lastAttemptAt))
            }
            val markets = JSONObject()
            s.spotMarkets.forEach { (pair, market) -> markets.put(pair, JSONObject().put("tradable", market.tradable).put("checkedAt", market.checkedAt)) }
            val transfers = JSONArray(s.transfers.map { t -> JSONObject().put("txid", t.txid).put("accountId", t.accountId).put("chain", t.chain.name).put("symbol", t.symbol).put("recipient", t.recipient).put("amount", t.amount).put("status", t.status.name).put("createdAt", t.createdAt).put("balanceUpdateAttempted", t.balanceUpdateAttempted).put("error", t.error) })
            return JSONObject().put("schema", 1).put("accounts", accounts).put("preferences", prefs).put("snapshots", snapshots).put("updated", s.updatedAt).put("spotMarkets", markets).put("transfers", transfers)
        }
        fun decode(root: JSONObject): PortfolioState {
            require(root.getInt("schema") == 1)
            val accounts = root.getJSONArray("accounts").let { rows -> (0 until rows.length()).map { rows.getJSONObject(it).let { a -> Account(a.getString("id"), a.getString("name"), Chain.valueOf(a.getString("chain")), a.getString("path"), a.getString("key"), a.getString("address"), a.getLong("at")) } } }
            val p = root.getJSONObject("preferences")
            val preferences = Preferences(p.optString("language", "zh-TW"), p.optString("theme", "system"), p.optBoolean("zeroBalance"), p.optBoolean("zeroValue"), p.optBoolean("hidden"), p.optBoolean("privacy"), p.optInt("gap", 20).coerceIn(5, 100), p.optInt("max", 200).coerceIn(20, 1000), p.optString("blockfrost"), p.optString("alchemy"), p.optString("tronGrid"))
            val snapshots = root.getJSONObject("snapshots").let { all -> all.keys().asSequence().associateWith { id ->
                val snapshot = all.getJSONObject(id); val rows = snapshot.getJSONArray("assets")
                val assets = (0 until rows.length()).map { rows.getJSONObject(it).let { a ->
                    Asset(a.getString("id"), a.getString("account"), Chain.valueOf(a.getString("chain")), a.getString("symbol"), a.getString("name"), BigDecimal(a.getString("quantity")), a.optString("contract").takeIf { it.isNotEmpty() }, a.getInt("decimals"), a.optJSONObject("quote")?.let { q -> Quote(BigDecimal(q.getString("price")), QuoteState.valueOf(q.getString("state")), q.getLong("at")) }, a.optBoolean("hidden"), a.optBoolean("balanceKnown", true))
                } }
                AccountSnapshot(assets, snapshot.optString("error").takeIf { it.isNotEmpty() }, snapshot.getLong("at"), snapshot.optBoolean("limited"), snapshot.optString("warning").takeIf { it.isNotEmpty() }, snapshot.optBoolean("complete", true), snapshot.optLong("lastAttemptAt", if (accounts.any { it.id == id && it.chain == Chain.ADA }) snapshot.getLong("at") else 0))
            } }
            val markets = root.optJSONObject("spotMarkets")?.let { rows -> rows.keys().asSequence().filter { it.matches(Regex("[A-Z0-9]{1,32}USDT")) }.associateWith { pair ->
                val row = rows.getJSONObject(pair); SpotMarket(row.getBoolean("tradable"), row.getLong("checkedAt"))
            } }.orEmpty()
            val transfers = root.optJSONArray("transfers")?.let { rows -> (0 until rows.length()).map { index ->
                val t = rows.getJSONObject(index)
                TransferRecord(t.getString("txid"), t.getString("accountId"), Chain.valueOf(t.getString("chain")), t.getString("symbol"), t.getString("recipient"), t.getString("amount"), TransferStatus.valueOf(t.getString("status")), t.getLong("createdAt"), t.optBoolean("balanceUpdateAttempted"), t.optString("error").takeIf { it.isNotBlank() })
            } }.orEmpty()
            return PortfolioState(accounts, snapshots, preferences, updatedAt = if (root.isNull("updated")) null else root.getLong("updated"), spotMarkets = markets, transfers = transfers)
        }
    }
}
