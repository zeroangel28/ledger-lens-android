package io.ledgerlens.app.model

import io.ledgerlens.app.crypto.AddressCodec

// Leave room for the largest configured gap before the hard discovery limit.
const val MAX_RECEIVE_INDEX = 899
val Account.canRotateReceivingAddress get() = chain == Chain.BTC || chain == Chain.ADA
data class ReceivingAddress(val index: Int, val address: String, val path: String)
fun Account.receivingAddress(index: Int = receiveIndex): ReceivingAddress {
    require(index in 0..MAX_RECEIVE_INDEX)
    val value = when (chain) {
        Chain.BTC -> AddressCodec.btc(publicKey, 0, index)
        Chain.ADA -> AddressCodec.cardano(publicKey, 0, index)
        else -> { require(index == 0); address }
    }
    return ReceivingAddress(index, value, if (canRotateReceivingAddress) "$path/0/$index" else path)
}
fun Account.discoveryMax(p: Preferences) = maxOf(p.scanMax, receiveIndex + 1 + p.scanGap).coerceAtMost(1000)
fun continueReceiveDiscovery(gap: Int, nextIndex: Int, branch: Int, account: Account, p: Preferences) =
    gap < p.scanGap || branch == 0 && nextIndex <= account.receiveIndex
