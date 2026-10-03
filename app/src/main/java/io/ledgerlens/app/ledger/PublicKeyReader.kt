package io.ledgerlens.app.ledger

import io.ledgerlens.app.crypto.*
import io.ledgerlens.app.model.*
import java.nio.ByteBuffer
import java.util.UUID

object PublicKeyReader {
    fun encodePath(path: String): ByteArray {
        val parts = path.removePrefix("m/").split('/'); require(parts.size in 1..10)
        return byteArrayOf(parts.size.toByte()) + parts.flatMap {
            val hardened = it.endsWith("'"); val value = it.removeSuffix("'").toLong()
            require(value in 0..0x7fffffff)
            ByteBuffer.allocate(4).putInt((value or if (hardened) 0x80000000L else 0L).toInt()).array().toList()
        }.toByteArray()
    }
    fun command(cla: Int, ins: Int, p1: Int, p2: Int, data: ByteArray) = byteArrayOf(cla.toByte(), ins.toByte(), p1.toByte(), p2.toByte(), data.size.toByte()) + data
    fun unwrap(response: ByteArray): ByteArray {
        require(response.size >= 2) { "Ledger returned a short response" }
        val status = ((response[response.size - 2].toInt() and 255) shl 8) or (response.last().toInt() and 255)
        check(status == 0x9000) { "Ledger status %04X. Unlock device, open the selected chain app and approve the request.".format(status) }
        return response.dropLast(2).toByteArray()
    }
    suspend fun read(transport: LedgerTransport, chain: Chain, index: Int, name: String): Account {
        require(index in 0..1000)
        val path = chain.path(index); val encoded = encodePath(path)
        val publicKey: String; val address: String
        when (chain) {
            Chain.BTC -> {
                val payload = unwrap(transport.exchange(command(0xe1, 0x00, 0, 1, byteArrayOf(1) + encoded)))
                publicKey = payload.toString(Charsets.US_ASCII)
                address = AddressCodec.btc(publicKey, 0, 0)
            }
            Chain.ETH, Chain.TRON -> {
                val payload = unwrap(transport.exchange(command(0xe0, 0x02, 1, 0, encoded)))
                require(payload.isNotEmpty()); val keyLen = payload[0].toInt() and 255
                require(keyLen in setOf(33, 65) && payload.size > keyLen + 1)
                val key = payload.copyOfRange(1, keyLen + 1)
                val addrLen = payload[keyLen + 1].toInt() and 255
                require(payload.size >= keyLen + 2 + addrLen)
                val deviceAddress = payload.copyOfRange(keyLen + 2, keyLen + 2 + addrLen).toString(Charsets.US_ASCII)
                val derived = if (chain == Chain.ETH) AddressCodec.ethereum(key) else AddressCodec.tron(key)
                val normalized = if (chain == Chain.ETH) "0x" + deviceAddress.removePrefix("0x").lowercase() else deviceAddress
                require(derived == normalized) { "Public key and device address do not match" }
                publicKey = key.hex(); address = derived
            }
            Chain.ADA -> {
                val version = unwrap(transport.exchange(command(0xd7, 0, 0, 0, byteArrayOf())))
                require(version.size == 4 && (version[0].toInt() and 255) in 7..8) { "Cardano app version unsupported; supported majors: 7 and 8" }
                val key = unwrap(transport.exchange(command(0xd7, 0x10, 0, 0, encoded)))
                require(key.size == 64)
                publicKey = key.hex(); address = AddressCodec.cardano(publicKey, 0, 0)
                val addressParams = byteArrayOf(0, 1) + encodePath("$path/0/0") + byteArrayOf(0x22) + encodePath("$path/2/0")
                val deviceAddress = unwrap(transport.exchange(command(0xd7, 0x11, 1, 0, addressParams)))
                require(bech32("addr", deviceAddress) == address) { "Cardano address derivation does not match Ledger" }
                unwrap(transport.exchange(command(0xd7, 0x11, 2, 0, addressParams)))
            }
        }
        return Account(UUID.randomUUID().toString(), name.ifBlank { "${chain.title} ${index + 1}" }, chain, path, publicKey, address)
    }
}
