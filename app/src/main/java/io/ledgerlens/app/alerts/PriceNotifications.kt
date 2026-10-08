package io.ledgerlens.app.alerts

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.ledgerlens.app.MainActivity
import io.ledgerlens.app.R
import io.ledgerlens.app.model.PriceMove
import io.ledgerlens.app.ui.Strings
import java.math.RoundingMode

class PriceNotifications(private val context: Application) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val channel = "spot-price-moves-v1"
    fun available(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return manager.areNotificationsEnabled() && manager.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    @SuppressLint("MissingPermission") // available() checks both runtime permission and channel state.
    fun post(move: PriceMove?, language: String, privacy: Boolean): Boolean {
        val s = Strings(language)
        manager.createNotificationChannel(NotificationChannel(channel, s["priceAlerts"], NotificationManager.IMPORTANCE_HIGH))
        if (!available()) return false
        val title = if (move == null) s["alertTestTitle"] else if (privacy) s["priceAlerts"] else move.pair.removeSuffix("USDT") + " +" + move.percent.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%"
        val text = if (move == null) s["alertTestBody"] else if (privacy) s["alertPrivateBody"] else s["alertRiseBody"] + " · " + move.price.stripTrailingZeros().toPlainString() + " USDT"
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_price_alert).setContentTitle(s["priceAlerts"]).setContentText(s["alertPrivateBody"]).build()
        val notification = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_price_alert).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public).setContentIntent(intent).setAutoCancel(true).build()
        try { manager.notify(move?.pair?.hashCode() ?: 30001, notification) } catch (_: SecurityException) { return false }
        return true
    }
}
