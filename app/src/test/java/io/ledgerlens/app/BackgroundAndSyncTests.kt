package io.ledgerlens.app

import io.ledgerlens.app.data.*
import io.ledgerlens.app.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class BackgroundAndSyncTests {
    private val ada = Account("ada", "Fixture", Chain.ADA, Chain.ADA.path(0), "public fixture", "public address")
    private val now = 1_790_000_000_000L
    private fun snapshot(amount: String) = AccountSnapshot(listOf(nativePlaceholder(ada).copy(balanceKnown = true, quantity = BigDecimal(amount))), at = now)
    @Test fun cardanoCanRefreshTwiceWithinAMinuteAndUseNewProviderBalances() = runBlocking {
        var calls = 0
        val fetch: suspend (Account) -> AccountSnapshot = { calls++; snapshot(calls.toString()) }
        val first = refreshHoldingsSnapshot(ada, snapshot("0").copy(lastAttemptAt = now), now, fetch)
        val second = refreshHoldingsSnapshot(ada, first, now + 1_000, fetch)
        assertEquals(2, calls); assertEquals(BigDecimal("2"), second.assets.single().quantity)
        assertEquals(now + 1_000, second.lastAttemptAt)
    }
    @Test fun cardanoFailureDoesNotLockOutTheNextAttemptOrErasePriorBalances() = runBlocking {
        val previous = snapshot("42").copy(lastAttemptAt = now + 86_400_000)
        val failed = refreshHoldingsSnapshot(ada, previous, now) { throw ApiException("fixture", 429, retryAfterSeconds = 60) }
        assertEquals(BigDecimal("42"), failed.assets.single().quantity); assertFalse(failed.complete)
        val recovered = refreshHoldingsSnapshot(ada, failed, now + 60_000) { snapshot("43") }
        assertTrue(recovered.complete); assertNull(recovered.error); assertEquals(BigDecimal("43"), recovered.assets.single().quantity)
    }
    @Test fun cancelledHoldingsReadIsNotSavedAsAFailedBalance() = runBlocking {
        try { refreshHoldingsSnapshot(ada, snapshot("42"), now) { throw CancellationException("fixture") }; fail("Cancellation was swallowed") }
        catch (_: CancellationException) { }
    }
    @Test fun foregroundSuccessCannotOverwriteBackgroundResultOrFailure() {
        val background = PriceAlertStatus(lastCheckedAt = now - 60_000, lastBackgroundAttemptAt = now, lastBackgroundCheckAt = now - 900_000)
            .backgroundFinished(AlertCheckResult.NO_VALID_PRICES, now, "binance: HTTP 429")
        val foreground = background.copy(lastCheckedAt = now + 1_000, error = null)
        assertEquals(now - 900_000, foreground.lastBackgroundCheckAt)
        assertEquals(AlertCheckResult.NO_VALID_PRICES, foreground.backgroundResult)
        assertEquals("binance: HTTP 429", foreground.backgroundError)
    }
    @Test fun onlyValidBackgroundChecksAdvanceBackgroundSuccessTime() {
        val old = PriceAlertStatus(lastBackgroundCheckAt = now - 900_000)
        AlertCheckResult.entries.filter { it != AlertCheckResult.CHECKED }.forEach { result ->
            assertEquals(now - 900_000, old.backgroundFinished(result, now).lastBackgroundCheckAt)
        }
        assertEquals(now, old.backgroundFinished(AlertCheckResult.CHECKED, now).lastBackgroundCheckAt)
    }
    @Test fun backgroundDiagnosticsPersistWithoutAddingPortfolioData() {
        val status = PriceAlertStatus(PriceAlertSettings(true), now, lastBackgroundAttemptAt = now, lastBackgroundCheckAt = now - 100,
            backgroundResult = AlertCheckResult.FAILED, backgroundError = "fixture", lastBackgroundTestAt = now - 200, backgroundTestError = "test fixture")
        val journal = PriceAlertJournal(status)
        val encoded = PriceAlertStore.encode(journal)
        assertEquals(journal, PriceAlertStore.decode(encoded))
        assertFalse(encoded.has("accounts")); assertFalse(encoded.has("keys"))
    }
    @Test fun priorAlertJournalsHaveNoInventedBackgroundSuccess() {
        val legacy = PriceAlertStore.encode(PriceAlertJournal(PriceAlertStatus(PriceAlertSettings(true), now)))
        listOf("backgroundAttempt", "backgroundChecked", "backgroundResult", "backgroundError", "backgroundTest", "backgroundTestError").forEach { legacy.remove(it) }
        val restored = PriceAlertStore.decode(legacy).status
        assertEquals(now, restored.lastCheckedAt); assertEquals(0L, restored.lastBackgroundCheckAt)
        assertEquals(0L, restored.lastBackgroundAttemptAt); assertNull(restored.backgroundResult)
        assertEquals(0L, restored.lastBackgroundTestAt)
    }
}
