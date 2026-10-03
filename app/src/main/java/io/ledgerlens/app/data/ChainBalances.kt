package io.ledgerlens.app.data

import io.ledgerlens.app.crypto.*
import io.ledgerlens.app.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger

class ChainBalances(private val http: Http) {
    private fun BigInteger.checkedInt(): Int {
        require(this >= BigInteger.valueOf(Int.MIN_VALUE.toLong()) && this <= BigInteger.valueOf(Int.MAX_VALUE.toLong())) { "Integer overflow in token metadata" }
        return toInt()
    }
    suspend fun fetch(a: Account, p: Preferences): AccountSnapshot = when(a.chain) {
        Chain.BTC -> btc(a, p)
        Chain.ETH -> eth(a, p)
        Chain.TRON -> tron(a, p)
        Chain.ADA -> ada(a, p)
    }
    private fun native(a: Account, balance: BigDecimal) = Asset("${a.id}:native", a.id, a.chain, a.chain.symbol, a.chain.title, balance, decimals = a.chain.decimals)
    private fun token(a: Account, id: String, symbol: String, name: String, quantity: BigDecimal, decimals: Int) = Asset("${a.id}:$id", a.id, a.chain, symbol.take(32), name.take(128), quantity, id, decimals)
    private suspend fun btc(a: Account, p: Preferences): AccountSnapshot {
        var sum = BigDecimal.ZERO; var capped = false
        var preferred = "https://mempool.space/api"
        suspend fun addressInfo(address: String): JSONObject {
            var failure: Exception? = null
            for (base in listOf(preferred, "https://mempool.space/api", "https://blockstream.info/api").distinct()) {
                try {
                    val data = JSONObject(http.get("$base/address/$address"))
                    check(data.getString("address") == address) { "BTC provider returned the wrong address" }
                    data.getJSONObject("chain_stats"); data.getJSONObject("mempool_stats")
                    preferred = base
                    return data
                } catch (e: CancellationException) { throw e } catch (e: Exception) { failure = e }
            }
            throw failure ?: IllegalStateException("BTC providers unavailable")
        }
        for (branch in 0..1) {
            var gap = 0; var i = 0
            while (gap < p.scanGap && i < p.scanMax) {
                val address = AddressCodec.btc(a.publicKey, branch, i++)
                val data = addressInfo(address)
                val confirmed = data.getJSONObject("chain_stats"); val pending = data.getJSONObject("mempool_stats")
                val used = confirmed.getLong("tx_count") + pending.getLong("tx_count") > 0
                gap = if (used) 0 else gap + 1
                // Show confirmed holdings. Pending outgoing/incoming are not silently folded in.
                sum += rawAmount((confirmed.getLong("funded_txo_sum") - confirmed.getLong("spent_txo_sum")).toString(), 8)
                delay(150)
            }
            if (gap < p.scanGap) capped = true
        }
        return AccountSnapshot(listOf(native(a, sum)), scanLimited = capped)
    }
    private suspend fun eth(a: Account, p: Preferences): AccountSnapshot {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "eth_getBalance").put("params", JSONArray(listOf(a.address, "latest")))
        val rpcUrl = if (p.alchemyKey.isBlank()) "https://ethereum-rpc.publicnode.com" else {
            require(p.alchemyKey.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid Alchemy key" }
            "https://eth-mainnet.g.alchemy.com/v2/${p.alchemyKey}"
        }
        val data = JSONObject(http.post(rpcUrl, body.toString()))
        check(!data.has("error")) { "Ethereum balance RPC failed" }
        val result = data.getString("result")
        require(result.matches(Regex("0x[0-9a-fA-F]+"))) { "Invalid Ethereum balance response" }
        val amount = BigInteger(result.removePrefix("0x"), 16).toString()
        val assets = mutableListOf(native(a, rawAmount(amount, 18)))
        try {
        if (p.alchemyKey.isNotBlank()) {
            require(p.alchemyKey.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid Alchemy key" }
            var pageKey: String? = null; var page = 0
            do {
                check(++page <= 100) { "Alchemy token pagination limit reached" }
                val options = JSONObject().apply { if (pageKey != null) put("pageKey", pageKey) }
                val request = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "alchemy_getTokenBalances").put("params", JSONArray().put(a.address).put("erc20").put(options))
                val response = JSONObject(http.post("https://eth-mainnet.g.alchemy.com/v2/${p.alchemyKey}", request.toString())).getJSONObject("result")
                val tokens = response.getJSONArray("tokenBalances")
                for (i in 0 until tokens.length()) {
                    val entry = tokens.getJSONObject(i); check(entry.isNull("error")) { "Token balance query failed" }
                    val balance = BigInteger(entry.getString("tokenBalance").removePrefix("0x"), 16)
                    val contract = entry.getString("contractAddress").lowercase()
                    val metaRequest = JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "alchemy_getTokenMetadata").put("params", JSONArray().put(contract))
                    val meta = JSONObject(http.post("https://eth-mainnet.g.alchemy.com/v2/${p.alchemyKey}", metaRequest.toString())).getJSONObject("result")
                    val decimals = meta.getInt("decimals")
                    assets += token(a, contract, meta.optString("symbol", "TOKEN"), meta.optString("name", "Token"), rawAmount(balance.toString(), decimals), decimals)
                }
                pageKey = response.optString("pageKey").takeIf { it.isNotBlank() }
            } while (pageKey != null)
            return AccountSnapshot(assets)
        }
        var url: String? = "https://eth.blockscout.com/api/v2/addresses/${a.address}/tokens?type=ERC-20"
        var pages = 0
        while (url != null) {
            check(++pages <= 100) { "Token pagination limit reached" }
            val response = JSONObject(http.get(url)); val tokens = response.getJSONArray("items")
            for (i in 0 until tokens.length()) {
                val row = tokens.getJSONObject(i); val t = row.getJSONObject("token")
                if (t.optString("type") != "ERC-20") continue
                val decimals = t.optString("decimals").toIntOrNull() ?: error("Token decimals unavailable")
                assets += token(a, t.getString("address_hash").lowercase(), t.optString("symbol", "TOKEN"), t.optString("name", "Token"), rawAmount(row.getString("value"), decimals), decimals)
            }
            val next = response.optJSONObject("next_page_params")
            url = if (next == null) null else {
                val query = next.keys().asSequence().map { java.net.URLEncoder.encode(it, "UTF-8") + "=" + java.net.URLEncoder.encode(next.get(it).toString(), "UTF-8") }.joinToString("&")
                "https://eth.blockscout.com/api/v2/addresses/${a.address}/tokens?type=ERC-20&$query"
            }
        }
        return AccountSnapshot(assets)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { return AccountSnapshot(assets, warning = e.message ?: "Ethereum token query failed", complete = false) }
    }
    private suspend fun tron(a: Account, p: Preferences): AccountSnapshot {
        val headers = if (p.tronGridKey.isBlank()) emptyMap() else mapOf("TRON-PRO-API-KEY" to p.tronGridKey)
        val response = JSONObject(http.get("https://api.trongrid.io/v1/accounts/${a.address}?only_confirmed=true", headers))
        if (!response.optBoolean("success", true)) throw ApiException("api.trongrid.io", response.optInt("statusCode", 502))
        val rows = response.getJSONArray("data")
        if (rows.length() == 0) return AccountSnapshot(listOf(native(a, BigDecimal.ZERO)))
        check(rows.length() == 1) { "Unexpected TronGrid account response" }
        val row = rows.getJSONObject(0); val assets = mutableListOf(native(a, rawAmount(row.optString("balance", "0"), 6)))
        val warnings = mutableListOf<String>()
        try {
        val trc10 = row.optJSONArray("assetV2") ?: JSONArray()
        for (i in 0 until trc10.length()) {
            try {
            val entry = trc10.getJSONObject(i); val id = entry.getString("key")
            val meta = JSONObject(http.post("https://api.trongrid.io/wallet/getassetissuebyid", JSONObject().put("value", id).toString(), headers))
            val decimals = meta.optInt("precision", 0)
            fun decoded(key: String) = runCatching { meta.getString(key).unhex().toString(Charsets.UTF_8) }.getOrDefault(id)
            assets += token(a, "trc10:$id", decoded("abbr"), decoded("name"), rawAmount(entry.getString("value"), decimals), decimals)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { warnings += e.message ?: "TRC-10 query failed" }
        }
        // Indexed token balances, paginated; metadata comes from verified contract identity.
        var cursor: String? = null; var pages = 0
        do {
            check(++pages <= 100) { "TRON token pagination limit reached" }
            val endpoint = "https://api.trongrid.io/v1/accounts/${a.address}/trc20/balance?limit=200" + (cursor?.let { "&fingerprint=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
            val tokens = if (pages == 1 && row.has("trc20")) JSONObject().put("data", row.getJSONArray("trc20")) else JSONObject(http.get(endpoint, headers))
            if (!tokens.optBoolean("success", true)) throw ApiException("api.trongrid.io", tokens.optInt("statusCode", 502))
            val list = tokens.getJSONArray("data")
            for (i in 0 until list.length()) {
                val balances = list.getJSONObject(i)
                for (contract in balances.keys()) {
                    try {
                    // Known mainnet Tether identity avoids three extra requests for every refresh.
                    val knownUsdt = contract == "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"
                    val decimals = if (knownUsdt) 6 else BigInteger(tronConstant(a.address, contract, "decimals()", headers), 16).checkedInt()
                    val symbol = if (knownUsdt) "USDT" else abiString(tronConstant(a.address, contract, "symbol()", headers))
                    val name = if (knownUsdt) "Tether USD" else abiString(tronConstant(a.address, contract, "name()", headers))
                    assets += token(a, contract, symbol, name, rawAmount(balances.getString(contract), decimals), decimals)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { warnings += e.message ?: "TRC-20 query failed" }
                }
            }
            val next = tokens.optJSONObject("meta")?.optJSONObject("links")?.optString("next").orEmpty()
            cursor = if (next.isNotEmpty()) tokens.getJSONObject("meta").getString("fingerprint") else null
        } while (cursor != null)
        return AccountSnapshot(assets.distinctBy { it.id }, warning = warnings.distinct().take(3).takeIf { it.isNotEmpty() }?.joinToString("\n"), complete = warnings.isEmpty())
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { return AccountSnapshot(assets.distinctBy { it.id }, warning = e.message ?: "TRON token query failed", complete = false) }
    }
    private suspend fun tronConstant(owner: String, contract: String, selector: String, headers: Map<String, String>): String {
        val body = JSONObject().put("owner_address", owner).put("contract_address", contract).put("function_selector", selector).put("visible", true)
        delay(250)
        val response = JSONObject(http.post("https://api.trongrid.io/wallet/triggerconstantcontract", body.toString(), headers))
        check(response.getJSONObject("result").getBoolean("result")) { "TRON token metadata call failed" }
        return response.getJSONArray("constant_result").getString(0)
    }
    private fun abiString(hex: String): String {
        val bytes = hex.unhex()
        if (bytes.size == 32) return bytes.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
        require(bytes.size >= 64)
        val offset = BigInteger(1, bytes.copyOfRange(0, 32)).checkedInt()
        require(offset >= 32 && offset <= bytes.size - 32)
        val size = BigInteger(1, bytes.copyOfRange(offset, offset + 32)).checkedInt()
        require(size >= 0 && size <= bytes.size - offset - 32)
        return bytes.copyOfRange(offset + 32, offset + 32 + size).toString(Charsets.UTF_8)
    }
    private suspend fun adaKoios(a: Account, p: Preferences): AccountSnapshot {
        val base = "https://api.koios.rest/api/v1"
        val stake = AddressCodec.stake(a.publicKey)
        val discovery = JSONObject().put("_stake_addresses", JSONArray().put(stake)).put("_first_only", false).put("_empty", false)
        val rows = JSONArray(http.post("$base/account_addresses", discovery.toString()))
        val found = mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            check(row.getString("stake_address") == stake) { "Cardano provider returned the wrong stake account" }
            val addresses = row.getJSONArray("addresses")
            for (j in 0 until addresses.length()) found += addresses.getString(j)
        }
        val derived = mutableSetOf<String>()
        // Never trust an address solely because it reuses this account's stake credential.
        for (branch in 0..1) for (i in 0 until p.scanMax) derived += AddressCodec.cardano(a.publicKey, branch, i)
        val directScan = found.isEmpty()
        val owned = if (directScan) derived.toList() else found.filter { it in derived }
        val limited = directScan || found.any { it !in derived }
        var amount = BigDecimal.ZERO
        for (batch in owned.chunked(40)) {
            val body = JSONObject().put("_addresses", JSONArray(batch))
            val info = JSONArray(http.post("$base/address_info", body.toString()))
            val seen = mutableSetOf<String>()
            for (i in 0 until info.length()) {
                val row = info.getJSONObject(i); val address = row.getString("address")
                check(address in batch && seen.add(address)) { "Invalid Cardano address response" }
                amount += rawAmount(row.getString("balance"), 6)
            }
        }
        val assets = mutableListOf(native(a, amount))
        try {
            val totals = mutableMapOf<String, BigDecimal>()
            val scales = mutableMapOf<String, Int>()
            for (batch in owned.chunked(40)) {
                val body = JSONObject().put("_addresses", JSONArray(batch))
                var offset = 0
                do {
                    val info = JSONArray(http.post("$base/address_assets?limit=1000&offset=$offset", body.toString()))
                    for (i in 0 until info.length()) {
                        val row = info.getJSONObject(i)
                        check(row.getString("address") in batch) { "Invalid Cardano asset address" }
                        val unit = row.getString("policy_id") + row.getString("asset_name")
                        require(unit.matches(Regex("[0-9a-fA-F]{56,120}")) && unit.length % 2 == 0)
                        val decimals = if (row.isNull("decimals")) 0 else row.getInt("decimals")
                        check(scales[unit] == null || scales[unit] == decimals) { "Inconsistent Cardano token decimals" }
                        scales[unit] = decimals
                        totals[unit] = (totals[unit] ?: BigDecimal.ZERO) + rawAmount(row.getString("quantity"), decimals)
                    }
                    offset += info.length()
                    check(offset <= 100_000) { "Cardano token pagination limit reached" }
                } while (info.length() == 1000)
            }
            for ((unit, quantity) in totals) {
                val name = runCatching { unit.drop(56).unhex().toString(Charsets.UTF_8) }.getOrDefault(unit.take(12)).ifBlank { unit.take(12) }
                assets += token(a, unit, name, name, quantity, scales.getValue(unit))
            }
            return AccountSnapshot(assets, scanLimited = limited, complete = !limited)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { return AccountSnapshot(assets, warning = e.message ?: "Cardano token query failed", scanLimited = limited, complete = false) }
    }
    private suspend fun ada(a: Account, p: Preferences): AccountSnapshot {
        if (p.blockfrostKey.isBlank()) return adaKoios(a, p)
        val headers = mapOf("project_id" to p.blockfrostKey)
        val stake = AddressCodec.stake(a.publicKey)
        // Stake-linked discovery followed by payment-key validation avoids accepting mangled addresses.
        val derived = mutableSetOf<String>()
        for (branch in 0..1) for (i in 0 until p.scanMax) derived += AddressCodec.cardano(a.publicKey, branch, i)
        val found = mutableListOf<String>(); var page = 1
        try {
            while (true) {
                check(page <= 100) { "Cardano address pagination limit reached" }
                val rows = JSONArray(http.get("https://cardano-mainnet.blockfrost.io/api/v0/accounts/$stake/addresses?count=100&page=${page++}", headers))
                for (i in 0 until rows.length()) found += rows.getJSONObject(i).getString("address")
                if (rows.length() < 100) break
            }
        } catch (e: Exception) {
            // An unregistered stake account may still hold ADA. Only a 404 uses direct scanning.
            if (e.message?.endsWith("HTTP 404") != true) throw e
            for (branch in 0..1) for (i in 0 until p.scanGap) found += AddressCodec.cardano(a.publicKey, branch, i)
        }
        val owned = found.distinct().filter { it in derived }; val capped = found.any { it !in derived }
        var ada = BigDecimal.ZERO; val tokens = mutableMapOf<String, BigDecimal>()
        for (address in owned) {
            val info = try { JSONObject(http.get("https://cardano-mainnet.blockfrost.io/api/v0/addresses/$address", headers)) } catch (e: Exception) { if (e.message?.endsWith("HTTP 404") == true) continue else throw e }
            val amounts = info.getJSONArray("amount")
            for (i in 0 until amounts.length()) { val amount = amounts.getJSONObject(i); val unit = amount.getString("unit"); val q = BigDecimal(amount.getString("quantity")); if (unit == "lovelace") ada += q.movePointLeft(6) else tokens[unit] = (tokens[unit] ?: BigDecimal.ZERO) + q }
        }
        val assets = mutableListOf(native(a, ada))
        for ((unit, quantity) in tokens) {
            val info = JSONObject(http.get("https://cardano-mainnet.blockfrost.io/api/v0/assets/$unit", headers))
            val metadata = info.optJSONObject("metadata")
            val decimals = metadata?.optInt("decimals", 0) ?: 0
            val fallback = runCatching { unit.drop(56).unhex().toString(Charsets.UTF_8) }.getOrDefault(unit.take(12))
            assets += token(a, unit, metadata?.optString("ticker")?.takeIf { it.isNotBlank() } ?: fallback, metadata?.optString("name")?.takeIf { it.isNotBlank() } ?: fallback, quantity.movePointLeft(decimals), decimals)
        }
        return AccountSnapshot(assets, scanLimited = capped)
    }
}
