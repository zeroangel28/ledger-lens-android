package io.ledgerlens.app

import io.ledgerlens.app.crypto.*
import io.ledgerlens.app.data.*
import io.ledgerlens.app.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class DataRecoveryTests {
    private class FakeHttp(val respond: (String, String?, Map<String, String>) -> String) : Http() {
        val urls = mutableListOf<String>()
        override suspend fun get(url: String, headers: Map<String, String>): String { urls += url; return respond(url, null, headers) }
        override suspend fun post(url: String, body: String, headers: Map<String, String>): String { urls += url; return respond(url, body, headers) }
    }
    private fun account(chain: Chain) = Account(chain.name, "Test", chain, chain.path(0), "public", "address", 1)
    private fun asset(chain: Chain) = nativePlaceholder(account(chain)).copy(quantity = BigDecimal.ONE, balanceKnown = true)
    private fun listing(pair: String) = JSONObject().put("symbols", JSONArray().put(JSONObject()
        .put("symbol", pair).put("baseAsset", pair.removeSuffix("USDT")).put("quoteAsset", "USDT")
        .put("status", "TRADING").put("isSpotTradingAllowed", true))).toString()

    @Test fun ethereumUsesRpcHostAndKeepsNativeBalanceWhenTokensFail() = runBlocking {
        val http = FakeHttp { url, body, _ ->
            when {
                url == "https://ethereum-rpc.publicnode.com" -> {
                    assertEquals("eth_getBalance", JSONObject(body!!).getString("method"))
                    """{"result":"0xde0b6b3a7640000"}"""
                }
                url.contains("blockscout") -> throw ApiException("eth.blockscout.com", 429)
                else -> error("Unexpected endpoint: $url")
            }
        }
        val snapshot = ChainBalances(http).fetch(account(Chain.ETH), Preferences())
        assertEquals(BigDecimal("1.000000000000000000"), snapshot.assets.single().quantity)
        assertFalse(snapshot.complete)
        assertNotNull(snapshot.warning)
    }

    @Test fun tronUsdtUsesMainnetIdentityAndAccountResponseWithoutExtraCalls() = runBlocking {
        val http = FakeHttp { url, _, headers ->
            assertEquals("test-data-service-key", headers["TRON-PRO-API-KEY"])
            assertTrue(url.endsWith("?only_confirmed=true"))
            """{"success":true,"data":[{"balance":2500000,"trc20":[{"TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t":"123456789"}]}]}"""
        }
        val snapshot = ChainBalances(http).fetch(account(Chain.TRON), Preferences(tronGridKey = "test-data-service-key"))
        assertEquals(1, http.urls.size)
        assertEquals(BigDecimal("2.500000"), snapshot.assets[0].quantity)
        assertEquals(BigDecimal("123.456789"), snapshot.assets[1].quantity)
        assertEquals("USDT", PriceMapping.symbol(snapshot.assets[1]))
        assertTrue(snapshot.complete)
        assertNull(PriceMapping.symbol(snapshot.assets[1].copy(contract = "TXLAQ63Xg1NAzckPwKHvzw7CSEmLMEqcdj")))
    }

    @Test fun tronMetadataFailureCannotDiscardNativeOrKnownUsdt() = runBlocking {
        val http = FakeHttp { url, _, _ ->
            if (url.contains("/v1/accounts/")) """{"data":[{"balance":1000000,"trc20":[{"unknown-token":"99"},{"TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t":"1000000"}]}]}"""
            else throw ApiException("api.trongrid.io", 429)
        }
        val snapshot = ChainBalances(http).fetch(account(Chain.TRON), Preferences())
        assertEquals(listOf("TRX", "USDT"), snapshot.assets.map { it.symbol })
        assertFalse(snapshot.complete)
        assertNotNull(snapshot.warning)
    }

    @Test fun partialDiscoveryPreservesOldTokenAndManualHiding() {
        val native = asset(Chain.ETH)
        val token = native.copy(id = "token", contract = "contract", quantity = BigDecimal("12"), hidden = true)
        val old = AccountSnapshot(listOf(native, token))
        val partial = mergeSnapshot(AccountSnapshot(listOf(native.copy(quantity = BigDecimal("2"))), warning = "offline", complete = false), old)
        assertEquals(BigDecimal("12"), partial.assets.last().quantity)
        assertTrue(partial.assets.last().hidden)
        val full = mergeSnapshot(AccountSnapshot(listOf(native)), old)
        assertEquals(BigDecimal.ZERO, full.assets.last().quantity)
    }

    @Test fun spotPricesOnlyRequestNeededPairsAndWorkForUnknownBalances() = runBlocking {
        val http = FakeHttp { url, _, _ ->
            when {
                url.contains("exchangeInfo?symbol=BTCUSDT&showPermissionSets=false") -> listing("BTCUSDT")
                url.contains("ticker/price?symbol=BTCUSDT") -> """{"symbol":"BTCUSDT","price":"62000.12345678"}"""
                else -> error("Unexpected endpoint: $url")
            }
        }
        val unknown = nativePlaceholder(account(Chain.BTC))
        val prices = BinancePrices(http).prices(listOf(unknown))
        assertNull(prices.error)
        assertEquals(BigDecimal("62000.12345678"), prices.quotes.getValue(unknown.id).price)
        assertEquals(2, http.urls.size)
    }

    @Test fun spotFailureKeepsOtherSuccessfulQuotesAndDoesNotBecomeZero() = runBlocking {
        val http = FakeHttp { url, _, _ ->
            when {
                url.contains("BTCUSDT") -> throw ApiException("data-api.binance.vision", 429)
                url.contains("exchangeInfo") -> listing("ETHUSDT")
                else -> """{"symbol":"ETHUSDT","price":"3000.25"}"""
            }
        }
        val result = BinancePrices(http).prices(listOf(asset(Chain.BTC), asset(Chain.ETH)))
        assertFalse(result.quotes.containsKey("BTC:native"))
        assertEquals(BigDecimal("3000.25"), result.quotes.getValue("ETH:native").price)
        assertNotNull(result.error)
    }

    @Test fun cardanoNightRecoversMissingPriceByFullAssetIdentityAndRejectsLookalikes() = runBlocking {
        val unit = "0691b2fecca1ac4f53cb6dfb00b7013e561d1f34403b957cbb5af1fa4e49474854"
        val night = asset(Chain.ADA).copy(id = "night", symbol = "NIGHT", name = "Midnight", contract = unit,
            quantity = BigDecimal("123.456789"), decimals = 6, quote = Quote(BigDecimal.ZERO, QuoteState.MISSING, 1))
        val uppercase = night.copy(id = "uppercase", contract = unit.uppercase(), symbol = "TOKEN")
        val wrongPolicy = night.copy(id = "wrong-policy", contract = "a".repeat(56) + "4e49474854")
        val wrongName = night.copy(id = "wrong-name", contract = unit.take(56) + "4e4947485432")
        val wrongChain = night.copy(id = "wrong-chain", chain = Chain.ETH)
        val http = FakeHttp { url, _, _ ->
            when {
                url.endsWith("exchangeInfo?symbol=NIGHTUSDT&showPermissionSets=false") -> listing("NIGHTUSDT")
                url.endsWith("ticker/price?symbol=NIGHTUSDT") -> """{"symbol":"NIGHTUSDT","price":"0.05046000"}"""
                else -> error("Unexpected endpoint: $url")
            }
        }
        val result = BinancePrices(http).prices(listOf(night, uppercase, wrongPolicy, wrongName, wrongChain))
        assertNull(result.error)
        assertEquals(2, http.urls.size)
        for (id in listOf(night.id, uppercase.id)) {
            assertEquals(BigDecimal("0.05046000"), result.quotes.getValue(id).price)
            assertEquals(QuoteState.LIVE, result.quotes.getValue(id).state)
        }
        for (id in listOf(wrongPolicy.id, wrongName.id, wrongChain.id)) {
            assertEquals(BigDecimal.ZERO, result.quotes.getValue(id).price)
            assertEquals(QuoteState.MISSING, result.quotes.getValue(id).state)
        }
    }

    @Test fun onlyConfirmedInvalidPairIsPricedAtZero() = runBlocking {
        val http = FakeHttp { _, _, _ -> throw ApiException("data-api.binance.vision", 400, -1121) }
        val result = BinancePrices(http).prices(listOf(asset(Chain.ETH)))
        assertNull(result.error)
        assertEquals(QuoteState.MISSING, result.quotes.getValue("ETH:native").state)
    }

    @Test fun unavailableBalanceAndPriceSurviveZeroFilters() {
        val unknown = nativePlaceholder(account(Chain.TRON))
        val preferences = Preferences(hideZeroBalance = true, hideZeroValue = true)
        assertTrue(isVisible(unknown, preferences))
        assertTrue(isVisible(unknown.copy(quote = Quote(BigDecimal.ONE, QuoteState.LIVE, 1)), preferences))
        assertTrue(isVisible(asset(Chain.TRON), preferences))
        val a = account(Chain.TRON)
        val unpriced = PortfolioState(listOf(a), mapOf(a.id to AccountSnapshot(listOf(asset(Chain.TRON)))))
        assertFalse(unpriced.hasKnownValuation)
        assertTrue(unpriced.copy(snapshots = mapOf(a.id to AccountSnapshot(listOf(asset(Chain.TRON).copy(quote = Quote(BigDecimal.ZERO, QuoteState.MISSING, 1)))))).hasKnownValuation)
    }

    @Test fun cardanoDailyAttemptSurvivesStorageAndIncludesFailures() {
        val a = account(Chain.ADA)
        val now = 1_790_000_000_000L
        val snapshot = AccountSnapshot(listOf(nativePlaceholder(a)), error = "offline", at = 0, complete = false, lastAttemptAt = now)
        val loaded = EncryptedStore.decode(EncryptedStore.encode(PortfolioState(listOf(a), mapOf(a.id to snapshot))))
        val restored = loaded.snapshots.getValue(a.id)
        assertFalse(cardanoSyncDue(restored, now + CARDANO_SYNC_INTERVAL_MS - 1))
        assertTrue(cardanoSyncDue(restored, now + CARDANO_SYNC_INTERVAL_MS))
        assertTrue(cardanoSyncDue(null, now))
        assertEquals(snapshot, restored)
    }

    @Test fun upgradeUsesPreviousCardanoSyncTimeInsteadOfSpendingQuotaAgain() {
        val a = account(Chain.ADA)
        val now = 1_790_000_000_000L
        val state = PortfolioState(listOf(a), mapOf(a.id to AccountSnapshot(listOf(asset(Chain.ADA)), at = now)))
        val legacy = EncryptedStore.encode(state)
        legacy.getJSONObject("snapshots").getJSONObject(a.id).remove("lastAttemptAt")
        assertFalse(cardanoSyncDue(EncryptedStore.decode(legacy).snapshots[a.id], now + 60_000))
    }

    @Test fun longRetryAfterStopsRequestsAndMaintainsHostCooldown() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("Limited")
                .header("Retry-After", "120").body("{}".toResponseBody()).build()
        }.build()
        val http = Http(client)
        repeat(2) {
            try { http.get("https://api.trongrid.io/test"); fail("Expected rate limit") }
            catch (e: ApiException) { assertEquals(429, e.status) }
        }
        assertEquals(1, calls)
    }

    @Test fun btcFallbackUsesBothReceiveAndChangeBranches() = runBlocking {
        val key = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
        val first = AddressCodec.btc(key, 0, 0)
        val change = AddressCodec.btc(key, 1, 0)
        val http = FakeHttp { url, _, _ ->
            if (url.contains("mempool.space")) throw ApiException("mempool.space", 503)
            val address = url.substringAfterLast('/')
            val used = address == first || address == change
            JSONObject().put("address", address).put("chain_stats", JSONObject().put("tx_count", if (used) 1 else 0)
                .put("funded_txo_sum", if (used) 50_000_000 else 0).put("spent_txo_sum", 0))
                .put("mempool_stats", JSONObject().put("tx_count", 0)).toString()
        }
        val a = account(Chain.BTC).copy(publicKey = key, address = first)
        val result = ChainBalances(http).fetch(a, Preferences(scanGap = 1, scanMax = 10))
        assertEquals(BigDecimal("1.00000000"), result.assets.single().quantity)
        assertFalse(result.scanLimited)
        assertEquals(1, http.urls.count { it.contains("mempool.space") })
    }

    @Test fun cardanoKeylessProviderValidatesOwnershipAndGetsNativeAssets() = runBlocking {
        val key = "aaaca5e7adc69a03ef1f5c017ed02879e8ca871df028461ed9bf19fb8fa15038b40c44dfd9be08591b62be7f9991c85f812d8196927f3c824d9fcb17d275089e"
        val stake = AddressCodec.stake(key)
        val address = AddressCodec.cardano(key, 0, 0)
        val policy = "a".repeat(56)
        val http = FakeHttp { url, _, _ ->
            when {
                url.endsWith("account_addresses") -> JSONArray().put(JSONObject().put("stake_address", stake).put("addresses", JSONArray().put(address))).toString()
                url.endsWith("address_info") -> JSONArray().put(JSONObject().put("address", address).put("balance", "2000000")).toString()
                url.contains("address_assets") -> JSONArray().put(JSONObject().put("address", address).put("policy_id", policy).put("asset_name", "414243").put("decimals", 2).put("quantity", "1234")).toString()
                else -> error("Unexpected endpoint: $url")
            }
        }
        val a = account(Chain.ADA).copy(publicKey = key, address = address)
        val result = ChainBalances(http).fetch(a, Preferences(scanMax = 2))
        assertEquals(BigDecimal("2.000000"), result.assets[0].quantity)
        assertEquals(BigDecimal("12.34"), result.assets[1].quantity)
        assertTrue(result.complete)
    }
}
