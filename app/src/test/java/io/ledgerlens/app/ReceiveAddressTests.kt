package io.ledgerlens.app

import io.ledgerlens.app.crypto.AddressCodec
import io.ledgerlens.app.data.*
import io.ledgerlens.app.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger

class ReceiveAddressTests {
    private val key = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
    private fun account(index: Int = 0) = Account("btc", "Fixture", Chain.BTC, Chain.BTC.path(0), key, AddressCodec.btc(key, 0, 0), 1, index)
    private class FakeHttp(val response: (String) -> String) : Http() {
        override suspend fun get(url: String, headers: Map<String, String>) = response(url)
    }
    private fun history(address: String, confirmed: Int = 0, pending: Int = 0, amount: Long = 0) =
        """{"address":"$address","chain_stats":{"tx_count":$confirmed,"funded_txo_sum":$amount,"spent_txo_sum":0},"mempool_stats":{"tx_count":$pending}}"""

    @Test fun nextAddressUsesPublishedVectorAndPreservesSigningIdentityAcrossUpgrade() {
        val a = account(1)
        assertEquals("bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g", a.receivingAddress().address)
        assertEquals("84'/0'/0'/0/1", a.receivingAddress().path)
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", a.address)
        val old = AccountSnapshot(listOf(nativePlaceholder(a)), lastAttemptAt = 123, highestUsedReceiveIndex = 4)
        val encoded = EncryptedStore.encode(PortfolioState(listOf(a), mapOf(a.id to old)))
        val decoded = EncryptedStore.decode(encoded)
        assertEquals(a, decoded.accounts.single()); assertEquals(4, decoded.snapshots.getValue(a.id).highestUsedReceiveIndex)
        encoded.getJSONArray("accounts").getJSONObject(0).remove("receiveIndex")
        encoded.getJSONObject("snapshots").getJSONObject(a.id).remove("highestUsedReceiveIndex")
        val migrated = EncryptedStore.decode(encoded)
        assertEquals(0, migrated.accounts.single().receiveIndex); assertEquals(-1, migrated.snapshots.getValue(a.id).highestUsedReceiveIndex)
    }
    @Test fun generationSkipsConfirmedAndPendingHistory() = runBlocking {
        val a = account(); val addresses = (0..3).map { a.receivingAddress(it).address }
        val http = FakeHttp { url ->
            val address = url.substringAfterLast('/')
            history(address, confirmed = if (address == addresses[1]) 1 else 0, pending = if (address == addresses[2]) 1 else 0)
        }
        val chosen = ChainBalances(http).nextBitcoinReceiveIndex(a, Preferences(), 1)
        assertEquals(BtcReceiveCandidate(3, 2), chosen)
    }
    @Test fun providerFailureCannotBeClassifiedAsUnused() = runBlocking {
        val http = FakeHttp { throw ApiException("fixture", 429) }
        try { ChainBalances(http).nextBitcoinReceiveIndex(account(), Preferences(), 1); fail("Expected network failure") }
        catch (e: ApiException) { assertEquals(429, e.status) }
    }
    @Test fun consecutiveUnusedGapIsBoundedForWalletRecovery() = runBlocking {
        val a = account(4); val http = FakeHttp { history(it.substringAfterLast('/')) }
        try { ChainBalances(http).nextBitcoinReceiveIndex(a, Preferences(scanGap = 5), 5); fail("Expected gap limit") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("gap reached")) }
        assertEquals(4, a.receiveIndex)
    }
    @Test fun holdingsIncludeIssuedAddressBeyondAnEarlierEmptyGap() = runBlocking {
        val a = account(7); val receiving = a.receivingAddress().address
        val http = FakeHttp { url ->
            val address = url.substringAfterLast('/')
            history(address, confirmed = if (address == receiving) 1 else 0, amount = if (address == receiving) 123456L else 0L)
        }
        val snapshot = ChainBalances(http).fetch(a, Preferences(scanGap = 5, scanMax = 20))
        assertEquals(BigDecimal("0.00123456"), snapshot.assets.single().quantity)
        assertEquals(7, snapshot.highestUsedReceiveIndex); assertFalse(snapshot.scanLimited)
        assertTrue(a.copy(receiveIndex = 50).discoveryMax(Preferences(scanMax = 20)) > 50)
    }
    @Test fun transferPreparationIncludesIssuedAddressAfterAnEmptyGap() = runBlocking {
        val a = account(7); val receiving = a.receivingAddress().address; val txid = "ab".repeat(32)
        val http = FakeHttp { url -> when {
            url.endsWith("/v1/fees/recommended") -> """{"halfHourFee":3}"""
            url.endsWith("/tx/$txid/hex") -> "00"
            url.endsWith("/address/$receiving/utxo") -> """[{"txid":"$txid","vout":0,"value":123456,"status":{"confirmed":true}}]"""
            else -> url.substringAfterLast('/').let { history(it, confirmed = if (it == receiving) 1 else 0) }
        } }
        val data = TransactionData(http).prepare(a, nativePlaceholder(a), a.address, BigInteger.ONE, false, Preferences(scanGap = 5, scanMax = 20))
        val utxos = data.getJSONArray("utxos")
        assertEquals(1, utxos.length()); assertEquals(7, utxos.getJSONObject(0).getInt("index"))
        assertEquals(0, utxos.getJSONObject(0).getInt("branch")); assertEquals("123456", data.getString("balance"))
    }
    @Test fun cardanoRotationRetainsStakeAndRejectsUnsupportedOrInvalidIndices() {
        val pub = "aaaca5e7adc69a03ef1f5c017ed02879e8ca871df028461ed9bf19fb8fa15038b40c44dfd9be08591b62be7f9991c85f812d8196927f3c824d9fcb17d275089e"
        val a = Account("ada", "Fixture", Chain.ADA, Chain.ADA.path(0), pub, AddressCodec.cardano(pub, 0, 0), 1, 3)
        assertNotEquals(a.address, a.receivingAddress().address)
        assertEquals("1852'/1815'/0'/0/3", a.receivingAddress().path)
        assertThrows(IllegalArgumentException::class.java) { a.receivingAddress(-1) }
        assertThrows(IllegalArgumentException::class.java) { a.receivingAddress(900) }
        val eth = a.copy(chain = Chain.ETH)
        assertFalse(eth.canRotateReceivingAddress)
        assertThrows(IllegalArgumentException::class.java) { eth.receivingAddress(1) }
    }
}
