package io.ledgerlens.app

import io.ledgerlens.app.crypto.*
import io.ledgerlens.app.data.*
import io.ledgerlens.app.ledger.*
import io.ledgerlens.app.model.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class PortfolioTests {
    @Test fun cardanoPublicCkdMatchesPublishedV2GoldenVector() {
        // input-output-hk/rust-cardano, hdwallet.rs, DerivationScheme::V2 golden test.
        val parent = "aaaca5e7adc69a03ef1f5c017ed02879e8ca871df028461ed9bf19fb8fa15038b40c44dfd9be08591b62be7f9991c85f812d8196927f3c824d9fcb17d275089e".unhex()
        val child = AddressCodec.edChild(AddressCodec.edChild(parent, 24), 2000)
        assertEquals("98bcf394fc33f5d0a6e135c1c934c69c327bc3c91dd02db3f2ef2c805bbaab10342fe9b5b3bb154321d526f5c80538324bb67964817ccc0cea227a495025b007", child.hex())
    }
    @Test fun bip84ReceiveAndChangeMatchPublishedVectors() {
        // Public account key and addresses from bitcoin/bips, BIP84.
        val key = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", AddressCodec.btc(key, 0, 0))
        assertEquals("bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g", AddressCodec.btc(key, 0, 1))
        assertEquals("bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el", AddressCodec.btc(key, 1, 0))
    }
    @Test fun ethereumAndTronFromPublicGeneratorPoint() {
        val publicKey = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798".unhex()
        assertEquals("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf", AddressCodec.ethereum(publicKey))
        assertEquals("TMVQGm1qAQYVdetCeGRRkTWYYrLXuHK2HC", AddressCodec.tron(publicKey))
    }
    @Test fun hidAndBleFragmentationRoundTrips() {
        val payload = ByteArray(252) { it.toByte() } + byteArrayOf(0x90.toByte(), 0)
        for (usb in listOf(true, false)) {
            val decoder = LedgerFrames.Decoder(usb); var result: ByteArray? = null
            LedgerFrames.encode(payload, if (usb) 64 else 20, usb).forEach { result = decoder.accept(it) }
            assertArrayEquals(payload, result)
        }
    }
    @Test(expected = IllegalArgumentException::class) fun outOfOrderFramesAreRejected() {
        val frames = LedgerFrames.encode(ByteArray(120), 64, true)
        LedgerFrames.Decoder(true).accept(frames[1])
    }
    @Test fun signingCommandsNeverPassTheTransportBoundary() {
        listOf(0xe1 to 4, 0xe0 to 4, 0xd7 to 0x21, 0xd7 to 0x24, 0xe1 to 0x10).forEach { (cla, ins) ->
            assertThrows(IllegalArgumentException::class.java) { ReadOnlyPolicy.validate(PublicKeyReader.command(cla, ins, 0, 0, byteArrayOf())) }
        }
        ReadOnlyPolicy.validate(PublicKeyReader.command(0xe1, 0, 0, 1, byteArrayOf(1) + PublicKeyReader.encodePath("84'/0'/0'")))
    }
    @Test fun pathEncodingMatchesBip32Apdu() {
        assertEquals("03800000548000000080000000", PublicKeyReader.encodePath("84'/0'/0'").hex())
        assertThrows(IllegalArgumentException::class.java) { PublicKeyReader.encodePath("44'/-1'") }
        assertThrows(IllegalStateException::class.java) { PublicKeyReader.unwrap(byteArrayOf(0x69, 0x85.toByte())) }
    }
    @Test fun smallBalanceIsNotHiddenByDisplayRounding() {
        val a = Asset("x", "a", Chain.BTC, "BTC", "Bitcoin", BigDecimal("0.00000001"), quote = Quote(BigDecimal("0.01"), QuoteState.LIVE, 1))
        assertTrue(isVisible(a, Preferences(hideZeroBalance = true, hideZeroValue = true)))
        assertFalse(isVisible(a.copy(quantity = BigDecimal.ZERO), Preferences(hideZeroBalance = true)))
        assertFalse(isVisible(a.copy(quote = Quote(BigDecimal.ZERO, QuoteState.MISSING, 1)), Preferences(hideZeroValue = true)))
        assertFalse(isVisible(a.copy(hidden = true), Preferences()))
        assertTrue(isVisible(a.copy(hidden = true), Preferences(revealHidden = true)))
    }
    @Test fun symbolsCannotSpoofVerifiedTokens() {
        val fake = Asset("x", "a", Chain.ETH, "USDT", "Tether", BigDecimal.ONE, "0x0000000000000000000000000000000000000000")
        assertNull(PriceMapping.symbol(fake))
        assertEquals("USDT", PriceMapping.symbol(fake.copy(contract = "0xdac17f958d2ee523a2206206994597c13d831ec7")))
    }
    @Test fun portfolioStoragePreservesPrecisionAndHiddenFlags() {
        val a = Account("account", "Test", Chain.ETH, Chain.ETH.path(0), "public", "address", 1)
        val asset = Asset("asset", a.id, Chain.ETH, "ETH", "Ethereum", BigDecimal("1.123456789123456789"), hidden = true, quote = Quote(BigDecimal("3000.1234"), QuoteState.STALE, 2))
        val state = PortfolioState(listOf(a), mapOf(a.id to AccountSnapshot(listOf(asset), "offline", 2)), Preferences(language = "en", hideZeroValue = true))
        val loaded = EncryptedStore.decode(EncryptedStore.encode(state))
        assertEquals(state, loaded)
        assertEquals(asset.value, loaded.total)
    }
    @Test fun rawAmountUsesDecimalPrecision() {
        assertEquals(BigDecimal("1.000000000000000001"), rawAmount("1000000000000000001", 18))
    }
}
