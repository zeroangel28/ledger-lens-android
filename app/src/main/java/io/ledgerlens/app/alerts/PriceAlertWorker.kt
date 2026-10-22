package io.ledgerlens.app.alerts

import android.content.Context
import androidx.work.*
import io.ledgerlens.app.data.EncryptedStore
import io.ledgerlens.app.model.PriceAlertSettings
import io.ledgerlens.app.model.AlertCheckResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class PriceAlertWorker(context: Context, parameters: WorkerParameters): CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val controller = PriceAlertController.get(applicationContext)
        if (controller.foreground || !controller.status.value.settings.let { it.enabled && it.background }) return Result.success()
        try {
            controller.backgroundStarted()
            val state = withContext(Dispatchers.IO) { EncryptedStore(applicationContext).load() }
            val result = controller.check(state)
            val error = if (result in setOf(AlertCheckResult.BLOCKED, AlertCheckResult.NO_VALID_PRICES, AlertCheckResult.FAILED)) controller.status.value.error else null
            controller.backgroundFinished(result, error)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { controller.backgroundFinished(AlertCheckResult.INTERRUPTED) } }; throw e
        } catch (_: Exception) {
            runCatching { controller.backgroundFinished(AlertCheckResult.FAILED, "Price alert storage unavailable") }
            controller.reportFailure("Price alert storage unavailable")
        }
        // Provider failures are shown and checked next period; do not hammer an unavailable endpoint.
        return Result.success()
    }
    companion object {
        const val UNIQUE_NAME = "binance-spot-price-alerts"
        const val FOLLOWUP_NAME = "binance-spot-price-alerts-after-close"
        fun request() = PeriodicWorkRequestBuilder<PriceAlertWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        fun cancelFollowup(context: Context) { WorkManager.getInstance(context).cancelUniqueWork(FOLLOWUP_NAME) }
        fun afterForeground(context: Context, settings: PriceAlertSettings, available: Boolean = true) {
            if (!available || !settings.enabled || !settings.background) return
            val request = OneTimeWorkRequestBuilder<PriceAlertWorker>().setInitialDelay(30, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(FOLLOWUP_NAME, ExistingWorkPolicy.KEEP, request)
        }
        fun schedule(context: Context, settings: PriceAlertSettings, available: Boolean = true) {
            val manager = WorkManager.getInstance(context)
            if (!available || !settings.enabled || !settings.background) {
                manager.cancelUniqueWork(UNIQUE_NAME); manager.cancelUniqueWork(FOLLOWUP_NAME)
                manager.cancelUniqueWork(BackgroundTestWorker.UNIQUE_NAME); return
            }
            // Refresh old work definitions without resetting the periodic enqueue time.
            manager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request())
        }
    }
}
