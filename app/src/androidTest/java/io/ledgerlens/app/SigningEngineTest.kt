package io.ledgerlens.app

import androidx.test.platform.app.InstrumentationRegistry
import io.ledgerlens.app.ledger.SigningEngine
import io.ledgerlens.app.ledger.LedgerTransport
import io.ledgerlens.app.ledger.LedgerPermit
import io.ledgerlens.app.crypto.unhex
import io.ledgerlens.app.model.Chain
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SigningEngineTest {
    @Test fun authorizedAndroidBridgeVerifiesEthAndUsdtSignaturesAndRevokesPermit() = runBlocking {
        val i = InstrumentationRegistry.getInstrumentation()
        val fixtures = JSONArray(i.context.assets.open("eth-signature-fixtures.json").bufferedReader().use { it.readText() })
        lateinit var engine: SigningEngine
        i.runOnMainSync { engine = SigningEngine(i.targetContext) }
        try {
            for (index in 0 until fixtures.length()) {
                val fixture = fixtures.getJSONObject(index); val account = fixture.getJSONObject("account")
                val mock = object : LedgerTransport {
                    override suspend fun exchange(apdu: ByteArray): ByteArray = when (apdu[1].toInt() and 255) {
                        2 -> byteArrayOf(65) + account.getString("publicKey").unhex() + byteArrayOf(40) + account.getString("address").removePrefix("0x").toByteArray() + "9000".unhex()
                        0x0a -> "9000".unhex()
                        4 -> fixture.getString("signature").unhex() + "9000".unhex()
                        else -> error("Unexpected Ledger instruction")
                    }
                    override fun close() {}
                }
                val prepared = JSONObject(engine.run("build", fixture.getJSONObject("request")))
                val permit = LedgerPermit.reviewedTransaction(Chain.ETH)
                val signed = JSONObject(engine.run("sign", prepared, mock, permit))
                assertTrue(signed.getString("raw").startsWith("0x02")); assertEquals(66, signed.getString("txid").length)
                try { permit.validate("e002000000".unhex()); fail("Permit must be revoked") } catch (_: IllegalArgumentException) { }
                try { engine.run("sign", prepared, mock, LedgerPermit.address(Chain.ETH)); fail("Public-only permit must reject signing") } catch (_: IllegalStateException) { }
            }
        } finally { i.runOnMainSync { engine.close() } }
    }
    @Test fun localWebViewBuildsAllSevenAssetsAndRepeatedCardanoMaximumWithoutNetworkOrLedger() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixtures = JSONArray(instrumentation.context.assets.open("transaction-fixtures.json").bufferedReader().use { it.readText() })
        lateinit var engine: SigningEngine
        instrumentation.runOnMainSync { engine = SigningEngine(instrumentation.targetContext) }
        try {
            for (i in 0 until fixtures.length()) {
                val request = fixtures.getJSONObject(i)
                if (request.getJSONObject("account").getString("chain") == "TRON") request.getJSONObject("data").put("timestamp", System.currentTimeMillis())
                val prepared = JSONObject(engine.run("build", request))
                assertEquals(request.getJSONObject("account").getString("chain"), prepared.getString("chain"))
                assertEquals(request.getString("amount"), prepared.getString("amount"))
                assertTrue(prepared.getString("unsigned").isNotBlank())
            }
            repeat(2) {
                val request = fixtures.getJSONObject(5).put("maximum", true)
                val result = JSONObject(engine.run("build", request))
                assertTrue(result.getString("amount").toLong() > 0)
                assertEquals("ADA", result.getString("chain"))
            }
            val validated = JSONObject(engine.run("validate", JSONObject().put("chain", "ETH").put("address", fixtures.getJSONObject(0).getString("to"))))
            assertTrue(validated.getString("address").startsWith("0x"))
        } finally { instrumentation.runOnMainSync { engine.close() } }
    }
}
