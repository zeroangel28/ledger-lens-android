package io.ledgerlens.app

import io.ledgerlens.app.data.*
import io.ledgerlens.app.ledger.*
import io.ledgerlens.app.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal
import java.net.URLDecoder

class TransferAndCacheTests {
    private fun asset(chain: Chain) = nativePlaceholder(Account(chain.name, "Fixture", chain, chain.path(0), "public", "address"))
    private class FixtureHttp : Http() {
        val paths = mutableListOf<String>()
        var missing = false
        override suspend fun get(url: String, headers: Map<String, String>): String {
            paths += url
            if (url.contains("exchangeInfo")) {
                if (missing) throw ApiException("binance", 400, -1121)
                val pair = url.substringAfter("symbol=").substringBefore('&')
                return JSONObject().put("symbols", JSONArray().put(JSONObject().put("symbol", pair).put("baseAsset", pair.removeSuffix("USDT")).put("quoteAsset", "USDT").put("status", "TRADING").put("isSpotTradingAllowed", true))).toString()
            }
            fun row(pair: String) = JSONObject().put("symbol", pair).put("price", "12.345")
            return if (url.contains("symbols=")) JSONArray(JSONArray(URLDecoder.decode(url.substringAfter("symbols="), "UTF-8")).let { a -> (0 until a.length()).map { row(a.getString(it)) } }).toString()
                else row(url.substringAfter("symbol=")).toString()
        }
    }
    @Test fun positiveMarketCacheSurvivesRestartAndBatchesOnlyHeldPairs() = runBlocking {
        val http = FixtureHttp(); var now = 1_000L
        val assets = listOf(asset(Chain.BTC), asset(Chain.ETH))
        val first = BinancePrices(http, clock = { now }); assertNull(first.prices(assets).error)
        assertEquals(3, http.paths.size); assertTrue(http.paths.last().contains("symbols="))
        val persisted = EncryptedStore.decode(EncryptedStore.encode(PortfolioState(spotMarkets = first.cache)))
        val reopened = BinancePrices(http, persisted.spotMarkets, clock = { now })
        now += 30_000; reopened.prices(assets)
        assertEquals(2, http.paths.count { it.contains("exchangeInfo") })
        now = 86_401_000; reopened.prices(assets)
        assertEquals(4, http.paths.count { it.contains("exchangeInfo") })
    }
    @Test fun missingMarketHasDailyNegativeCacheButNetworkFailureDoesNotBecomeMissing() = runBlocking {
        val http = FixtureHttp().apply { missing = true }; var now = 10L
        val prices = BinancePrices(http, clock = { now }); val assets = listOf(asset(Chain.ADA))
        assertEquals(QuoteState.MISSING, prices.prices(assets).quotes.values.single().state)
        now += 30_000; prices.prices(assets); assertEquals(1, http.paths.size)
        now += 86_400_000; prices.prices(assets); assertEquals(2, http.paths.size)
        val unavailable = BinancePrices(object : Http() { override suspend fun get(url: String, headers: Map<String, String>): String = throw ApiException("binance", 429) })
        val failed = unavailable.prices(assets); assertNotNull(failed.error); assertTrue(failed.quotes.isEmpty()); assertTrue(unavailable.cache.isEmpty())
    }
    @Test fun clockRollbackInvalidatesMarketCache() = runBlocking {
        val http = FixtureHttp(); val prices = BinancePrices(http, mapOf("BTCUSDT" to SpotMarket(true, 1000)), clock = { 999 })
        prices.prices(listOf(asset(Chain.BTC))); assertTrue(http.paths.first().contains("exchangeInfo"))
    }
    @Test fun signingCapabilityIsChainBoundAndRevokedAndCannotRequestBlindHashOrPrivateData() {
        fun apdu(cla: Int, ins: Int) = byteArrayOf(cla.toByte(), ins.toByte(), 0, 0, 0)
        val public = LedgerPermit.address(Chain.ETH)
        public.validate(apdu(0xe0, 2)); assertThrows(IllegalArgumentException::class.java) { public.validate(apdu(0xe0, 4)) }
        val signing = LedgerPermit.reviewedTransaction(Chain.TRON)
        signing.validate(apdu(0xe0, 4)); assertThrows(IllegalArgumentException::class.java) { signing.validate(apdu(0xe0, 5)) }
        assertThrows(IllegalArgumentException::class.java) { signing.validate(apdu(0xe0, 0x0a)) }
        assertThrows(IllegalArgumentException::class.java) { signing.validate(apdu(0xd7, 0x21)) }
        signing.revoke(); assertThrows(IllegalArgumentException::class.java) { signing.validate(apdu(0xe0, 4)) }
    }
    @Test fun submissionTimeoutIsOneAttemptAndNeverAutomaticallyRetried() = runBlocking {
        var attempts = 0
        val client = OkHttpClient.Builder().addInterceptor { attempts++; throw IOException("fixture transport timeout") }.build()
        try { Http(client).submit("https://fixture.invalid/submit", byteArrayOf(1), "application/cbor"); fail("Expected timeout") } catch (_: IOException) { }
        assertEquals(1, attempts)
    }
    @Test fun confirmedReceiptMustMatchStoredTransactionIdAndTransferStateSurvivesRestart() = runBlocking {
        val record = TransferRecord("0x" + "11".repeat(32), "account", Chain.ETH, "ETH", "recipient", "0.01", TransferStatus.UNKNOWN, balanceUpdateAttempted = true)
        val restored = EncryptedStore.decode(EncryptedStore.encode(PortfolioState(transfers = listOf(record))))
        assertEquals(record, restored.transfers.single())
        val bad = TransactionData(object : Http() { override suspend fun post(url: String, body: String, headers: Map<String, String>) = """{"result":{"status":"0x1","transactionHash":"0xwrong"}}""" })
        try { bad.status(record, Preferences()); fail("Mismatched receipt") } catch (_: IllegalStateException) { }
    }
    @Test fun onlyCanonicalTransferAssetsAndDecimalsAreEnabled() {
        assertTrue(canTransfer(asset(Chain.ETH)))
        val usdt = asset(Chain.ETH).copy(symbol = "USDT", decimals = 6, contract = ETH_USDT)
        assertTrue(canTransfer(usdt)); assertFalse(canTransfer(usdt.copy(decimals = 18)))
        assertFalse(canTransfer(usdt.copy(contract = "0xspoof"))); assertFalse(canTransfer(usdt.copy(symbol = "NIGHT")))
    }
    @Test fun qrImportNeverExecutesContractsOrOverridesAmountAndRejectsWrongNetwork() {
        assertEquals("bc1qfixture", recipientFromQr(Chain.BTC, "bitcoin:bc1qfixture?amount=999"))
        assertEquals("0xfixture", recipientFromQr(Chain.ETH, "ethereum:0xfixture@1"))
        assertThrows(IllegalArgumentException::class.java) { recipientFromQr(Chain.ETH, "ethereum:0xfixture@137") }
        assertThrows(IllegalArgumentException::class.java) { recipientFromQr(Chain.ETH, "ethereum:0xcontract/transfer?uint256=1") }
        assertThrows(IllegalArgumentException::class.java) { recipientFromQr(Chain.ADA, "tron:Tfixture") }
    }
}
