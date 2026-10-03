package io.ledgerlens.app.ledger

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.UUID

data class NearbyLedger(val address: String, val name: String)
@SuppressLint("MissingPermission")
class BleLedger private constructor(private val context: Context): LedgerTransport {
    private lateinit var gatt: BluetoothGatt
    private lateinit var write: BluetoothGattCharacteristic
    private val ready = CompletableDeferred<Unit>()
    private val writeResults = Channel<Int>(Channel.UNLIMITED)
    private val notifications = Channel<ByteArray>(Channel.UNLIMITED)
    private var packetSize = 20
    private var attPayload = 20
    private val callback = object: BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) { gatt = g; g.discoverServices() }
            else if (state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val error = IllegalStateException("Ledger BLE disconnected ($status)"); ready.completeExceptionally(error); notifications.close(error); writeResults.close(error)
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { ready.completeExceptionally(IllegalStateException("BLE discovery failed")); return }
            val service = g.getService(SERVICE) ?: run { ready.completeExceptionally(IllegalStateException("Ledger Flex service not found")); return }
            write = service.getCharacteristic(WRITE) ?: run { ready.completeExceptionally(IllegalStateException("Ledger BLE write characteristic missing")); return }
            val notify = service.getCharacteristic(NOTIFY) ?: run { ready.completeExceptionally(IllegalStateException("Ledger BLE notification characteristic missing")); return }
            if (!g.setCharacteristicNotification(notify, true)) { ready.completeExceptionally(IllegalStateException("BLE notifications failed")); return }
            val descriptor = notify.getDescriptor(CCCD)
            if (descriptor == null) { ready.completeExceptionally(IllegalStateException("BLE descriptor missing")); return }
            if (Build.VERSION.SDK_INT >= 33) {
                if (g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != BluetoothStatusCodes.SUCCESS) ready.completeExceptionally(IllegalStateException("BLE subscription failed"))
            } else { descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; if (!g.writeDescriptor(descriptor)) ready.completeExceptionally(IllegalStateException("BLE subscription failed")) }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) ready.completeExceptionally(IllegalStateException("BLE subscribe failed"))
            else if (!g.requestMtu(156)) ready.complete(Unit)
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) attPayload = (mtu - 3).coerceAtLeast(20)
            ready.complete(Unit)
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { writeResults.trySend(status) }
        @Deprecated("Legacy Android callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { if (Build.VERSION.SDK_INT < 33) notifications.trySend(c.value.copyOf()) }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { notifications.trySend(value.copyOf()) }
    }
    private suspend fun writePacket(bytes: ByteArray) {
        val started = if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(write, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        else { write.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; write.value = bytes; gatt.writeCharacteristic(write) }
        check(started) { "BLE write could not start" }; check(withTimeout(15000) { writeResults.receive() } == BluetoothGatt.GATT_SUCCESS) { "BLE write failed" }
    }
    override suspend fun exchange(apdu: ByteArray): ByteArray { ReadOnlyPolicy.validate(apdu); return transfer(apdu) }
    override suspend fun exchangeAuthorized(apdu: ByteArray, permit: LedgerPermit): ByteArray { permit.validate(apdu); return transfer(apdu) }
    private suspend fun transfer(apdu: ByteArray): ByteArray = withTimeout(120000) {
        for (packet in LedgerFrames.encode(apdu, packetSize, false)) writePacket(packet)
        val decoder = LedgerFrames.Decoder(false)
        while (true) decoder.accept(notifications.receive())?.let { return@withTimeout it }
        @Suppress("UNREACHABLE_CODE") byteArrayOf()
    }
    override fun close() { if (::gatt.isInitialized) { gatt.disconnect(); gatt.close() }; notifications.close(); writeResults.close() }
    companion object {
        val SERVICE: UUID = UUID.fromString("13d63400-2c97-3004-0000-4c6564676572")
        private val NOTIFY = UUID.fromString("13d63400-2c97-3004-0001-4c6564676572")
        private val WRITE = UUID.fromString("13d63400-2c97-3004-0002-4c6564676572")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        suspend fun scan(context: Context): List<NearbyLedger> {
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter ?: error("Bluetooth not supported")
            check(adapter.isEnabled) { "Enable Bluetooth first" }
            val scanner = adapter.bluetoothLeScanner ?: error("BLE scanner unavailable")
            val found = java.util.concurrent.ConcurrentHashMap<String, NearbyLedger>()
            val failure = CompletableDeferred<Int>()
            val callback = object: ScanCallback() {
                override fun onScanResult(type: Int, result: ScanResult) { found[result.device.address] = NearbyLedger(result.device.address, result.device.name ?: "Ledger Flex") }
                override fun onScanFailed(code: Int) { failure.complete(code) }
            }
            try {
                scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
                withTimeoutOrNull(10000) { error("BLE scan failed (${failure.await()})") }
            } finally { scanner.stopScan(callback) }
            return found.values.sortedBy { it.name }
        }
        suspend fun open(context: Context, address: String): LedgerTransport {
            val session = BleLedger(context)
            try {
                withContext(Dispatchers.Main) { session.gatt = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address).connectGatt(context, false, session.callback, BluetoothDevice.TRANSPORT_LE) }
                withTimeout(30000) { session.ready.await() }
                session.writePacket(byteArrayOf(8, 0, 0, 0, 0))
                val mtuReply = withTimeout(15000) { session.notifications.receive() }
                require(mtuReply.size >= 6 && mtuReply[0] == 8.toByte()) { "Ledger BLE MTU negotiation failed" }
                val ledgerSize = mtuReply[5].toInt() and 255
                require(ledgerSize >= 20) { "Invalid Ledger BLE packet size" }
                session.packetSize = minOf(ledgerSize, session.attPayload, 153)
                return SerializedTransport(session)
            } catch (e: Exception) { session.close(); throw e }
        }
    }
}
