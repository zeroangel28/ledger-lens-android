package io.ledgerlens.app.data

import io.ledgerlens.app.crypto.*
import io.ledgerlens.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/** Read-only preparation and single-attempt submission; unsigned transactions are built locally. */
class TransactionData(private val http: Http) {
    private val koios = "https://api.koios.rest/api/v1"
    private val bf = "https://cardano-mainnet.blockfrost.io/api/v0"
    private fun ethUrl(p: Preferences) = if (p.alchemyKey.isBlank()) "https://ethereum-rpc.publicnode.com" else "https://eth-mainnet.g.alchemy.com/v2/${p.alchemyKey}"
    private fun tronHeaders(p: Preferences) = if (p.tronGridKey.isBlank()) emptyMap() else mapOf("TRON-PRO-API-KEY" to p.tronGridKey)
    private fun bfHeaders(p: Preferences) = mapOf("project_id" to p.blockfrostKey)
    private fun integerHex(value: String) = BigInteger(value.removePrefix("0x").ifEmpty { "0" }, 16)
    private suspend fun rpc(p: Preferences, method: String, params: JSONArray): Any {
        val row = JSONObject(http.post(ethUrl(p), JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params).toString()))
        check(!row.has("error")) { "Ethereum RPC rejected $method: ${row.optJSONObject("error")?.optString("message")}" }
        return row.get("result")
    }
    private suspend fun tron(p: Preferences, method: String, body: JSONObject) = JSONObject(http.post("https://api.trongrid.io/wallet/$method", body.toString(), tronHeaders(p))).also {
        check(!it.has("Error")) { "TRON query failed: ${it.optString("Error")}" }
    }
    suspend fun prepare(a: Account, asset: Asset, recipient: String, units: BigInteger, maximum: Boolean, p: Preferences): JSONObject {
        require(canTransfer(asset) && asset.accountId == a.id)
        return when (a.chain) {
            Chain.ETH -> ethereum(a, asset, recipient, units, maximum, p)
            Chain.TRON -> tronData(a, asset, recipient, units, maximum, p)
            Chain.BTC -> bitcoin(a, p)
            Chain.ADA -> cardano(a, p)
        }
    }
    private suspend fun ethereum(a: Account, asset: Asset, to: String, amount: BigInteger, maximum: Boolean, p: Preferences): JSONObject {
        check(integerHex(rpc(p, "eth_chainId", JSONArray()).toString()) == BigInteger.ONE) { "RPC is not Ethereum mainnet" }
        val balance = integerHex(rpc(p, "eth_getBalance", JSONArray().put(a.address).put("pending")).toString())
        val nonce = integerHex(rpc(p, "eth_getTransactionCount", JSONArray().put(a.address).put("pending")).toString())
        val block = rpc(p, "eth_getBlockByNumber", JSONArray().put("latest").put(false)) as JSONObject
        val base = integerHex(block.getString("baseFeePerGas"))
        val priority = integerHex(rpc(p, "eth_maxPriorityFeePerGas", JSONArray()).toString()).max(BigInteger.ONE)
        val cap = base * BigInteger.valueOf(2) + priority
        val token = asset.contract != null
        val tokenBalance = if (token) integerHex(rpc(p, "eth_call", JSONArray().put(JSONObject().put("to", ETH_USDT).put("data", "0x70a08231" + a.address.removePrefix("0x").padStart(64, '0'))).put("pending")).toString()) else BigInteger.ZERO
        val sendAmount = if (maximum) if (token) tokenBalance else BigInteger.ONE else amount
        val call = JSONObject().put("from", a.address).put("to", if (token) ETH_USDT else to)
            .put("value", if (token) "0x0" else "0x" + sendAmount.toString(16))
        if (token) call.put("data", "0xa9059cbb" + to.removePrefix("0x").lowercase().padStart(64, '0') + sendAmount.toString(16).padStart(64, '0'))
        var gas = integerHex(rpc(p, "eth_estimateGas", JSONArray().put(call).put("pending")).toString())
        gas = (gas * BigInteger.valueOf(120) + BigInteger.valueOf(99)) / BigInteger.valueOf(100)
        if (maximum && !token) {
            val maxAmount = balance - gas * cap; require(maxAmount.signum() > 0) { "Insufficient ETH for network fee" }
            call.put("value", "0x" + maxAmount.toString(16))
            val actual = integerHex(rpc(p, "eth_estimateGas", JSONArray().put(call).put("pending")).toString())
            check(actual <= gas) { "Recipient gas depends on amount; enter a specific amount" }
        }
        return JSONObject().put("balance", balance.toString()).put("tokenBalance", tokenBalance.toString()).put("nonce", nonce.toString())
            .put("gasLimit", gas.toString()).put("maxFeePerGas", cap.toString()).put("maxPriorityFeePerGas", priority.toString())
    }
    private suspend fun tronData(a: Account, asset: Asset, to: String, amount: BigInteger, maximum: Boolean, p: Preferences): JSONObject {
        val owner = JSONObject().put("address", a.address).put("visible", true)
        val account = tron(p, "getaccount", owner)
        val resources = tron(p, "getaccountresource", owner)
        val block = tron(p, "getnowblock", JSONObject())
        val header = block.getJSONObject("block_header").getJSONObject("raw_data")
        val params = tron(p, "getchainparameters", JSONObject()).getJSONArray("chainParameter")
        val prices = (0 until params.length()).associate { params.getJSONObject(it).getString("key") to BigInteger(params.getJSONObject(it).optString("value", "0")) }
        val byteFee = prices["getTransactionFee"] ?: error("TRON bandwidth fee unavailable")
        val token = asset.contract != null
        var tokenBalance = BigInteger.ZERO; var energyCost = BigInteger.ZERO
        if (token) {
            val addressHex = base58Decode(a.address).hex().drop(2).padStart(64, '0')
            fun contract(selector: String, parameter: String) = JSONObject().put("owner_address", a.address).put("contract_address", TRON_USDT)
                .put("function_selector", selector).put("parameter", parameter).put("visible", true)
            val result = tron(p, "triggerconstantcontract", contract("balanceOf(address)", addressHex))
            check(result.getJSONObject("result").getBoolean("result")) { "TRON token balance query failed" }
            tokenBalance = BigInteger(result.getJSONArray("constant_result").getString(0), 16)
            val sendAmount = if (maximum) tokenBalance else amount
            val parameter = base58Decode(to).hex().drop(2).padStart(64, '0') + sendAmount.toString(16).padStart(64, '0')
            val simulation = tron(p, "triggerconstantcontract", contract("transfer(address,uint256)", parameter))
            check(simulation.getJSONObject("result").getBoolean("result")) { "TRON transfer simulation failed" }
            val simulatedResult = simulation.optJSONArray("constant_result")?.optString(0)
            check(simulatedResult != null && BigInteger(simulatedResult, 16).signum() != 0) { "USDT contract rejected transfer simulation" }
            val estimate = BigInteger(simulation.get("energy_used").toString())
            val price = prices["getEnergyFee"] ?: error("TRON energy fee unavailable")
            // Reserve full simulated Energy plus 20%; available staked Energy can reduce actual cost.
            energyCost = ((estimate * BigInteger.valueOf(120) + BigInteger.valueOf(99)) / BigInteger.valueOf(100)) * price
        }
        val freeBandwidth = (resources.optLong("freeNetLimit") - resources.optLong("freeNetUsed")).coerceAtLeast(0)
        val stakedBandwidth = (resources.optLong("NetLimit") - resources.optLong("NetUsed")).coerceAtLeast(0)
        val bandwidth = maxOf(freeBandwidth, stakedBandwidth)
        val estimatedBytes = if (token) 400L else 300L
        var fee = energyCost + if (bandwidth >= estimatedBytes) BigInteger.ZERO else BigInteger.valueOf(estimatedBytes) * byteFee
        if (!token) {
            val recipient = tron(p, "getaccount", JSONObject().put("address", to).put("visible", true))
            if (!recipient.has("address")) {
                val creation = prices["getCreateNewAccountFeeInSystemContract"] ?: error("TRON account creation fee unavailable")
                val bandwidthRate = prices["getCreateNewAccountBandwidthRate"] ?: error("TRON account creation bandwidth rate unavailable")
                fee = creation + if (BigInteger.valueOf(stakedBandwidth) >= BigInteger.valueOf(estimatedBytes) * bandwidthRate) BigInteger.ZERO else prices["getCreateAccountFee"] ?: error("TRON activation bandwidth fee unavailable")
            }
        }
        return JSONObject().put("balance", account.optString("balance", "0")).put("tokenBalance", tokenBalance.toString()).put("feeBudget", fee.toString())
            .put("timestamp", header.getLong("timestamp")).put("blockNumber", header.getLong("number")).put("blockId", block.getString("blockID"))
    }
    private suspend fun bitcoin(a: Account, p: Preferences): JSONObject = coroutineScope {
        val base = "https://mempool.space/api"
        val rate = JSONObject(http.get("$base/v1/fees/recommended")).getInt("halfHourFee").coerceAtLeast(1)
        val found = JSONArray(); var changeIndex = 0
        for (branch in 0..1) {
            var empty = 0; var exhausted = true
            for (index in 0 until a.discoveryMax(p)) {
                val address = AddressCodec.btc(a.publicKey, branch, index)
                val info = JSONObject(http.get("$base/address/$address"))
                check(info.getString("address") == address)
                val used = info.getJSONObject("chain_stats").getLong("tx_count") + info.getJSONObject("mempool_stats").getLong("tx_count") > 0
                if (branch == 1 && !used && empty == 0) changeIndex = index
                if (used) {
                    empty = 0
                    val utxos = JSONArray(http.get("$base/address/$address/utxo"))
                    for (i in 0 until utxos.length()) {
                        val u = utxos.getJSONObject(i)
                        if (!u.getJSONObject("status").getBoolean("confirmed")) continue
                        val txid = u.getString("txid"); require(txid.matches(Regex("[0-9a-f]{64}")))
                        val raw = http.get("$base/tx/$txid/hex").trim(); require(raw.matches(Regex("[0-9a-f]+")))
                        found.put(JSONObject().put("txid", txid).put("vout", u.getInt("vout")).put("value", u.get("value").toString()).put("rawTx", raw).put("branch", branch).put("index", index))
                    }
                } else empty++
                if (!continueReceiveDiscovery(empty, index + 1, branch, a, p)) { exhausted = false; break }
            }
            check(!exhausted) { "Bitcoin address scan reached its limit; increase the scan limit before sending" }
        }
        check(found.length() in 1..200) { "No confirmed Bitcoin UTXO, or too many inputs for one transaction" }
        val balance = (0 until found.length()).fold(BigInteger.ZERO) { total, i -> total + BigInteger(found.getJSONObject(i).getString("value")) }
        JSONObject().put("utxos", found).put("balance", balance.toString()).put("feeRate", rate.toString()).put("changeIndex", changeIndex)
    }
    private suspend fun cardano(a: Account, p: Preferences): JSONObject = withContext(Dispatchers.Default) {
        val owned = mutableMapOf<String, Pair<Int, Int>>()
        for (branch in 0..1) for (index in 0 until a.discoveryMax(p)) owned[AddressCodec.cardano(a.publicKey, branch, index)] = branch to index
        val stake = AddressCodec.stake(a.publicKey); val rows = JSONArray(); val addresses = mutableSetOf(a.address)
        for (index in 0..a.receiveIndex) addresses += a.receivingAddress(index).address
        val params: JSONObject; val slot: Long
        if (p.blockfrostKey.isNotBlank()) {
            params = JSONObject(http.get("$bf/epochs/latest/parameters", bfHeaders(p)))
            slot = JSONObject(http.get("$bf/blocks/latest", bfHeaders(p))).getLong("slot")
            var page = 1
            try { do {
                val found = JSONArray(http.get("$bf/accounts/$stake/addresses?count=100&page=${page++}", bfHeaders(p)))
                for (i in 0 until found.length()) addresses += found.getJSONObject(i).getString("address")
                check(page <= 100)
            } while (found.length() == 100) } catch (e: ApiException) { if (e.status != 404) throw e }
            check(addresses.all { it in owned }) { "Cardano address scan limit does not cover the account; increase it before sending" }
            for (address in addresses) {
                var utxoPage = 1
                try { do {
                    val found = JSONArray(http.get("$bf/addresses/$address/utxos?count=100&page=${utxoPage++}", bfHeaders(p)))
                    for (i in 0 until found.length()) {
                        val row = found.getJSONObject(i); val amount = row.getJSONArray("amount"); val tokens = JSONObject(); var coin = "0"
                        for (j in 0 until amount.length()) { val item = amount.getJSONObject(j); if (item.getString("unit") == "lovelace") coin = item.getString("quantity") else tokens.put(item.getString("unit"), item.getString("quantity")) }
                        rows.put(JSONObject().put("txid", row.getString("tx_hash")).put("vout", row.getInt("output_index")).put("address", address).put("value", coin).put("tokens", tokens))
                    }
                    check(utxoPage <= 100)
                } while (found.length() == 100) } catch (e: ApiException) { if (e.status != 404) throw e }
            }
        } else {
            params = JSONArray(http.get("$koios/epoch_params?limit=1&order=epoch_no.desc")).getJSONObject(0)
            slot = JSONArray(http.get("$koios/tip")).getJSONObject(0).getLong("abs_slot")
            val found = JSONArray(http.post("$koios/account_addresses", JSONObject().put("_stake_addresses", JSONArray().put(stake)).toString()))
            for (i in 0 until found.length()) { val values = found.getJSONObject(i).getJSONArray("addresses"); for (j in 0 until values.length()) addresses += values.getString(j) }
            check(addresses.all { it in owned }) { "Cardano address scan limit does not cover the account; increase it before sending" }
            for (batch in addresses.chunked(40)) {
                var offset = 0
                do {
                    val items = JSONArray(http.post("$koios/address_utxos?limit=1000&offset=$offset", JSONObject().put("_addresses", JSONArray(batch)).put("_extended", true).toString()))
                    for (i in 0 until items.length()) {
                        val row = items.getJSONObject(i); check(row.getString("address") in batch)
                        val tokens = JSONObject(); val assets = row.optJSONArray("asset_list") ?: JSONArray()
                        for (j in 0 until assets.length()) { val item = assets.getJSONObject(j); tokens.put(item.getString("policy_id") + item.getString("asset_name"), item.getString("quantity")) }
                        rows.put(JSONObject().put("txid", row.getString("tx_hash")).put("vout", row.getInt("tx_index")).put("address", row.getString("address")).put("value", row.get("value").toString()).put("tokens", tokens))
                    }
                    offset += items.length(); check(offset <= 100_000)
                } while (items.length() == 1000)
            }
        }
        check(rows.length() in 1..200) { "No spendable Cardano UTXO, or too many inputs for one transaction" }
        var balance = BigInteger.ZERO; var night = BigInteger.ZERO
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); val path = owned.getValue(row.getString("address")); row.put("branch", path.first).put("index", path.second)
            require(row.getString("txid").matches(Regex("[0-9a-f]{64}")))
            balance += BigInteger(row.getString("value")); night += BigInteger(row.getJSONObject("tokens").optString(NIGHT_UNIT, "0"))
        }
        JSONObject().put("utxos", rows).put("params", params).put("slot", slot).put("balance", balance.toString()).put("tokenBalance", night.toString())
    }
    suspend fun broadcast(chain: Chain, raw: String, expectedId: String, p: Preferences) {
        val response = when (chain) {
            Chain.BTC -> http.submit("https://mempool.space/api/tx", raw.toByteArray(), "text/plain").trim()
            Chain.ETH -> {
                val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "eth_sendRawTransaction").put("params", JSONArray().put(raw))
                val result = JSONObject(http.submit(ethUrl(p), body.toString().toByteArray(), "application/json"))
                check(!result.has("error")) { "Ethereum submission rejected: ${result.optJSONObject("error")?.optString("message")}" }
                result.getString("result")
            }
            Chain.TRON -> {
                val result = JSONObject(http.submit("https://api.trongrid.io/wallet/broadcasttransaction", raw.toByteArray(), "application/json", tronHeaders(p)))
                check(result.optBoolean("result")) { "TRON submission rejected: ${result.optString("code")}" }
                result.optString("txid", expectedId)
            }
            Chain.ADA -> if (p.blockfrostKey.isBlank()) http.submit("$koios/submittx", raw.unhex(), "application/cbor").trim().trim('"')
                else http.submit("$bf/tx/submit", raw.unhex(), "application/cbor", bfHeaders(p)).trim().trim('"')
        }
        check(response.lowercase() == expectedId.lowercase()) { "Node returned a different transaction ID; query status before retrying" }
    }
    suspend fun status(record: TransferRecord, p: Preferences): TransferStatus = when (record.chain) {
        Chain.BTC -> {
            try { if (JSONObject(http.get("https://mempool.space/api/tx/${record.txid}/status")).getBoolean("confirmed")) TransferStatus.CONFIRMED else TransferStatus.PENDING }
            catch (e: ApiException) { if (e.status == 404) record.status else throw e }
        }
        Chain.ETH -> {
            val result = rpc(p, "eth_getTransactionReceipt", JSONArray().put(record.txid))
            if (result == JSONObject.NULL) record.status else (result as JSONObject).let { check(it.getString("transactionHash").equals(record.txid, true)); if (integerHex(it.getString("status")) == BigInteger.ONE) TransferStatus.CONFIRMED else TransferStatus.FAILED }
        }
        Chain.TRON -> {
            val result = tron(p, "gettransactioninfobyid", JSONObject().put("value", record.txid))
            if (!result.has("blockNumber")) record.status else { check(result.getString("id").equals(record.txid, true)); if (result.optString("result") == "FAILED" || result.optJSONObject("receipt")?.optString("result", "SUCCESS")?.let { it != "SUCCESS" } == true) TransferStatus.FAILED else TransferStatus.CONFIRMED }
        }
        Chain.ADA -> if (p.blockfrostKey.isNotBlank()) {
            try { val result = JSONObject(http.get("$bf/txs/${record.txid}", bfHeaders(p))); check(result.getString("hash") == record.txid); TransferStatus.CONFIRMED }
            catch (e: ApiException) { if (e.status == 404) record.status else throw e }
        } else {
            val result = JSONArray(http.post("$koios/tx_status", JSONObject().put("_tx_hashes", JSONArray().put(record.txid)).toString()))
            if (result.length() == 0) record.status else { val row = result.getJSONObject(0); check(row.getString("tx_hash") == record.txid); if (row.optInt("num_confirmations") > 0) TransferStatus.CONFIRMED else record.status }
        }
    }
}
