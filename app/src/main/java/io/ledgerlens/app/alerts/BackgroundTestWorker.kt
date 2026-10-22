package io.ledgerlens.app.alerts

import android.content.Context
import androidx.work.*
import io.ledgerlens.app.data.EncryptedStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Explicit user diagnostic only: no price request, holdings request or transaction. */
class BackgroundTestWorker(context: Context, parameters: WorkerParameters): CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val controller = PriceAlertController.get(applicationContext)
        if (!controller.status.value.settings.let { it.enabled && it.background }) return Result.success()
        try {
            if (controller.foreground) { controller.backgroundTestFinished("Background test requires leaving the app"); return Result.success() }
            val state = withContext(Dispatchers.IO) { EncryptedStore(applicationContext).load() }
            val error = if (controller.testNotification(state)) null else "Price alert notifications blocked"
            controller.backgroundTestFinished(error)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { runCatching { controller.backgroundTestFinished("Price alert storage unavailable") } }
        return Result.success()
    }
    companion object {
        const val UNIQUE_NAME = "ledger-lens-background-test"
        fun request() = OneTimeWorkRequestBuilder<BackgroundTestWorker>().setInitialDelay(1, TimeUnit.MINUTES).build()
        fun schedule(context: Context): Operation = WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request())
    }
}
