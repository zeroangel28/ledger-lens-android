package io.ledgerlens.app.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import io.ledgerlens.app.model.*
import java.math.BigDecimal

private fun number(n: BigDecimal) = n.stripTrailingZeros().toPlainString()
@Composable private fun TransferWindow(title: String, s: Strings, locked: Boolean, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = { if (!locked) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !locked, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).padding(12.dp))
                    IconButton(onClick = onClose, enabled = !locked) { Icon(Icons.Outlined.Close, s["close"]) }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
            }
        }
    }
}

@Composable fun ReceiveScreen(a: Account, s: Strings, busy: Boolean, connection: String?, onClose: () -> Unit, onConnect: () -> Unit, onVerify: (Int) -> Unit, asset: Asset? = null, onGenerate: (() -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current; val context = LocalContext.current
    var selectedIndex by rememberSaveable(a.id) { mutableIntStateOf(a.receiveIndex) }
    var lastIssuedIndex by rememberSaveable(a.id) { mutableIntStateOf(a.receiveIndex) }
    LaunchedEffect(a.receiveIndex) {
        if (lastIssuedIndex != a.receiveIndex) { selectedIndex = a.receiveIndex; lastIssuedIndex = a.receiveIndex }
    }
    val receiving = remember(a.id, a.publicKey, selectedIndex) { a.receivingAddress(selectedIndex) }
    var copied by remember(receiving.address) { mutableStateOf(false) }
    val qr = remember(receiving.address) {
        val matrix = MultiFormatWriter().encode(receiving.address, BarcodeFormat.QR_CODE, 640, 640)
        val pixels = IntArray(640 * 640) { i -> if (matrix[i % 640, i / 640]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        Bitmap.createBitmap(pixels, 640, 640, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    TransferWindow(s["receive"], s, busy, onClose) {
        Text(a.name, style = MaterialTheme.typography.titleLarge)
        Text((asset?.symbol ?: a.chain.symbol) + " · " + a.chain.title + " · Mainnet", color = MaterialTheme.colorScheme.primary)
        if (a.canRotateReceivingAddress) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = { selectedIndex-- }, enabled = !busy && selectedIndex > 0) { Icon(Icons.Outlined.ChevronLeft, s["previousReceive"]) }
                Text(s["receiveNumber"] + " #" + (selectedIndex + 1), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { selectedIndex++ }, enabled = !busy && selectedIndex < a.receiveIndex) { Icon(Icons.Outlined.ChevronRight, s["nextReceive"]) }
            }
            Text("m/" + receiving.path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            if (onGenerate != null) FilledTonalButton(onClick = onGenerate, enabled = !busy && a.receiveIndex < MAX_RECEIVE_INDEX, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(s[if (busy) "updating" else "newReceive"])
            }
            Text(s[if (a.chain == Chain.BTC) "btcNewReceiveHint" else "adaNewReceiveHint"], color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else Text(s["singleReceiveHint"], color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Image(qr, s["receiveQr"], Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White))
        Text(receiving.address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        Text(s["receiveHint"], color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { clipboard.setText(AnnotatedString(receiving.address)); copied = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Icon(Icons.Outlined.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text(s[if (copied) "copied" else "copy"])
        }
        OutlinedButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, receiving.address), s["share"])) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(s["share"]) }
        OutlinedButton(onClick = { if (connection == null) onConnect() else onVerify(selectedIndex) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(s[if (connection == null) "connect" else "verifyAddress"]) }
    }
}

@Composable fun SendScreen(form: TransferUi, asset: Asset, account: Account, s: Strings, connection: String?, onClose: () -> Unit, onConnect: () -> Unit,
    onEdit: (String, String, Boolean) -> Unit, onScan: () -> Unit, onPrepare: () -> Unit, onConfirm: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    TransferWindow(s["send"] + " " + asset.symbol, s, form.working, onClose) {
        Text(account.name + " · " + asset.chain.title + " Mainnet", color = MaterialTheme.colorScheme.primary)
        Text(s["from"] + "\n" + account.address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        if (form.stage == "submitted") {
            Icon(Icons.Outlined.HourglassTop, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Text(s["submittedHint"], style = MaterialTheme.typography.titleMedium)
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(s["close"]) }
        } else if (form.draft == null) {
            OutlinedTextField(form.recipient, { onEdit(it, form.amount, form.maximum) }, label = { Text(s["recipient"]) }, enabled = !form.working, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 4)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onEdit(clipboard.getText()?.text.orEmpty().trim(), form.amount, form.maximum) }, enabled = !form.working, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(s["paste"]) }
                OutlinedButton(onClick = onScan, enabled = !form.working, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text(s["scanQr"]) }
            }
            OutlinedTextField(form.amount, { onEdit(form.recipient, it, false) }, label = { Text(s["amount"] + " · " + asset.symbol) }, enabled = !form.working && !form.maximum,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
            FilterChip(selected = form.maximum, onClick = { onEdit(form.recipient, form.amount, !form.maximum) }, enabled = !form.working, label = { Text(s["maximum"]) })
            Text(s["prepareHint"], color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (connection == null) OutlinedButton(onClick = onConnect, enabled = !form.working, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(s["connect"]) }
            Button(onClick = onPrepare, enabled = !form.working && form.recipient.isNotBlank() && (form.maximum || form.amount.isNotBlank()), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(s["estimateReview"]) }
        } else {
            val draft = form.draft
            Text(s["reviewTransaction"], style = MaterialTheme.typography.titleLarge)
            Text(s["recipient"], style = MaterialTheme.typography.labelLarge)
            Text(draft.recipient, fontFamily = FontFamily.Monospace)
            ReviewLine(s["amount"], number(draft.amount) + " " + draft.symbol)
            ReviewLine(s["networkFee"], "≤ " + number(draft.fee) + " " + draft.chain.symbol)
            if (draft.extraNative.signum() > 0) ReviewLine(s["attachedAda"], number(draft.extraNative) + " ADA")
            val native = asset.contract == null
            ReviewLine(s["totalDebit"], if (native) number(draft.amount + draft.fee) + " " + draft.symbol else number(draft.amount) + " " + draft.symbol + " + ≤ " + number(draft.fee + draft.extraNative) + " " + draft.chain.symbol)
            Text(s["reviewWarning"], color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (connection == null) OutlinedButton(onClick = onConnect, enabled = !form.working, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(s["connect"]) }
            Button(onClick = onConfirm, enabled = !form.working && connection != null, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Icon(Icons.Outlined.Security, null); Spacer(Modifier.width(8.dp)); Text(s["confirmOnLedger"]) }
            TextButton(onClick = { onEdit(form.recipient, form.amount, form.maximum) }, enabled = !form.working) { Text(s["edit"]) }
        }
        if (form.working) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(s[form.stage ?: "preparing"]) }
        form.error?.let { Text(s.explain(it), color = MaterialTheme.colorScheme.error) }
        Text(s["security"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun ReviewLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.titleMedium) }
}

@Composable fun TransferHistory(records: List<TransferRecord>, s: Strings, onRefresh: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(s["transactions"], style = MaterialTheme.typography.titleLarge)
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(s["checkStatus"]) }
        records.take(50).forEach { r ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.amount + " " + r.symbol + " · " + r.chain.title, style = MaterialTheme.typography.titleMedium)
                    Text(s["tx" + r.status.name], color = if (r.status == TransferStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    Text(r.txid, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    r.error?.let { Text(s.explain(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(r.txid)) }) { Text(s["copyTxid"]) }
                }
            }
        }
    }
}
