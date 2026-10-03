package io.ledgerlens.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.ledgerlens.app.ui.*
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.ledgerlens.app.model.recipientFromQr

class MainActivity: ComponentActivity() {
    private val model: PortfolioViewModel by viewModels()
    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { raw ->
            val form = model.transfer.value
            val asset = model.state.value.assets.find { it.id == form.assetId }
            if (asset != null) try { model.editTransfer(recipientFromQr(asset.chain, raw), form.amount, form.maximum) }
            catch (e: Exception) { model.reportMessage(e.message ?: "Invalid QR code") }
        }
    }
    private val blePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) model.scanBle()
        else model.reportMessage(Strings(model.state.value.preferences.language)["permission"])
    }
    override fun onStart() { super.onStart(); model.setForeground(true) }
    override fun onStop() { model.setForeground(false); super.onStop() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val devices by model.devices.collectAsStateWithLifecycle()
            val connection by model.connection.collectAsStateWithLifecycle()
            val transfer by model.transfer.collectAsStateWithLifecycle()
            LensTheme(state.preferences.theme) {
                LensApp(state, devices, connection, model::refresh, model::connectUsb, {
                    val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                    if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) model.scanBle() else blePermissions.launch(permissions)
                }, model::connectBle, model::disconnect, model::importAccount, model::updatePreferences, model::toggleHidden, model::removeAccount, model::dismissMessage, onPrices = model::refreshPrices,
                    transfer = transfer, onSend = model::openTransfer, onTransferEdit = model::editTransfer, onTransferClose = model::closeTransfer,
                    onTransferScan = { qrScanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(Strings(state.preferences.language)["scanQr"]).setBeepEnabled(false).setOrientationLocked(false)) },
                    onPrepare = model::prepareTransfer, onConfirm = model::confirmTransfer, onVerify = model::verifyAddress, onStatus = model::refreshTransfers)
            }
        }
    }
}
