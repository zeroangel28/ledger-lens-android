package io.ledgerlens.app.crypto

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.digests.KeccakDigest
import org.bouncycastle.crypto.digests.RIPEMD160Digest
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
fun String.unhex(): ByteArray { require(length % 2 == 0); return chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
private fun hash160(b: ByteArray): ByteArray { val d = RIPEMD160Digest(); val h = sha(b); d.update(h, 0, h.size); return ByteArray(20).also { d.doFinal(it, 0) } }
private val b58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
fun base58Check(payload: ByteArray): String {
    val bytes = payload + sha(sha(payload)).take(4).toByteArray()
    var n = BigInteger(1, bytes); val out = StringBuilder()
    while (n.signum() > 0) { val d = n.divideAndRemainder(BigInteger.valueOf(58)); out.append(b58[d[1].toInt()]); n = d[0] }
    return "1".repeat(bytes.takeWhile { it == 0.toByte() }.size) + out.reverse()
}
fun base58Decode(text: String): ByteArray {
    require(text.isNotEmpty()); var n = BigInteger.ZERO
    for (c in text) { val i = b58.indexOf(c); require(i >= 0); n = n * BigInteger.valueOf(58) + BigInteger.valueOf(i.toLong()) }
    var raw = n.toByteArray(); if (raw.size > 1 && raw[0] == 0.toByte()) raw = raw.drop(1).toByteArray()
    val bytes = ByteArray(text.takeWhile { it == '1' }.length) + raw
    require(bytes.size >= 4 && bytes.takeLast(4).toByteArray().contentEquals(sha(sha(bytes.dropLast(4).toByteArray())).take(4).toByteArray())) { "Invalid xpub checksum" }
    return bytes.dropLast(4).toByteArray()
}
private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA512").run { init(SecretKeySpec(key, "HmacSHA512")); doFinal(data) }
private val alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
private fun convertBits(bytes: ByteArray): List<Int> {
    var acc = 0; var bits = 0; val out = mutableListOf<Int>()
    for (b in bytes) { acc = ((acc shl 8) or (b.toInt() and 255)) and 0xffff; bits += 8; while (bits >= 5) { bits -= 5; out += (acc shr bits) and 31 } }
    if (bits > 0) out += (acc shl (5 - bits)) and 31
    return out
}
fun bech32(hrp: String, bytes: ByteArray, witness: Boolean = false): String {
    val data = (if (witness) listOf(0) else emptyList()) + convertBits(bytes)
    val expanded = hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }
    var chk = 1; val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    for (v in expanded + data + List(6) { 0 }) { val top = chk ushr 25; chk = ((chk and 0x1ffffff) shl 5) xor v; for (i in 0..4) if (((top ushr i) and 1) != 0) chk = chk xor generators[i] }
    chk = chk xor 1
    return hrp + "1" + (data + (0..5).map { (chk ushr (5 * (5 - it))) and 31 }).map { alphabet[it] }.joinToString("")
}
object AddressCodec {
    private val curve = SECNamedCurves.getByName("secp256k1")
    fun ethereum(pub: ByteArray): String {
        val raw = curve.curve.decodePoint(pub).normalize().getEncoded(false).drop(1).toByteArray()
        val d = KeccakDigest(256); d.update(raw, 0, raw.size)
        return "0x" + ByteArray(32).also { d.doFinal(it, 0) }.takeLast(20).toByteArray().hex()
    }
    fun tron(pub: ByteArray) = base58Check(byteArrayOf(0x41) + ethereum(pub).removePrefix("0x").unhex())
    fun btc(xpub: String, branch: Int, index: Int): String {
        var raw = base58Decode(xpub); require(raw.size == 78)
        var chain = raw.copyOfRange(13, 45); var pub = raw.copyOfRange(45, 78)
        for (i in listOf(branch, index)) {
            require(i >= 0); val h = hmac(chain, pub + ByteBuffer.allocate(4).putInt(i).array())
            val scalar = BigInteger(1, h.copyOfRange(0, 32)); require(scalar.signum() > 0 && scalar < curve.n)
            val point = curve.g.multiply(scalar).add(curve.curve.decodePoint(pub)).normalize(); require(!point.isInfinity)
            pub = point.getEncoded(true); chain = h.copyOfRange(32, 64)
        }
        return bech32("bc", hash160(pub), true)
    }
    fun cardano(accountXpub: String, branch: Int, index: Int): String {
        val account = accountXpub.unhex(); require(account.size == 64)
        val payment = edChild(edChild(account, branch), index)
        val stake = edChild(edChild(account, 2), 0)
        return bech32("addr", byteArrayOf(1) + blake224(payment.take(32).toByteArray()) + blake224(stake.take(32).toByteArray()))
    }
    fun stake(accountXpub: String) = bech32("stake", byteArrayOf(0xe1.toByte()) + blake224(edChild(edChild(accountXpub.unhex(), 2), 0).take(32).toByteArray()))
    private fun blake224(b: ByteArray): ByteArray { val d = Blake2bDigest(224); d.update(b, 0, b.size); return ByteArray(28).also { d.doFinal(it, 0) } }
    fun edChild(parent: ByteArray, i: Int): ByteArray {
        require(i >= 0); val pub = parent.copyOfRange(0, 32); val chain = parent.copyOfRange(32, 64); val index = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(i).array()
        val z = hmac(chain, byteArrayOf(2) + pub + index)
        val nextChain = hmac(chain, byteArrayOf(3) + pub + index).copyOfRange(32, 64)
        val scalar = BigInteger(1, z.copyOfRange(0, 28).reversedArray()) * BigInteger.valueOf(8)
        return EdPoint.decode(pub).add(EdPoint.base.multiply(scalar)).encode() + nextChain
    }
    private data class EdPoint(val x: BigInteger, val y: BigInteger) {
        fun add(q: EdPoint): EdPoint {
            val xy = (d * x * q.x * y * q.y).mod(p)
            return EdPoint(((x * q.y + y * q.x) * (BigInteger.ONE + xy).mod(p).modInverse(p)).mod(p), ((y * q.y + x * q.x) * (BigInteger.ONE - xy).mod(p).modInverse(p)).mod(p))
        }
        fun multiply(n: BigInteger): EdPoint {
            var a = Extended(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
            var b = Extended(x, y, BigInteger.ONE, x * y % p); var k = n
            while (k.signum() > 0) { if (k.testBit(0)) a = a.add(b); b = b.add(b); k = k.shiftRight(1) }
            val inverse = a.z.modInverse(p); return EdPoint(a.x * inverse % p, a.y * inverse % p)
        }
        private data class Extended(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger) {
            fun add(q: Extended): Extended {
                val a = ((y - x) * (q.y - q.x)).mod(p); val b = ((y + x) * (q.y + q.x)).mod(p)
                val c = (t * BigInteger.valueOf(2) * d * q.t).mod(p); val dd = (z * BigInteger.valueOf(2) * q.z).mod(p)
                val e = (b - a).mod(p); val f = (dd - c).mod(p); val g = (dd + c).mod(p); val h = (b + a).mod(p)
                return Extended(e * f % p, g * h % p, f * g % p, e * h % p)
            }
        }
        fun encode(): ByteArray { val raw = y.toByteArray().reversedArray().copyOf(32); if (x.testBit(0)) raw[31] = (raw[31].toInt() or 128).toByte(); return raw }
        companion object {
            val p = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
            val d = (BigInteger.valueOf(-121665) * BigInteger.valueOf(121666).modInverse(p)).mod(p)
            val identity = EdPoint(BigInteger.ZERO, BigInteger.ONE)
            val base = EdPoint(BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"), BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960"))
            fun decode(raw: ByteArray): EdPoint {
                require(raw.size == 32); val sign = (raw[31].toInt() and 128) != 0; val bytes = raw.copyOf(); bytes[31] = (bytes[31].toInt() and 127).toByte()
                val y = BigInteger(1, bytes.reversedArray()); require(y < p); val yy = y * y % p; val xx = ((yy - BigInteger.ONE) * (d * yy + BigInteger.ONE).mod(p).modInverse(p)).mod(p)
                var x = xx.modPow((p + BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), p)
                if (x * x % p != xx) x = x * BigInteger.valueOf(2).modPow((p - BigInteger.ONE).divide(BigInteger.valueOf(4)), p) % p
                require(x * x % p == xx); if (x.testBit(0) != sign) x = p - x
                return EdPoint(x, y)
            }
        }
    }
}
