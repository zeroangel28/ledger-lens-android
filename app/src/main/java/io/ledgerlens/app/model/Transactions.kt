package io.ledgerlens.app.model

import org.json.JSONObject
import java.math.BigDecimal

const val NIGHT_UNIT = "0691b2fecca1ac4f53cb6dfb00b7013e561d1f34403b957cbb5af1fa4e49474854"
const val ETH_USDT = "0xdac17f958d2ee523a2206206994597c13d831ec7"
const val TRON_USDT = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"
fun canTransfer(asset: Asset) = if (asset.contract == null) asset.decimals == asset.chain.decimals && asset.symbol == asset.chain.symbol else asset.decimals == 6 && when (asset.chain) {
    Chain.ETH -> asset.symbol == "USDT" && asset.contract.lowercase() == ETH_USDT
    Chain.TRON -> asset.symbol == "USDT" && asset.contract == TRON_USDT
    Chain.ADA -> asset.symbol == "NIGHT" && asset.contract.lowercase() == NIGHT_UNIT
    else -> false
}
fun accountJson(a: Account) = JSONObject().put("id", a.id).put("chain", a.chain.name).put("path", a.path).put("publicKey", a.publicKey).put("address", a.address)
data class TransferDraft(val assetId: String, val accountId: String, val chain: Chain, val symbol: String, val recipient: String,
    val amount: BigDecimal, val fee: BigDecimal, val extraNative: BigDecimal, val preparedJson: String, val expiresAt: Long)
enum class TransferStatus { SUBMITTING, PENDING, UNKNOWN, CONFIRMED, FAILED }
data class TransferRecord(val txid: String, val accountId: String, val chain: Chain, val symbol: String, val recipient: String,
    val amount: String, val status: TransferStatus, val createdAt: Long = System.currentTimeMillis(), val balanceUpdateAttempted: Boolean = false, val error: String? = null)
data class TransferUi(val assetId: String? = null, val recipient: String = "", val amount: String = "", val maximum: Boolean = false,
    val draft: TransferDraft? = null, val working: Boolean = false, val stage: String? = null, val error: String? = null)

/** Address-only QR import; payment amounts and contract calls never execute from a QR. */
fun recipientFromQr(chain: Chain, raw: String): String {
    val text = raw.trim(); require(text.length in 1..600) { "QR address is invalid" }
    if (!text.contains(':')) return text
    val scheme = text.substringBefore(':').lowercase()
    val allowed = when (chain) { Chain.BTC -> "bitcoin"; Chain.ETH -> "ethereum"; Chain.TRON -> "tron"; Chain.ADA -> "cardano" }
    require(scheme == allowed) { "QR code belongs to a different network" }
    var address = text.substringAfter(':').substringBefore('?')
    if (chain == Chain.ETH && address.contains('@')) { require(address.substringAfter('@') == "1") { "Only Ethereum mainnet is supported" }; address = address.substringBefore('@') }
    require(!address.contains('/') && !address.contains('@') && !address.startsWith("pay-")) { "Contract payment QR is unsupported; paste the recipient address" }
    return address
}
