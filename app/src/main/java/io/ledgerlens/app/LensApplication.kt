package io.ledgerlens.app

import android.app.Application
import io.ledgerlens.app.alerts.PriceAlertController
import kotlinx.coroutines.*

/** WorkManager may start the process without any Activity or ViewModel. */
class LensApplication: Application() {
    private val startup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        startup.launch { runCatching { PriceAlertController.get(this@LensApplication).schedule() } }
    }
}
