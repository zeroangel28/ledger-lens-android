package io.ledgerlens.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.ledgerlens.app.data.*
import io.ledgerlens.app.ledger.*
import io.ledgerlens.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger

class PortfolioViewModel(app: Application): AndroidViewModel(app) {
    private val store = EncryptedStore(app)
    private val http = Http(); private val balances = ChainBalances(http)
    private var storageLocked = false
    private val initial = try { store.load() } catch (e: Exception) { storageLocked = true; PortfolioState(message = "Encrypted portfolio cannot be opened. Data has not been overwritten.") }
    private val prices = BinancePrices(http, initial.spotMarkets)
    private val mutableState = MutableStateFlow(initial)
    val state = mutableState.asStateFlow()
    private val saveMutex = Mutex()
    private val quoteMutex = Mutex()
    private var foregroundJob: Job? = null
    private var transport: LedgerTransport? = null
    private val engine = SigningEngine(app)
    private val transactions = TransactionData(http)
    private val mutableTransfer = MutableStateFlow(TransferUi())
    val transfer = mutableTransfer.asStateFlow()
    private val statusMutex = Mutex()
    private val lastStatusCheck = mutableMapOf<String, Long>()
    private val mutableDevices = MutableStateFlow<List<NearbyLedger>>(emptyList())
    val devices = mutableDevices.asStateFlow()
    private val mutableConnection = MutableStateFlow<String?>(null)
    val connection = mutableConnection.asStateFlow()
    fun setForeground(active: Boolean) {
        foregroundJob?.cancel(); foregroundJob = null
        if (active) foregroundJob = viewModelScope.launch {
            supervisorScope {
                launch { while (isActive) { try { refreshQuotes() } catch (e: CancellationException) { throw e } catch (e: Exception) { reportMessage(e.message ?: "Price refresh failed") }; delay(30_000) } }
                launch { while (isActive) { try { checkTransfers() } catch (e: CancellationException) { throw e } catch (e: Exception) { reportMessage(e.message ?: "Status query failed") }; delay(30_000) } }
            }
        }
    }
    fun refreshPrices() { viewModelScope.launch { refreshQuotes() } }
    private suspend fun refreshQuotes() {
        if (storageLocked || !quoteMutex.tryLock()) return
        try {
            val assets = mutableState.value.assets
            if (assets.isEmpty()) return
            mutableState.update { it.copy(priceBusy = true) }
            val batch = prices.prices(assets)
            mutableState.update { s -> s.copy(snapshots = s.snapshots.mapValues { (_, snapshot) -> snapshot.copy(assets = snapshot.assets.map { a -> a.copy(quote = batch.quotes[a.id] ?: a.quote?.copy(state = QuoteState.STALE)) }) }, priceError = batch.error, updatedAt = System.currentTimeMillis(), spotMarkets = prices.cache) }
            persist()
        } finally { mutableState.update { it.copy(priceBusy = false) }; quoteMutex.unlock() }
    }
    private suspend fun persist() { if (storageLocked) return; saveMutex.withLock { val snapshot = mutableState.value; withContext(Dispatchers.IO) { store.save(snapshot) } } }
    private fun action(block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { mutableState.update { it.copy(message = e.message ?: "Operation failed") } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
    fun connectUsb() = action { disconnect(); transport = UsbLedger.open(getApplication()); mutableConnection.value = "USB · Ledger" }
    fun scanBle() = action { mutableDevices.value = BleLedger.scan(getApplication()); if (mutableDevices.value.isEmpty()) error("No Ledger Flex found. Enable Bluetooth on the device.") }
    fun connectBle(address: String) = action { disconnect(); transport = BleLedger.open(getApplication(), address); mutableConnection.value = "BLE · Ledger Flex"; mutableDevices.value = emptyList() }
    fun disconnect() { if (mutableTransfer.value.working) return; transport?.close(); transport = null; mutableConnection.value = null }
    fun importAccount(chain: Chain, index: Int, name: String) = action {
        check(!storageLocked) { "Encrypted storage is unavailable" }
        val session = transport ?: error("Connect Ledger first")
        val account = withContext(Dispatchers.Default) { PublicKeyReader.read(session, chain, index, name) }
        check(mutableState.value.accounts.none { it.chain == account.chain && it.path == account.path && it.publicKey == account.publicKey }) { "Account already imported" }
        mutableState.update { it.copy(accounts = it.accounts + account, snapshots = it.snapshots + (account.id to AccountSnapshot(listOf(nativePlaceholder(account)), at = 0, complete = false))) }; persist()
    }
    fun refresh() = action {
        mutableState.update { s -> s.copy(snapshots = s.snapshots + s.accounts.filter { s.snapshots[it.id]?.assets.isNullOrEmpty() }.associate {
            it.id to (s.snapshots[it.id] ?: AccountSnapshot(emptyList(), at = 0)).copy(assets = listOf(nativePlaceholder(it)), complete = false)
        }) }
        val before = mutableState.value
        if (before.accounts.isEmpty()) return@action
        coroutineScope {
        // Prices start immediately; a slow address scan never holds them back.
        val firstPrices = async {
            prices.prices(before.assets).also { batch ->
                mutableState.update { s -> s.copy(snapshots = s.snapshots.mapValues { (_, snapshot) -> snapshot.copy(assets = snapshot.assets.map { a -> a.copy(quote = batch.quotes[a.id] ?: a.quote?.copy(state = QuoteState.STALE)) }) }, priceError = batch.error) }
            }
        }
        for (account in before.accounts) {
            val old = mutableState.value.snapshots[account.id]
            val attemptedAt = System.currentTimeMillis()
            if (account.chain == Chain.ADA && !cardanoSyncDue(old, attemptedAt)) continue
            if (account.chain == Chain.ADA) {
                // Persist before network access, so failure, cancellation and process death cannot reset the daily budget.
                mutableState.update { s -> s.copy(snapshots = s.snapshots + (account.id to (old ?: AccountSnapshot(listOf(nativePlaceholder(account)), at = 0, complete = false)).copy(lastAttemptAt = attemptedAt))) }
                persist()
            }
            val snapshot = try {
                val fresh = withContext(Dispatchers.Default) { balances.fetch(account, before.preferences) }
                mergeSnapshot(fresh, old).copy(lastAttemptAt = attemptedAt)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { (old ?: AccountSnapshot(listOf(nativePlaceholder(account)), at = 0)).copy(error = e.message ?: "Balance query failed", complete = false, lastAttemptAt = attemptedAt) }
            mutableState.update { it.copy(snapshots = it.snapshots + (account.id to snapshot)) }
        }
        val initial = firstPrices.await()
        val requestedIds = before.assets.map { it.id }.toSet()
        val discovered = prices.prices(mutableState.value.assets.filter { it.id !in requestedIds })
        val quotes = initial.quotes + discovered.quotes
        val priceError = listOfNotNull(initial.error, discovered.error).takeIf { it.isNotEmpty() }?.joinToString("\n")
        mutableState.update { s -> s.copy(snapshots = s.snapshots.mapValues { (_, snapshot) -> snapshot.copy(assets = snapshot.assets.map { a -> a.copy(quote = quotes[a.id] ?: a.quote?.copy(state = QuoteState.STALE)) }) }, priceError = priceError, updatedAt = System.currentTimeMillis()) }
        }
        mutableState.update { it.copy(spotMarkets = prices.cache) }
        persist()
    }
    fun updatePreferences(p: Preferences) { mutableState.update { it.copy(preferences = p) }; saveAsync() }
    fun toggleHidden(id: String) { mutableState.update { s -> s.copy(snapshots = s.snapshots.mapValues { (_, snapshot) -> snapshot.copy(assets = snapshot.assets.map { if (it.id == id) it.copy(hidden = !it.hidden) else it }) }) }; saveAsync() }
    fun removeAccount(id: String) {
        if (mutableState.value.busy) return
        if (mutableState.value.transfers.any { it.accountId == id && it.status in setOf(TransferStatus.SUBMITTING, TransferStatus.PENDING, TransferStatus.UNKNOWN) }) { reportMessage(io.ledgerlens.app.ui.Strings(mutableState.value.preferences.language)["pendingAccount"]); return }
        mutableState.update { it.copy(accounts = it.accounts.filterNot { a -> a.id == id }, snapshots = it.snapshots - id) }; saveAsync()
    }
    fun dismissMessage() { mutableState.update { it.copy(message = null) } }
    fun reportMessage(message: String) { mutableState.update { it.copy(message = message) } }
    fun openTransfer(assetId: String) {
        if (mutableTransfer.value.working || mutableState.value.busy) return
        val asset = mutableState.value.assets.find { it.id == assetId } ?: return
        if (canTransfer(asset)) mutableTransfer.value = TransferUi(assetId = assetId)
    }
    fun editTransfer(recipient: String, amount: String, maximum: Boolean) {
        if (!mutableTransfer.value.working) mutableTransfer.update { it.copy(recipient = recipient.take(300), amount = amount.take(90), maximum = maximum, draft = null, error = null, stage = null) }
    }
    fun closeTransfer() { if (!mutableTransfer.value.working) mutableTransfer.value = TransferUi() }
    private suspend fun freshAccount(account: Account) {
        val attempted = System.currentTimeMillis()
        val old = mutableState.value.snapshots[account.id]
        mutableState.update { s -> s.copy(snapshots = s.snapshots + (account.id to (old ?: AccountSnapshot(listOf(nativePlaceholder(account)), at = 0)).copy(lastAttemptAt = attempted))) }
        persist()
        val fresh = withContext(Dispatchers.Default) { balances.fetch(account, mutableState.value.preferences) }
        mutableState.update { s -> s.copy(snapshots = s.snapshots + (account.id to mergeSnapshot(fresh, s.snapshots[account.id]).copy(lastAttemptAt = attempted))) }
        persist()
    }
    fun prepareTransfer() {
        if (mutableTransfer.value.working || mutableState.value.busy || storageLocked) return
        val form = mutableTransfer.value
        val asset = mutableState.value.assets.find { it.id == form.assetId } ?: return
        val account = mutableState.value.accounts.find { it.id == asset.accountId } ?: return
        mutableTransfer.update { it.copy(working = true, error = null, stage = "preparing", draft = null) }
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                check(canTransfer(asset)) { "Unsupported asset" }
                check(mutableState.value.transfers.none { it.accountId == account.id && it.status in setOf(TransferStatus.SUBMITTING, TransferStatus.PENDING, TransferStatus.UNKNOWN) }) { "This account has an unresolved transaction. Check its status before sending again." }
                val recipient = JSONObject(engine.run("validate", JSONObject().put("chain", account.chain.name).put("address", form.recipient.trim()))).getString("address")
                val units = if (form.maximum) BigInteger.ZERO else BigDecimal(form.amount).movePointRight(asset.decimals).toBigIntegerExact().also { require(it.signum() > 0) { "Amount must be positive" } }
                freshAccount(account)
                val data = transactions.prepare(account, asset, recipient, units, form.maximum, mutableState.value.preferences)
                val request = JSONObject().put("account", accountJson(account)).put("to", recipient).put("amount", units.toString()).put("maximum", form.maximum)
                    .put("contract", asset.contract ?: JSONObject.NULL).put("data", data)
                val prepared = engine.run("build", request); val row = JSONObject(prepared)
                val draft = TransferDraft(asset.id, account.id, account.chain, asset.symbol, row.getString("to"), rawAmount(row.getString("amount"), asset.decimals),
                    rawAmount(row.getString("fee"), account.chain.decimals), rawAmount(row.getString("extraNative"), account.chain.decimals), prepared, row.getLong("expiresAt"))
                mutableTransfer.update { it.copy(draft = draft, stage = "review") }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { mutableTransfer.update { it.copy(error = e.message ?: "Preparation failed", stage = null) } }
            finally { mutableTransfer.update { it.copy(working = false) }; mutableState.update { it.copy(busy = false) } }
        }
    }
    /** Called only by the phone's explicit final confirmation button. */
    fun confirmTransfer() {
        val form = mutableTransfer.value; val draft = form.draft ?: return
        if (form.working || mutableState.value.busy || storageLocked) return
        mutableTransfer.update { it.copy(working = true, error = null, stage = "ledgerApproval") }; mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                check(System.currentTimeMillis() < draft.expiresAt) { "Transaction estimate expired; prepare again" }
                check(mutableState.value.transfers.none { it.accountId == draft.accountId && it.status in setOf(TransferStatus.SUBMITTING, TransferStatus.PENDING, TransferStatus.UNKNOWN) }) { "An unresolved transaction already exists" }
                val session = transport ?: error("Connect Ledger and open the matching coin app before signing")
                val signed = JSONObject(engine.run("sign", JSONObject(draft.preparedJson), session, LedgerPermit.reviewedTransaction(draft.chain)))
                check(System.currentTimeMillis() < draft.expiresAt) { "Transaction estimate expired during signing. Nothing was broadcast; prepare again." }
                val id = signed.getString("txid"); require(id.matches(Regex(if (draft.chain == Chain.ETH) "0x[0-9a-fA-F]{64}" else "[0-9a-fA-F]{64}")))
                val record = TransferRecord(id, draft.accountId, draft.chain, draft.symbol, draft.recipient, draft.amount.toPlainString(), TransferStatus.SUBMITTING)
                mutableState.update { it.copy(transfers = listOf(record) + it.transfers) }; persist()
                mutableTransfer.update { it.copy(stage = "broadcasting") }
                try {
                    transactions.broadcast(draft.chain, signed.getString("raw"), id, mutableState.value.preferences)
                    mutableState.update { it.copy(transfers = it.transfers.map { r -> if (r.txid == id) r.copy(status = TransferStatus.PENDING) else r }) }
                } catch (e: Exception) {
                    // A transport timeout can happen after node acceptance. Never automatically submit again.
                    mutableState.update { it.copy(transfers = it.transfers.map { r -> if (r.txid == id) r.copy(status = TransferStatus.UNKNOWN, error = e.message) else r }) }
                }
                persist(); mutableTransfer.update { it.copy(stage = "submitted", draft = null) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { mutableTransfer.update { it.copy(error = e.message ?: "Signing failed", stage = null, draft = null) } }
            finally { mutableTransfer.update { it.copy(working = false) }; mutableState.update { it.copy(busy = false) } }
        }
    }
    fun verifyAddress(accountId: String) = action {
        val a = mutableState.value.accounts.find { it.id == accountId } ?: error("Account unavailable")
        val session = transport ?: error("Connect Ledger first")
        engine.run("verifyAddress", accountJson(a), session, LedgerPermit.address(a.chain))
        reportMessage(io.ledgerlens.app.ui.Strings(mutableState.value.preferences.language)["addressVerified"])
    }
    fun refreshTransfers() { viewModelScope.launch { checkTransfers(force = true) } }
    private suspend fun checkTransfers(force: Boolean = false) {
        if (storageLocked || !statusMutex.tryLock()) return
        try {
            for (record in mutableState.value.transfers.filter { it.status in setOf(TransferStatus.SUBMITTING, TransferStatus.PENDING, TransferStatus.UNKNOWN) }.take(20)) {
                val now = System.currentTimeMillis(); val interval = if (record.chain == Chain.ADA) 60_000 else 30_000
                if (!force && now - (lastStatusCheck[record.txid] ?: 0) < interval) continue
                lastStatusCheck[record.txid] = now
                try {
                    val status = transactions.status(record, mutableState.value.preferences)
                    mutableState.update { it.copy(transfers = it.transfers.map { r -> if (r.txid == record.txid) r.copy(status = status, error = if (status == TransferStatus.UNKNOWN || status == TransferStatus.SUBMITTING) r.error else null) else r }) }; persist()
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    mutableState.update { it.copy(transfers = it.transfers.map { r -> if (r.txid == record.txid) r.copy(error = e.message) else r }) }; persist()
                }
            }
            // Persist the attempt before querying balances, including across process death.
            for (record in mutableState.value.transfers.filter { it.status == TransferStatus.CONFIRMED && !it.balanceUpdateAttempted }) {
                if (mutableState.value.busy) break
                mutableState.update { it.copy(busy = true) }
                try {
                    mutableState.update { it.copy(transfers = it.transfers.map { r -> if (r.txid == record.txid) r.copy(balanceUpdateAttempted = true) else r }) }; persist()
                    mutableState.value.accounts.find { it.id == record.accountId }?.let { freshAccount(it); refreshQuotes() }
                }
                catch (e: CancellationException) { throw e } catch (e: Exception) { reportMessage("Transaction confirmed; balance refresh failed: ${e.message}") }
                finally { mutableState.update { it.copy(busy = false) } }
            }
        } finally { statusMutex.unlock() }
    }
    private fun saveAsync() { viewModelScope.launch { try { persist() } catch (e: Exception) { mutableState.update { it.copy(message = e.message) } } } }
    override fun onCleared() { foregroundJob?.cancel(); transport?.close(); engine.close(); super.onCleared() }
}
