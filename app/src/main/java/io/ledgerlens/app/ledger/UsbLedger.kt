package io.ledgerlens.app.ledger

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class UsbLedger(private val connection: UsbDeviceConnection, private val usbInterface: UsbInterface, private val input: UsbEndpoint, private val output: UsbEndpoint): LedgerTransport {
    override suspend fun exchange(apdu: ByteArray): ByteArray { ReadOnlyPolicy.validate(apdu); return transfer(apdu) }
    override suspend fun exchangeAuthorized(apdu: ByteArray, permit: LedgerPermit): ByteArray { permit.validate(apdu); return transfer(apdu) }
    private suspend fun transfer(apdu: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        for (frame in LedgerFrames.encode(apdu, 64, true)) check(connection.bulkTransfer(output, frame, frame.size, 10000) == frame.size) { "USB write failed" }
        val decoder = LedgerFrames.Decoder(true)
        val deadline = System.nanoTime() + 120_000_000_000L
        while (System.nanoTime() < deadline) {
            coroutineContext.ensureActive()
            val frame = ByteArray(64); val n = connection.bulkTransfer(input, frame, frame.size, 1000)
            if (n > 0) decoder.accept(frame.copyOf(n))?.let { return@withContext it }
        }
        error("Ledger confirmation timed out")
    }
    override fun close() { connection.releaseInterface(usbInterface); connection.close() }
    companion object {
        suspend fun open(context: Context): LedgerTransport {
            val manager = context.getSystemService(UsbManager::class.java)
            val device = manager.deviceList.values.firstOrNull { it.vendorId == 0x2c97 } ?: error("No Ledger USB device found")
            if (!manager.hasPermission(device)) {
                val action = context.packageName + ".USB_PERMISSION"
                val granted = CompletableDeferred<Boolean>()
                val receiver = object: BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        if (intent?.action == action) granted.complete(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, IntentFilter(action))
                try {
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                    manager.requestPermission(device, PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), flags))
                    check(withTimeout(60000) { granted.await() }) { "USB permission denied" }
                } finally { context.unregisterReceiver(receiver) }
            }
            val intf = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID } ?: error("Ledger HID interface missing")
            val endpoints = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            val input = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: error("USB input missing")
            val output = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: error("USB output missing")
            val connection = manager.openDevice(device) ?: error("Cannot open Ledger")
            if (!connection.claimInterface(intf, true)) { connection.close(); error("Ledger is busy in another app") }
            return SerializedTransport(UsbLedger(connection, intf, input, output))
        }
    }
}
