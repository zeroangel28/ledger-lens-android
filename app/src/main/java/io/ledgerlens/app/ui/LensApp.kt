package io.ledgerlens.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ledgerlens.app.ledger.NearbyLedger
import io.ledgerlens.app.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun money(n: BigDecimal) = n.setScale(2, RoundingMode.HALF_UP).toPlainString()
private fun quantity(n: BigDecimal) = n.stripTrailingZeros().toPlainString()
private fun timestamp(at: Long?) = if (at == null || at == 0L) "—" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LensApp(
    state: PortfolioState, devices: List<NearbyLedger>, connection: String?, onRefresh: () -> Unit,
    onUsb: () -> Unit, onScan: () -> Unit, onBle: (String) -> Unit, onDisconnect: () -> Unit,
    onImport: (Chain, Int, String) -> Unit, onPreferences: (Preferences) -> Unit,
    onHide: (String) -> Unit, onRemove: (String) -> Unit, onDismiss: () -> Unit,
    onPrices: () -> Unit = onRefresh,
    transfer: TransferUi = TransferUi(), onSend: (String) -> Unit = {}, onTransferEdit: (String, String, Boolean) -> Unit = { _, _, _ -> },
    onTransferClose: () -> Unit = {}, onTransferScan: () -> Unit = {}, onPrepare: () -> Unit = {}, onConfirm: () -> Unit = {},
    onVerify: (String, Int) -> Unit = { _, _ -> }, onStatus: () -> Unit = {}, onGenerateReceive: (String) -> Unit = {},
    priceAlerts: PriceAlertStatus = PriceAlertStatus(), onPriceAlerts: (PriceAlertSettings) -> Unit = {}, onTestNotification: () -> Unit = {}, onNotificationSettings: () -> Unit = {}
) {
    val s = remember(state.preferences.language) { Strings(state.preferences.language) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var connect by rememberSaveable { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var details by remember { mutableStateOf<Asset?>(null) }
    var remove by remember { mutableStateOf<Account?>(null) }
    var receiving by rememberSaveable { mutableStateOf<String?>(null) }
    var receivingAsset by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = if (preview) demoState().copy(preferences = state.preferences) else state
    Scaffold(
        topBar = { Column {
            TopAppBar(title = { Column { Text("Ledger Lens", fontWeight = FontWeight.Bold); Text(s["watchOnly"], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, actions = {
                IconButton(onClick = { onPreferences(state.preferences.copy(privacy = !state.preferences.privacy)) }) { Icon(if (state.preferences.privacy) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, s[if (state.preferences.privacy) "reveal" else "private"]) }
            })
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        bottomBar = { NavigationBar {
            listOf("overview" to Icons.Outlined.AccountBalanceWallet, "accounts" to Icons.Outlined.Link, "settings" to Icons.Outlined.Settings).forEachIndexed { index, (key, icon) ->
                NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icon, null) }, label = { Text(s[key]) })
            }
        } }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when (tab) {
                0 -> Overview(shown, s, preview, { preview = !preview }, { connect = true }, onPrices, { tab = 2 }, { details = it }, Modifier.widthIn(max = 840.dp).fillMaxWidth())
                1 -> Accounts(state, s, { connect = true }, { remove = it }, Modifier.widthIn(max = 840.dp).fillMaxWidth(), onRefresh, { receiving = it.id; receivingAsset = null }, onStatus)
                else -> Settings(state.preferences, s, onPreferences, Modifier.widthIn(max = 840.dp).fillMaxWidth(), priceAlerts, onPriceAlerts, onTestNotification, onNotificationSettings)
            }
        }
    }
    if (connect) ModalBottomSheet(onDismissRequest = { if (!state.busy) connect = false }) {
        ConnectionPanel(state, devices, connection, s, onUsb, onScan, onBle, onDisconnect, onImport)
    }
    details?.let { asset -> ModalBottomSheet(onDismissRequest = { details = null }) {
        Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(asset.name, style = MaterialTheme.typography.headlineSmall)
            KeyValue(s["quantity"], if (state.preferences.privacy) "••••" else if (!asset.balanceKnown) s["unknownBalance"] else quantity(asset.quantity))
            KeyValue(s["price"], if (state.preferences.privacy) "••••" else asset.quote?.let { quantity(it.price) } ?: "—")
            KeyValue(s["value"], if (state.preferences.privacy) "••••" else if (!asset.balanceKnown || asset.quote == null) "—" else money(asset.value))
            KeyValue(s["priceTime"], timestamp(asset.quote?.at))
            Text(s[when(asset.quote?.state) { QuoteState.LIVE -> "live"; QuoteState.STALE -> "stale"; QuoteState.MISSING -> "missing"; null -> "unpriced" }], color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(s["contract"], style = MaterialTheme.typography.labelLarge)
            Text(asset.contract ?: s["native"], fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            if (!preview) {
                Button(onClick = { receiving = asset.accountId; receivingAsset = asset.id; details = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(s["receive"]) }
                if (canTransfer(asset)) OutlinedButton(onClick = { onSend(asset.id); details = null }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(s["send"]) }
            }
            if (!preview) FilledTonalButton(onClick = { onHide(asset.id); details = null }, modifier = Modifier.fillMaxWidth()) { Text(s[if (asset.hidden) "show" else "hide"]) }
            Spacer(Modifier.height(24.dp))
        }
    } }
    if (!connect) {
        state.accounts.find { it.id == receiving }?.let { a -> ReceiveScreen(a, s, state.busy, connection, { receiving = null }, { connect = true }, { onVerify(a.id, it) }, state.assets.find { it.id == receivingAsset }, { onGenerateReceive(a.id) }) }
        state.assets.find { it.id == transfer.assetId }?.let { asset -> state.accounts.find { it.id == asset.accountId }?.let { account ->
            SendScreen(transfer, asset, account, s, connection, onTransferClose, { connect = true }, onTransferEdit, onTransferScan, onPrepare, onConfirm)
        } }
    }
    remove?.let { a -> AlertDialog(onDismissRequest = { remove = null }, title = { Text(s["remove"]) }, text = { Text(a.name + "\n\n" + s["removeBody"]) }, confirmButton = { TextButton(onClick = { onRemove(a.id); remove = null }) { Text(s["remove"]) } }, dismissButton = { TextButton(onClick = { remove = null }) { Text(s["cancel"]) } }) }
    state.message?.let { message -> AlertDialog(onDismissRequest = onDismiss, title = { Text("Ledger Lens") }, text = { Text(s.explain(message)) }, confirmButton = { TextButton(onClick = onDismiss) { Text(s["close"]) } }) }
}

@Composable private fun Overview(state: PortfolioState, s: Strings, preview: Boolean, onPreview: () -> Unit, onConnect: () -> Unit, onRefresh: () -> Unit, onFilter: () -> Unit, onAsset: (Asset) -> Unit, modifier: Modifier) {
    var chain by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(preview) { listState.scrollToItem(0) }
    val assets = state.visible.filter { chain == null || it.chain.name == chain }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (preview) item { Notice(s["demoNotice"], Icons.Outlined.Info); TextButton(onClick = onPreview) { Text(s["exitDemo"]) } }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(24.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Icon(Icons.Outlined.Shield, null, Modifier.size(20.dp)); Text(s[if (state.valuationComplete) "total" else "knownSubtotal"], style = MaterialTheme.typography.labelLarge) }
                    Text(if (state.preferences.privacy) "••••••" else if (state.accounts.isNotEmpty() && !state.hasKnownValuation) "—" else money(state.total), style = MaterialTheme.typography.headlineLarge)
                    Text(s["spot"], style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
                    Text(if (state.updatedAt == null) s["never"] else s["updated"] + " · " + timestamp(state.updatedAt), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!preview && state.accounts.isNotEmpty()) item {
            FilledTonalButton(onClick = onRefresh, enabled = !state.priceBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text(s[if (state.priceBusy) "updating" else "refreshPrices"]) }
        }
        if (state.priceError != null) item { Notice(s["priceError"] + "\n" + s.explain(state.priceError), Icons.Outlined.CloudOff) }
        if (!state.valuationComplete) item { Notice(s["partial"], Icons.Outlined.Info) }
        if (state.accounts.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) { Icon(Icons.Outlined.Hub, null, Modifier.padding(24.dp).size(48.dp), tint = MaterialTheme.colorScheme.primary) }
                Text(s["emptyTitle"], style = MaterialTheme.typography.titleLarge)
                Text(s["emptyBody"], color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(s["connect"]) }
                TextButton(onClick = onPreview) { Text(s["demo"]) }
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(s["yourAssets"], style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onFilter) { Icon(Icons.Outlined.Tune, s["filter"]) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = chain == null, onClick = { chain = null }, label = { Text(s["all"]) })
                    Chain.entries.forEach { c -> FilterChip(selected = chain == c.name, onClick = { chain = c.name }, label = { Text(c.symbol) }) }
                }
            }
            if (assets.isEmpty()) item { Text(s["hiddenEmpty"], color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(assets.sortedByDescending { it.value }, key = { it.id }) { a -> AssetCard(a, state.preferences.privacy, s) { onAsset(a) } }
        }
        item { Text(s["security"], style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(16.dp)) }
    }
}

@Composable private fun AssetCard(asset: Asset, privacy: Boolean, s: Strings, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) { Text(asset.symbol.take(2), modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                Column(Modifier.weight(1f)) { Text(asset.symbol, style = MaterialTheme.typography.titleMedium); Text(asset.chain.title + if (asset.hidden) " · " + s["hidden"] else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (privacy) "••••" else if (!asset.balanceKnown) s["unknownBalance"] else quantity(asset.quantity), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(if (privacy) "•••• USDT" else if (!asset.balanceKnown || asset.quote == null) "— USDT" else money(asset.value) + " USDT", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Text(s["price"] + " · " + (if (privacy) "••••" else asset.quote?.let { quantity(it.price) } ?: "—"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (asset.quote?.state != QuoteState.LIVE) Text(s[when(asset.quote?.state) { QuoteState.MISSING -> "missing"; QuoteState.STALE -> "stale"; else -> "unpriced" }], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun Accounts(state: PortfolioState, s: Strings, onConnect: () -> Unit, onRemove: (Account) -> Unit, modifier: Modifier, onRefresh: () -> Unit, onReceive: (Account) -> Unit, onStatus: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    LazyColumn(modifier, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Text(s["accounts"], style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.height(8.dp)); Text(s["accountHint"], color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text(s["import"]) } }
        if (state.accounts.isNotEmpty()) item { OutlinedButton(onClick = onRefresh, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(s["refresh"]) } }
        if (state.accounts.isEmpty()) item { Text(s["accountEmpty"]) }
        items(state.accounts, key = { it.id }) { a ->
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(a.name, style = MaterialTheme.typography.titleLarge)
                    Text(a.chain.title + " · Mainnet", color = MaterialTheme.colorScheme.primary)
                    Text(a.address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(s["path"] + " · m/" + a.path, style = MaterialTheme.typography.bodySmall)
                    Text(s["balanceTime"] + " · " + timestamp(state.snapshots[a.id]?.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.snapshots[a.id]?.error?.let { Notice(s["syncError"] + "\n" + s.explain(it), Icons.Outlined.CloudOff) }
                    state.snapshots[a.id]?.warning?.let { Notice(s["partialSync"] + "\n" + s.explain(it), Icons.Outlined.Info) }
                    if (state.snapshots[a.id]?.scanLimited == true) Notice(s["scanLimited"], Icons.Outlined.Info)
                    if (a.chain == Chain.ADA) {
                        Text(s["cardanoDaily"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        state.snapshots[a.id]?.lastAttemptAt?.takeIf { it > 0 }?.let { Text(s["nextSync"] + " · " + timestamp(it + CARDANO_SYNC_INTERVAL_MS), style = MaterialTheme.typography.bodySmall) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { onReceive(a) }) { Icon(Icons.Outlined.QrCode, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(s["receive"]) }
                        TextButton(onClick = { clipboard.setText(AnnotatedString(a.address)) }) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(s["copy"]) }
                        TextButton(onClick = { onRemove(a) }, enabled = !state.busy) { Text(s["remove"], color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        if (state.transfers.isNotEmpty()) item { TransferHistory(state.transfers, s, onStatus) }
    }
}

@Composable private fun ConnectionPanel(state: PortfolioState, devices: List<NearbyLedger>, connection: String?, s: Strings, onUsb: () -> Unit, onScan: () -> Unit, onBle: (String) -> Unit, onDisconnect: () -> Unit, onImport: (Chain, Int, String) -> Unit) {
    var chain by rememberSaveable { mutableStateOf(Chain.ETH) }; var index by rememberSaveable { mutableStateOf("0") }; var name by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(s["connection"], style = MaterialTheme.typography.headlineSmall)
        Text(s["connectHint"], color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (connection == null) {
            Button(onClick = onUsb, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Outlined.Usb, null); Spacer(Modifier.width(8.dp)); Text(s["usb"]) }
            OutlinedButton(onClick = onScan, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Outlined.Bluetooth, null); Spacer(Modifier.width(8.dp)); Text(s["ble"]) }
            devices.forEach { device -> OutlinedButton(onClick = { onBle(device.address) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(device.name + " · " + device.address.takeLast(5)) } }
        } else {
            Notice(connection, Icons.Outlined.Link)
            TextButton(onClick = onDisconnect, enabled = !state.busy) { Text(s["disconnect"]) }
            Text(s["chain"], style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Chain.entries.forEach { c -> FilterChip(selected = chain == c, onClick = { chain = c }, enabled = !state.busy, label = { Text(c.symbol) }) } }
            OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text(s["accountName"]) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val valid = index.toIntOrNull()?.let { it in 0..1000 } == true
            OutlinedTextField(value = index, onValueChange = { index = it.take(4) }, label = { Text(s["index"]) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, isError = !valid, supportingText = { Text(if (valid) "m/" + chain.path(index.toInt()) else s["validation"] + " · 0–1000") }, modifier = Modifier.fillMaxWidth())
            Text(s["approveHint"], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { onImport(chain, index.toInt(), name) }, enabled = valid && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Outlined.Shield, null); Spacer(Modifier.width(8.dp)); Text(s["approve"]) }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun Settings(p: Preferences, s: Strings, onChange: (Preferences) -> Unit, modifier: Modifier, alerts: PriceAlertStatus, onAlerts: (PriceAlertSettings) -> Unit, onTestNotification: () -> Unit, onNotificationSettings: () -> Unit) {
    var key by remember(p.blockfrostKey) { mutableStateOf(p.blockfrostKey) }
    var ethKey by remember(p.alchemyKey) { mutableStateOf(p.alchemyKey) }
    var tronKey by remember(p.tronGridKey) { mutableStateOf(p.tronGridKey) }
    val uri = LocalUriHandler.current
    var gap by remember(p.scanGap) { mutableStateOf(p.scanGap.toString()) }; var max by remember(p.scanMax) { mutableStateOf(p.scanMax.toString()) }
    LazyColumn(modifier, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { Text(s["settings"], style = MaterialTheme.typography.headlineSmall) }
        item { PriceAlertSettingsPanel(alerts, s, onAlerts, onTestNotification, onNotificationSettings) }
        item {
            Section(s["appearance"]) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("zh-TW" to "繁體中文", "zh-CN" to "简体中文", "en" to "English").forEach { (code, label) -> FilterChip(selected = p.language == code, onClick = { onChange(p.copy(language = code)) }, label = { Text(label) }) } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("system", "light", "dark").forEach { mode -> FilterChip(selected = p.theme == mode, onClick = { onChange(p.copy(theme = mode)) }, label = { Text(s[mode]) }) } }
            }
        }
        item {
            Section(s["visibility"]) {
                SwitchRow(s["zeroBalance"], s["zeroBalanceHint"], p.hideZeroBalance) { onChange(p.copy(hideZeroBalance = it)) }
                SwitchRow(s["zeroValue"], s["zeroValueHint"], p.hideZeroValue) { onChange(p.copy(hideZeroValue = it)) }
                SwitchRow(s["revealHidden"], "", p.revealHidden) { onChange(p.copy(revealHidden = it)) }
                Text(s["totalHint"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Section(s["data"]) {
                Text(s["dataHint"], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = key, onValueChange = { key = it.trim() }, label = { Text(s["cardanoKey"]) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(s["cardanoKeyHint"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(s["cardanoDaily"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(s["keyHint"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = ethKey, onValueChange = { ethKey = it.trim() }, label = { Text(s["ethKey"]) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(s["ethKeyHint"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = tronKey, onValueChange = { tronKey = it.trim() }, label = { Text(s["tronKey"]) }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(s["tronKeyHint"], style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { uri.openUri("https://www.trongrid.io/") }) { Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(s["createTronKey"]) }
                Button(onClick = { onChange(p.copy(blockfrostKey = key, alchemyKey = ethKey, tronGridKey = tronKey)) }) { Text(s["save"]) }
            }
        }
        item {
            Section(s["scan"]) {
                Text(s["scanHint"], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val valid = gap.toIntOrNull()?.let { it in 5..100 } == true && max.toIntOrNull()?.let { it in 20..1000 && it >= (gap.toIntOrNull() ?: 1001) } == true
                OutlinedTextField(value = gap, onValueChange = { gap = it.take(3) }, label = { Text(s["gap"]) }, supportingText = { Text("5–100") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = max, onValueChange = { max = it.take(4) }, label = { Text(s["max"]) }, supportingText = { Text("20–1000") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Button(onClick = { onChange(p.copy(scanGap = gap.toInt(), scanMax = max.toInt())) }, enabled = valid) { Text(s["save"]) }
            }
        }
        item { Section(s["security"]) { Text(s["securityHint"], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("Ledger Lens · v0.3.0", style = MaterialTheme.typography.labelMedium) } }
    }
}

@Composable fun PriceAlertSettingsPanel(status: PriceAlertStatus, s: Strings, onChange: (PriceAlertSettings) -> Unit, onTest: () -> Unit, onSystemSettings: () -> Unit) {
    val settings = status.settings
    Section(s["priceAlerts"]) {
        SwitchRow(s["alertEnable"], s["alertScope"], settings.enabled) { onChange(settings.copy(enabled = it)) }
        Text(s["alertThreshold"], style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(3, 5).forEach { percent ->
            FilterChip(selected = settings.thresholdPercent == percent, onClick = { onChange(settings.copy(thresholdPercent = percent)) }, modifier = Modifier.heightIn(min = 48.dp), label = { Text("+$percent%") })
        } }
        SwitchRow(s["alertBackground"], s["alertBackgroundHint"], settings.background) { onChange(settings.copy(background = it)) }
        Text(s["alertRule"], color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(s["alertLastCheck"] + " · " + timestamp(status.lastCheckedAt), style = MaterialTheme.typography.bodySmall)
        status.error?.let { Text(s.explain(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        OutlinedButton(onClick = onTest, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Icon(Icons.Outlined.NotificationsActive, null); Spacer(Modifier.width(8.dp)); Text(s["alertTest"]) }
        Text(s["alertTestHint"], color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onSystemSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text(s["alertSystemSettings"]) }
    }
}

@Composable private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); content() }
}
@Composable private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) { Text(title); if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = title })
    }
}
@Composable private fun KeyValue(key: String, value: String) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(key, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, fontFamily = FontFamily.Monospace) } }
@Composable private fun Notice(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) { Row(Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(icon, null, Modifier.size(20.dp)); Text(text, style = MaterialTheme.typography.bodyMedium) } }
}
fun demoState(): PortfolioState {
    val now = 1790964000000L
    val accounts = Chain.entries.map { Account("demo-${it.name}", "${it.title} 1", it, it.path(0), "DEMO", "DEMO · ${it.title}", now) }
    val amounts = listOf("0.125", "1.84", "2400", "850")
    val prices = listOf("62000", "2400", "0.15", "0.36")
    val snapshots = accounts.mapIndexed { i, a -> a.id to AccountSnapshot(listOf(Asset("${a.id}:native", a.id, a.chain, a.chain.symbol, a.chain.title, BigDecimal(amounts[i]), quote = Quote(BigDecimal(prices[i]), QuoteState.LIVE, now))), at = now) }.toMap()
    return PortfolioState(accounts, snapshots, updatedAt = now)
}
@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable private fun PortfolioPreview() { LensTheme("dark") { Overview(demoState(), Strings("zh-TW"), true, {}, {}, {}, {}, {}, Modifier.fillMaxWidth()) } }
