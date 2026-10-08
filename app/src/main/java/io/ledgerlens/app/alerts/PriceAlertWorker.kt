package io.ledgerlens.app.alerts

import android.content.Context
import androidx.work.*
import io.ledgerlens.app.data.EncryptedStore
import io.ledgerlens.app.model.PriceAlertSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class PriceAlertWorker(context: Context, parameters: WorkerParameters): CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val controller = PriceAlertController.get(applicationContext)
        if (controller.foreground || !controller.status.value.settings.let { it.enabled && it.background }) return Result.success()
        try {
            val state = withContext(Dispatchers.IO) { EncryptedStore(applicationContext).load() }
            controller.check(state)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { controller.reportFailure("Price alert storage unavailable") }
        // Provider failures are shown and checked next period; do not hammer an unavailable endpoint.
        return Result.success()
    }
    companion object {
        const val UNIQUE_NAME = "binance-spot-price-alerts"
        fun schedule(context: Context, settings: PriceAlertSettings, available: Boolean = true) {
            val manager = WorkManager.getInstance(context)
            if (!available || !settings.enabled || !settings.background) { manager.cancelUniqueWork(UNIQUE_NAME); return }
            val request = PeriodicWorkRequestBuilder<PriceAlertWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            manager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
