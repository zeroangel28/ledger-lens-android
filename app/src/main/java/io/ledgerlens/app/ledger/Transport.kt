package io.ledgerlens.app.ledger

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream

interface LedgerTransport {
    suspend fun exchange(apdu: ByteArray): ByteArray
    suspend fun exchangeAuthorized(apdu: ByteArray, permit: LedgerPermit): ByteArray { permit.validate(apdu); return exchange(apdu) }
    fun close()
}

/** A short-lived capability minted by the app after reviewing one draft (or a public address). */
class LedgerPermit private constructor(private val chain: io.ledgerlens.app.model.Chain, private val signing: Boolean) {
    private var active = true
    private var remaining = 10_000
    @Synchronized fun revoke() { active = false }
    @Synchronized fun validate(apdu: ByteArray) {
        require(active && remaining-- > 0) { "Ledger operation authorization expired" }
        require(apdu.size >= 5 && apdu.size == 5 + (apdu[4].toInt() and 255)) { "Malformed APDU" }
        val cla = apdu[0].toInt() and 255; val ins = apdu[1].toInt() and 255
        val allowed = when (chain) {
            io.ledgerlens.app.model.Chain.BTC -> (cla == 0xe1 && ins in (if (signing) setOf(0, 3, 4, 5) else setOf(0, 3, 5))) || (cla == 0xf8 && ins == 1)
            io.ledgerlens.app.model.Chain.ETH -> cla == 0xe0 && ins in (if (signing) setOf(2, 4, 6, 0x0a) else setOf(2, 6))
            io.ledgerlens.app.model.Chain.TRON -> cla == 0xe0 && ins in (if (signing) setOf(2, 4, 6) else setOf(2, 6))
            io.ledgerlens.app.model.Chain.ADA -> cla == 0xd7 && ins in (if (signing) setOf(0, 0x10, 0x11, 0x21) else setOf(0, 0x10, 0x11))
        }
        require(allowed) { "Command is outside the approved Ledger operation" }
    }
    companion object {
        fun address(chain: io.ledgerlens.app.model.Chain) = LedgerPermit(chain, false)
        fun reviewedTransaction(chain: io.ledgerlens.app.model.Chain) = LedgerPermit(chain, true)
    }
}

/** Only public-data commands can cross this boundary. No signing APDU is exposed. */
object ReadOnlyPolicy {
    fun validate(apdu: ByteArray) {
        require(apdu.size >= 5 && apdu.size == 5 + (apdu[4].toInt() and 255)) { "Malformed APDU" }
        val cla = apdu[0].toInt() and 255; val ins = apdu[1].toInt() and 255
        require((cla == 0xe1 && ins == 0x00) || (cla == 0xe0 && ins == 0x02) || (cla == 0xd7 && ins in setOf(0x00, 0x10, 0x11))) { "Read-only command rejected" }
    }
}
class SerializedTransport(private val delegate: LedgerTransport): LedgerTransport {
    private val mutex = Mutex()
    override suspend fun exchange(apdu: ByteArray): ByteArray = mutex.withLock { ReadOnlyPolicy.validate(apdu); delegate.exchange(apdu) }
    override suspend fun exchangeAuthorized(apdu: ByteArray, permit: LedgerPermit): ByteArray = mutex.withLock { permit.validate(apdu); delegate.exchangeAuthorized(apdu, permit) }
    override fun close() = delegate.close()
}

object LedgerFrames {
    fun encode(apdu: ByteArray, packetSize: Int, usb: Boolean): List<ByteArray> {
        require(apdu.size <= 65535 && packetSize >= 8)
        val result = mutableListOf<ByteArray>(); var offset = 0; var sequence = 0
        while (offset < apdu.size) {
            val header = if (usb) byteArrayOf(0x01, 0x01, 0x05, (sequence shr 8).toByte(), sequence.toByte()) else byteArrayOf(0x05, (sequence shr 8).toByte(), sequence.toByte())
            val prefix = header + if (sequence == 0) byteArrayOf((apdu.size shr 8).toByte(), apdu.size.toByte()) else byteArrayOf()
            val n = minOf(packetSize - prefix.size, apdu.size - offset)
            val frame = prefix + apdu.copyOfRange(offset, offset + n)
            result += if (usb) frame.copyOf(packetSize) else frame
            offset += n; sequence++
        }
        return result
    }
    class Decoder(private val usb: Boolean) {
        private var sequence = 0; private var expected = -1; private val buffer = ByteArrayOutputStream()
        fun accept(packet: ByteArray): ByteArray? {
            val start = if (usb) 2 else 0
            require(packet.size >= start + 3)
            if (usb) require(packet[0] == 1.toByte() && packet[1] == 1.toByte()) { "Unexpected HID channel" }
            require(packet[start] == 5.toByte()) { "Unexpected Ledger frame" }
            require(((packet[start + 1].toInt() and 255) shl 8 or (packet[start + 2].toInt() and 255)) == sequence) { "Out-of-order Ledger frame" }
            var offset = start + 3
            if (sequence == 0) { require(packet.size >= offset + 2); expected = ((packet[offset].toInt() and 255) shl 8) or (packet[offset + 1].toInt() and 255); require(expected in 2..65535); offset += 2 }
            if (!usb) require(packet.size - offset <= expected - buffer.size()) { "Oversized Ledger response" }
            buffer.write(packet, offset, minOf(packet.size - offset, expected - buffer.size())); sequence++
            return if (buffer.size() == expected) buffer.toByteArray() else null
        }
    }
}
