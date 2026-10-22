package io.ledgerlens.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import io.ledgerlens.app.alerts.*
import io.ledgerlens.app.data.EncryptedStore
import io.ledgerlens.app.model.*
import io.ledgerlens.app.ui.Strings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class BackgroundWorkerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val controller get() = PriceAlertController.get(app)
    private var initialized = false
    @Before fun setup() = runBlocking {
        // These fixture tests must never overwrite a physical phone's portfolio.
        assumeTrue(Build.HARDWARE in listOf("ranchu", "goldfish") || Build.MODEL.contains("Emulator"))
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        EncryptedStore(app).save(PortfolioState(preferences = Preferences(language = "en")))
        controller.setForeground(true)
        controller.updateSettings(PriceAlertSettings(true, 3, true))
        WorkManager.getInstance(app).cancelUniqueWork(PriceAlertWorker.UNIQUE_NAME).result.get(10, TimeUnit.SECONDS)
        controller.setForeground(false)
        WorkManager.getInstance(app).cancelUniqueWork(PriceAlertWorker.FOLLOWUP_NAME).result.get(10, TimeUnit.SECONDS)
        initialized = true
    }
    @After fun cleanup() = runBlocking {
        if (initialized) { controller.updateSettings(PriceAlertSettings()); app.getSystemService(NotificationManager::class.java).cancel(30001) }
    }
    @Test fun workerRunsWithoutAnActivityAndPersistsItsOwnOutcome() = runBlocking {
        assertTrue(app is LensApplication)
        val previousSuccess = controller.status.value.lastBackgroundCheckAt
        val worker = TestListenableWorkerBuilder<PriceAlertWorker>(app).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(AlertCheckResult.NO_HOLDINGS, controller.status.value.backgroundResult)
        assertTrue(controller.status.value.lastBackgroundAttemptAt > 0)
        assertEquals(previousSuccess, controller.status.value.lastBackgroundCheckAt)
    }
    @Test fun foregroundSkipDoesNotPretendABackgroundCheckCompleted() = runBlocking {
        controller.setForeground(true)
        val before = controller.status.value
        TestListenableWorkerBuilder<PriceAlertWorker>(app).build().doWork()
        assertEquals(before.lastBackgroundAttemptAt, controller.status.value.lastBackgroundAttemptAt)
        assertEquals(before.lastBackgroundCheckAt, controller.status.value.lastBackgroundCheckAt)
        controller.setForeground(false)
    }
    @Test fun explicitBackgroundTestPostsOnlyALabelledTestNotification() = runBlocking {
        val previousSuccess = controller.status.value.lastBackgroundCheckAt
        TestListenableWorkerBuilder<BackgroundTestWorker>(app).build().doWork()
        val posted = app.getSystemService(NotificationManager::class.java).activeNotifications.first { it.id == 30001 }
        assertEquals(Strings("en")["alertTestTitle"], posted.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(controller.status.value.lastBackgroundTestAt > 0)
        assertEquals(previousSuccess, controller.status.value.lastBackgroundCheckAt)
    }
    @Test fun backgroundTestRejectsForegroundDelivery() = runBlocking {
        controller.setForeground(true)
        val before = controller.status.value.lastBackgroundTestAt
        TestListenableWorkerBuilder<BackgroundTestWorker>(app).build().doWork()
        assertEquals(before, controller.status.value.lastBackgroundTestAt)
        assertEquals("Background test requires leaving the app", controller.status.value.backgroundTestError)
        controller.setForeground(false)
    }
    @SuppressLint("RestrictedApi") // Inspect pinned WorkManager request specifications in this test only.
    @Test fun productionScheduleAndDiagnosticHaveDistinctConstraints() {
        val periodic = PriceAlertWorker.request().workSpec
        assertEquals(TimeUnit.MINUTES.toMillis(15), periodic.intervalDuration)
        assertEquals(NetworkType.CONNECTED, periodic.constraints.requiredNetworkType)
        val diagnostic = BackgroundTestWorker.request().workSpec
        assertEquals(TimeUnit.MINUTES.toMillis(1), diagnostic.initialDelay)
        assertEquals(NetworkType.NOT_REQUIRED, diagnostic.constraints.requiredNetworkType)
    }
}
