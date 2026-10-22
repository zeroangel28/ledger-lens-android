package io.ledgerlens.app.data

import io.ledgerlens.app.model.*
import kotlinx.coroutines.CancellationException

/** Every explicit refresh attempts the provider, including Cardano and previous failures. */
suspend fun refreshHoldingsSnapshot(account: Account, old: AccountSnapshot?, attemptedAt: Long, fetch: suspend (Account) -> AccountSnapshot): AccountSnapshot = try {
    mergeSnapshot(fetch(account), old).copy(lastAttemptAt = attemptedAt)
} catch (e: CancellationException) { throw e }
catch (e: Exception) {
    (old ?: AccountSnapshot(listOf(nativePlaceholder(account)), at = 0)).copy(error = e.message ?: "Balance query failed", complete = false, lastAttemptAt = attemptedAt)
}
