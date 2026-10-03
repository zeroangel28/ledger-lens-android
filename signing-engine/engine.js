// Offline codec/signers. No private key is requested or supplied by the Android bridge.
import { Buffer } from 'buffer';
import Transport from '@ledgerhq/hw-transport';
import Eth from '@ledgerhq/hw-app-eth';
import Trx from '@ledgerhq/hw-app-trx';
import { AppClient } from '@ledgerhq/hw-app-btc/lib/newops/appClient';
import { WalletPolicy, createKey } from '@ledgerhq/hw-app-btc/lib/newops/policy';
import { PsbtV2 } from '@ledgerhq/psbtv2';
import Ada from '@cardano-foundation/ledgerjs-hw-app-cardano';
import * as C from '@emurgo/cardano-serialization-lib-browser';
import * as bitcoin from 'bitcoinjs-lib';
import { BIP32Factory } from 'bip32';
import * as secp from '@bitcoinerlab/secp256k1';
import { Transaction, Signature, Interface, getAddress, recoverAddress, sha256, decodeBase58, encodeBase58, getBytes, hexlify } from 'ethers';
import usdtMetadata from './eth-usdt-metadata.json';

const fail = message => { throw new Error(message); };
const requireThat = (condition, message) => { if (!condition) fail(message); };
const bn = n => C.BigNum.from_str(String(n));
const hex = bytes => Buffer.from(bytes).toString('hex');
const bytes = value => Buffer.from(value, 'hex');
const NIGHT = '0691b2fecca1ac4f53cb6dfb00b7013e561d1f34403b957cbb5af1fa4e49474854';
const ETH_USDT = '0xdac17f958d2ee523a2206206994597c13d831ec7';
const TRON_USDT = 'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t';
const abi = new Interface(['function transfer(address,uint256)','function balanceOf(address) view returns (uint256)']);
const paths = path => path.replace(/^m\//, '').split('/').map(x => Number(x.replace("'", '')) + (x.endsWith("'") ? 0x80000000 : 0));
const safeNumber = value => { const n = Number(value); requireThat(Number.isSafeInteger(n) && n >= 0, 'Amount exceeds exact integer range'); return n; };
const bip32 = BIP32Factory(secp);
bitcoin.initEccLib(secp);

function tronBytes(address) {
  const raw = bytes(decodeBase58(address).toString(16).padStart(50, '0'));
  requireThat(raw.length === 25 && raw[0] === 0x41, 'Not a TRON mainnet address');
  const checksum = getBytes(sha256(getBytes(sha256(raw.subarray(0, 21))))).subarray(0, 4);
  requireThat(raw.subarray(21).equals(Buffer.from(checksum)), 'Invalid TRON address checksum');
  return raw.subarray(0, 21);
}
function tronAddress(pubkey) {
  const address = bytes('41' + recoverEthAddress(pubkey).slice(2));
  return encodeBase58(Buffer.concat([address, Buffer.from(getBytes(sha256(getBytes(sha256(address))))).subarray(0, 4)]));
}
// Ethers computes an Ethereum public-key address without signing.
import { computeAddress } from 'ethers';
const recoverEthAddress = pubkey => computeAddress('0x' + pubkey).toLowerCase();
function accountNode(account) {
  const text = account.publicKey;
  try { return bip32.fromBase58(text); }
  catch {
    // Ledger imports can use zpub serialization; derive the identical public node.
    const raw = bytes(decodeBase58(text).toString(16));
    requireThat(raw.length === 82 && ['04b24746', '049d7cb2'].includes(hex(raw.subarray(0, 4))), 'Unsupported account xpub');
    requireThat(hex(raw.subarray(78)) === sha256(getBytes(sha256(raw.subarray(0, 78)))).slice(2, 10), 'Invalid xpub checksum');
    raw.writeUInt32BE(0x0488b21e, 0);
    const body = raw.subarray(0, 78);
    return bip32.fromBase58(encodeBase58(Buffer.concat([body, Buffer.from(getBytes(sha256(getBytes(sha256(body))))).subarray(0, 4)])));
  }
}
function adaOwned(account, branch, index) {
  const node = C.Bip32PublicKey.from_bytes(bytes(account.publicKey));
  const pub = node.derive(branch).derive(index).to_raw_key();
  const stake = node.derive(2).derive(0).to_raw_key();
  const address = C.BaseAddress.new(1, C.Credential.from_keyhash(pub.hash()), C.Credential.from_keyhash(stake.hash())).to_address();
  return { address: address.to_bech32(), pub };
}
export function validateRecipient(chain, address) {
  requireThat(typeof address === 'string' && address.length < 200, 'Invalid address');
  if (chain === 'ETH') return getAddress(address);
  if (chain === 'TRON') { tronBytes(address); return address; }
  if (chain === 'BTC') { bitcoin.address.toOutputScript(address, bitcoin.networks.bitcoin); return address; }
  const value = C.Address.from_bech32(address);
  requireThat(value.network_id() === 1 && !C.RewardAddress.from_address(value), 'Use a Cardano mainnet payment address');
  return value.to_bech32();
}
export function buildEthereum(request) {
  const { account, to, data } = request;
  requireThat(!request.contract || request.contract.toLowerCase() === ETH_USDT, 'Unsupported Ethereum token');
  const token = !!request.contract;
  const gas = BigInt(data.gasLimit), cap = BigInt(data.maxFeePerGas);
  const fee = gas * cap;
  const balance = BigInt(data.balance), tokenBalance = BigInt(data.tokenBalance || '0');
  let amount = request.maximum ? (token ? tokenBalance : balance - fee) : BigInt(request.amount);
  requireThat(amount > 0n && (token ? amount <= tokenBalance && balance >= fee : amount + fee <= balance), 'Insufficient balance for amount and network fee');
  requireThat(gas >= 21000n && cap > 0n && BigInt(data.maxPriorityFeePerGas) <= cap, 'Invalid Ethereum fee estimate');
  const recipient = validateRecipient('ETH', to);
  requireThat(recoverEthAddress(account.publicKey) === account.address.toLowerCase(), 'Account public key mismatch');
  const tx = Transaction.from({ type: 2, chainId: 1, nonce: safeNumber(data.nonce), gasLimit: gas, maxFeePerGas: cap,
    maxPriorityFeePerGas: BigInt(data.maxPriorityFeePerGas), to: token ? ETH_USDT : recipient, value: token ? 0n : amount,
    data: token ? abi.encodeFunctionData('transfer', [recipient, amount]) : '0x', accessList: [] });
  return { chain: 'ETH', account, to: recipient, amount: amount.toString(), fee: fee.toString(), extraNative: '0',
    feeSymbol: 'ETH', expiresAt: Date.now() + 300000, unsigned: tx.unsignedSerialized, txData: data };
}
function varint(value) { let n = BigInt(value); requireThat(n >= 0n, 'Negative protobuf integer'); const out = []; do { out.push(Number(n & 127n) | (n > 127n ? 128 : 0)); n >>= 7n; } while (n); return Buffer.from(out); }
function field(number, value, integer = false) {
  return Buffer.concat([varint(number * 8 + (integer ? 0 : 2)), integer ? varint(value) : Buffer.concat([varint(value.length), value])]);
}
export function buildTron(request) {
  const { account, to, data } = request;
  requireThat(!request.contract || request.contract === TRON_USDT, 'Unsupported TRON token');
  const token = !!request.contract, fee = BigInt(data.feeBudget), balance = BigInt(data.balance);
  const amount = request.maximum ? (token ? BigInt(data.tokenBalance) : balance - fee) : BigInt(request.amount);
  requireThat(amount > 0n && balance >= fee + (token ? 0n : amount) && (!token || amount <= BigInt(data.tokenBalance)), 'Insufficient TRX or token balance');
  requireThat(tronAddress(account.publicKey) === account.address, 'Account public key mismatch');
  const owner = tronBytes(account.address), recipient = tronBytes(validateRecipient('TRON', to));
  const callData = token ? bytes('a9059cbb' + hex(recipient.subarray(1)).padStart(64, '0') + amount.toString(16).padStart(64, '0')) : null;
  const contract = token ? Buffer.concat([field(1, owner), field(2, tronBytes(TRON_USDT)), field(4, callData)])
    : Buffer.concat([field(1, owner), field(2, recipient), field(3, amount, true)]);
  const contractName = token ? 'TriggerSmartContract' : 'TransferContract';
  const any = Buffer.concat([field(1, Buffer.from('type.googleapis.com/protocol.' + contractName)), field(2, contract)]);
  const wrapped = Buffer.concat([field(1, token ? 31 : 1, true), field(2, any)]);
  const timestamp = safeNumber(data.timestamp), expiration = timestamp + 600000;
  requireThat(Math.abs(Date.now() - timestamp) < 180000 && bytes(data.blockId).length === 32, 'TRON block reference is stale');
  const refBytes = bytes(BigInt(data.blockNumber).toString(16).padStart(16, '0').slice(-4));
  const refHash = bytes(data.blockId).subarray(8, 16);
  const raw = Buffer.concat([field(1, refBytes), field(4, refHash), field(8, expiration, true), field(11, wrapped), field(14, timestamp, true), ...(token ? [field(18, fee, true)] : [])]);
  const value = token ? { owner_address: hex(owner), contract_address: hex(tronBytes(TRON_USDT)), data: hex(callData) }
    : { owner_address: hex(owner), to_address: hex(recipient), amount: safeNumber(amount) };
  const raw_data = { ref_block_bytes: hex(refBytes), ref_block_hash: hex(refHash), expiration, timestamp,
    contract: [{ type: contractName, parameter: { type_url: 'type.googleapis.com/protocol.' + contractName, value } }], ...(token ? { fee_limit: safeNumber(fee) } : {}) };
  return { chain: 'TRON', account, to, amount: amount.toString(), fee: fee.toString(), extraNative: '0', feeSymbol: 'TRX', expiresAt: Math.min(expiration, Date.now() + 300000),
    unsigned: hex(raw), transaction: { raw_data, raw_data_hex: hex(raw), txID: sha256(raw).slice(2), visible: false } };
}
export function buildBitcoin(request) {
  const { account, data } = request;
  const node = accountNode(account), recipient = validateRecipient('BTC', request.to);
  requireThat(bitcoin.payments.p2wpkh({ pubkey: node.derive(0).derive(0).publicKey }).address === account.address, 'Bitcoin source address mismatch');
  const script = bitcoin.address.toOutputScript(recipient), rate = BigInt(data.feeRate);
  requireThat(rate > 0n, 'Invalid Bitcoin fee rate');
  const candidates = data.utxos.map(u => {
    const child = node.derive(u.branch).derive(u.index);
    const output = bitcoin.payments.p2wpkh({ pubkey: child.publicKey }).output;
    const previous = bitcoin.Transaction.fromHex(u.rawTx);
    requireThat(previous.getId() === u.txid && previous.outs[u.vout]?.value === safeNumber(u.value) && previous.outs[u.vout].script.equals(output), 'Bitcoin UTXO ownership or value mismatch');
    return { ...u, child, output };
  }).sort((a, b) => Number(BigInt(b.value) - BigInt(a.value)));
  requireThat(new Set(candidates.map(u => `${u.txid}:${u.vout}`)).size === candidates.length, 'Duplicate Bitcoin UTXO');
  let total = 0n, fee = 0n, amount = BigInt(request.amount || '0'); const selected = [];
  const outputSize = 8 + 1 + script.length;
  for (const u of candidates) {
    selected.push(u); total += BigInt(u.value);
    fee = BigInt(12 + selected.length * 69 + outputSize + (request.maximum ? 0 : 31)) * rate;
    if (!request.maximum && total >= amount + fee) break;
  }
  if (request.maximum) amount = total - fee;
  requireThat(amount >= 546n && total >= amount + fee, 'Insufficient Bitcoin balance or dust output');
  const change = total - amount - fee;
  if (change > 0n && change < 294n) fee += change;
  const psbt = new bitcoin.Psbt(); psbt.setVersion(2);
  for (const u of selected) psbt.addInput({ hash: u.txid, index: u.vout, sequence: 0xffffffff, nonWitnessUtxo: bytes(u.rawTx),
    witnessUtxo: { script: u.output, value: safeNumber(u.value) } });
  psbt.addOutput({ address: recipient, value: safeNumber(amount) });
  const changeChild = node.derive(1).derive(data.changeIndex);
  if (change >= 294n) psbt.addOutput({ address: bitcoin.payments.p2wpkh({ pubkey: changeChild.publicKey }).address, value: safeNumber(change) });
  return { chain: 'BTC', account, to: recipient, amount: amount.toString(), fee: fee.toString(), extraNative: '0', feeSymbol: 'BTC', expiresAt: Date.now() + 300000,
    unsigned: psbt.toHex(), selected: selected.map(u => ({ txid: u.txid, vout: u.vout, branch: u.branch, index: u.index, value: u.value })), changeIndex: data.changeIndex, feeRate: data.feeRate };
}
function adaValue(coin, tokens = {}) {
  const value = C.Value.new(bn(coin)), multi = C.MultiAsset.new(), groups = new Map();
  for (const [unit, amount] of Object.entries(tokens)) {
    requireThat(/^[0-9a-f]{56,120}$/.test(unit) && unit.length % 2 === 0 && BigInt(amount) >= 0n, 'Invalid Cardano asset unit');
    const policy = unit.slice(0, 56); const assets = groups.get(policy) || C.Assets.new();
    assets.insert(C.AssetName.new(bytes(unit.slice(56))), bn(amount)); groups.set(policy, assets);
  }
  for (const [policy, assets] of groups) multi.insert(C.ScriptHash.from_hex(policy), assets);
  if (groups.size) value.set_multiasset(multi);
  return value;
}
function adaTokens(value) {
  const out = [], multi = value.multiasset(); if (!multi) return out;
  const policies = multi.keys();
  for (let i = 0; i < policies.len(); i++) {
    const policy = policies.get(i), assets = multi.get(policy), names = assets.keys(), tokens = [];
    for (let j = 0; j < names.len(); j++) { const name = names.get(j); tokens.push({ assetNameHex: hex(name.name()), amount: assets.get(name).to_str() }); }
    out.push({ policyIdHex: policy.to_hex(), tokens });
  }
  return out;
}
export function buildCardano(request) {
  const { account, data } = request, token = !!request.contract;
  requireThat(adaOwned(account, 0, 0).address === account.address, 'Cardano source address mismatch');
  requireThat(!token || request.contract.toLowerCase() === NIGHT, 'Unsupported Cardano token');
  const to = validateRecipient('ADA', request.to), params = data.params, cost = C.DataCost.new_coins_per_byte(bn(params.coins_per_utxo_size));
  const utxos = C.TransactionUnspentOutputs.new(), pathsByInput = new Map(); let total = 0n, tokenTotal = 0n;
  for (const u of data.utxos) {
    const owned = adaOwned(account, u.branch, u.index);
    requireThat(owned.address === u.address, 'Cardano UTXO ownership mismatch');
    const key = `${u.txid}:${u.vout}`; requireThat(!pathsByInput.has(key), 'Duplicate Cardano UTXO');
    pathsByInput.set(key, paths(account.path).concat([u.branch, u.index]));
    const input = C.TransactionInput.new(C.TransactionHash.from_hex(u.txid), u.vout);
    utxos.add(C.TransactionUnspentOutput.new(input, C.TransactionOutput.new(C.Address.from_bech32(u.address), adaValue(u.value, u.tokens))));
    total += BigInt(u.value); tokenTotal += BigInt(u.tokens?.[NIGHT] || '0');
  }
  const config = C.TransactionBuilderConfigBuilder.new().fee_algo(C.LinearFee.new(bn(params.min_fee_a), bn(params.min_fee_b)))
    .coins_per_utxo_byte(bn(params.coins_per_utxo_size)).pool_deposit(bn(params.pool_deposit)).key_deposit(bn(params.key_deposit))
    .max_value_size(Number(params.max_val_size)).max_tx_size(Number(params.max_tx_size)).do_not_burn_extra_change(true).build();
  function make(amount) {
    requireThat(amount > 0n, 'Amount must be positive');
    const value = adaValue(token ? 0 : amount, token ? { [NIGHT]: amount.toString() } : {});
    let output = C.TransactionOutput.new(C.Address.from_bech32(to), value);
    let minimum = 0n;
    for (let i = 0; i < 5; i++) { minimum = BigInt(C.min_ada_for_output(output, cost).to_str()); if (!token) break;
      if (BigInt(output.amount().coin().to_str()) >= minimum) break; const updated = output.amount(); updated.set_coin(bn(minimum)); output = C.TransactionOutput.new(C.Address.from_bech32(to), updated); }
    requireThat(BigInt(output.amount().coin().to_str()) >= minimum, 'Amount is below the required minimum ADA');
    const builder = C.TransactionBuilder.new(config); builder.set_ttl_bignum(bn(BigInt(data.slot) + 1800n));
    builder.add_output(output); builder.add_inputs_from(utxos, C.CoinSelectionStrategyCIP2.LargestFirstMultiAsset);
    builder.add_change_if_needed(C.Address.from_bech32(account.address));
    return { body: builder.build(), extra: token ? output.amount().coin().to_str() : '0' };
  }
  let amount = request.maximum && token ? tokenTotal : BigInt(request.amount || '0'), result;
  if (request.maximum && !token) {
    const smallest = C.TransactionOutput.new(C.Address.from_bech32(to), adaValue(1000000));
    let low = BigInt(C.min_ada_for_output(smallest, cost).to_str()), high = total, best = 0n;
    while (low <= high) { const mid = (low + high) / 2n; try { make(mid); best = mid; low = mid + 1n; } catch { high = mid - 1n; } }
    requireThat(best > 0n, 'Insufficient ADA for network fee and change'); amount = best;
  }
  result = make(amount); const body = result.body, tx = { network: { networkId: 1, protocolMagic: 764824073 }, inputs: [], outputs: [], fee: body.fee().to_str(), ttl: String(BigInt(data.slot) + 1800n) };
  const inputs = body.inputs();
  for (let i = 0; i < inputs.len(); i++) { const input = inputs.get(i), hash = input.transaction_id().to_hex(), index = input.index();
    const path = pathsByInput.get(`${hash}:${index}`); requireThat(path, 'Unknown Cardano transaction input'); tx.inputs.push({ txHashHex: hash, outputIndex: index, path }); }
  const outputs = body.outputs();
  for (let i = 0; i < outputs.len(); i++) {
    const output = outputs.get(i); const own = i !== 0 && output.address().to_bech32() === account.address;
    tx.outputs.push({ format: 0, amount: output.amount().coin().to_str(), tokenBundle: adaTokens(output.amount()),
      destination: own ? { type: 'device_owned', params: { type: 0, params: { spendingPath: paths(account.path).concat([0, 0]), stakingPath: paths(account.path).concat([2, 0]) } } }
      : { type: 'third_party', params: { addressHex: hex(output.address().to_bytes()) } } });
  }
  const hash = C.FixedTransaction.new_from_body_bytes(body.to_bytes()).transaction_hash().to_hex();
  return { chain: 'ADA', account, to, amount: amount.toString(), fee: body.fee().to_str(), extraNative: result.extra, feeSymbol: 'ADA', expiresAt: Date.now() + 300000,
    unsigned: body.to_hex(), txHash: hash, protocol: { minFeeA: String(params.min_fee_a), minFeeB: String(params.min_fee_b), maxTxSize: Number(params.max_tx_size) },
    ledgerRequest: { tx, signingMode: 'ordinary_transaction', options: { tagCborSets: true } } };
}
export function build(request) {
  return ({ ETH: buildEthereum, TRON: buildTron, BTC: buildBitcoin, ADA: buildCardano }[request.account.chain] || fail('Unsupported chain'))(request);
}

class AndroidTransport extends Transport {
  async exchange(apdu) { return new Promise((resolve, reject) => {
    const id = String(++sequence); replies.set(id, { resolve, reject }); globalThis.NativeLedger.exchange(id, apdu.toString('hex'));
  }); }
  async close() {}
  setScrambleKey() {}
}
let sequence = 0; const replies = new Map();
globalThis.ledgerReply = (id, response, error) => { const pending = replies.get(id); if (!pending) return; replies.delete(id);
  if (error) pending.reject(new Error(error)); else pending.resolve(bytes(response)); };
export async function sign(prepared, transport = new AndroidTransport()) {
  requireThat(Date.now() < prepared.expiresAt, 'Transaction estimate expired; prepare again');
  const { account } = prepared;
  if (prepared.chain === 'ETH') {
    const device = new Eth(transport), address = await device.getAddress(account.path, false);
    requireThat(address.address.toLowerCase() === account.address.toLowerCase(), 'Different Ledger account');
    const tx = Transaction.from(prepared.unsigned);
    if (tx.data !== '0x') requireThat(await device.provideERC20TokenInformation(usdtMetadata.data), 'Ledger rejected USDT display metadata');
    const resolution = { domains: [], plugin: [], externalPlugin: [], nfts: [], erc20Tokens: [] };
    const signature = await device.signTransaction(account.path, prepared.unsigned.slice(2), resolution);
    const parity = Number.parseInt(signature.v, 16);
    tx.signature = Signature.from({ r: '0x' + signature.r, s: '0x' + signature.s, yParity: parity >= 27 ? parity - 27 : parity });
    requireThat(recoverAddress(tx.unsignedHash, tx.signature).toLowerCase() === account.address.toLowerCase(), 'Ledger signature does not match source account');
    return { raw: tx.serialized, txid: tx.hash };
  }
  if (prepared.chain === 'TRON') {
    const device = new Trx(transport), address = await device.getAddress(account.path, false);
    requireThat(address.address === account.address, 'Different Ledger account');
    const config = await device.getAppConfiguration();
    requireThat(!config.truncateAddress, 'Disable truncated addresses in the Ledger TRON app before reviewing a transfer');
    // Canonical USDT is built into app-tron's token table. Name chunks are TRC10-only.
    const signature = await device.signTransaction(account.path, prepared.unsigned, []);
    const r = '0x' + signature.slice(0, 64), s = '0x' + signature.slice(64, 128), v = Number.parseInt(signature.slice(128), 16);
    requireThat(recoverAddress('0x' + prepared.transaction.txID, Signature.from({ r, s, yParity: v >= 27 ? v - 27 : v })).toLowerCase() === recoverEthAddress(account.publicKey), 'TRON signature does not match source account');
    return { raw: JSON.stringify({ ...prepared.transaction, signature: [signature] }), txid: prepared.transaction.txID };
  }
  if (prepared.chain === 'BTC') {
    const device = new AppClient(transport), fingerprint = await device.getMasterFingerprint(), node = accountNode(account);
    const xpub = await device.getExtendedPubkey(false, paths(account.path));
    requireThat(xpub === node.toBase58(), 'Different Ledger Bitcoin account');
    const policy = new WalletPolicy('wpkh(@0/**)', createKey(fingerprint, paths(account.path), xpub));
    const psbt = bitcoin.Psbt.fromHex(prepared.unsigned);
    prepared.selected.forEach((u, index) => { const pubkey = node.derive(u.branch).derive(u.index).publicKey;
      psbt.updateInput(index, { bip32Derivation: [{ masterFingerprint: fingerprint, pubkey, path: 'm/' + account.path + '/' + u.branch + '/' + u.index }] }); });
    if (psbt.txOutputs.length > 1) psbt.updateOutput(1, { bip32Derivation: [{ masterFingerprint: fingerprint,
      pubkey: node.derive(1).derive(prepared.changeIndex).publicKey, path: 'm/' + account.path + '/1/' + prepared.changeIndex }] });
    const signatures = await device.signPsbt(PsbtV2.fromV0(psbt.toBuffer()), policy, null, () => {});
    for (const [index, signature] of signatures) { const u = prepared.selected[index]; requireThat(u, 'Unknown Bitcoin signature input');
      psbt.updateInput(index, { partialSig: [{ pubkey: node.derive(u.branch).derive(u.index).publicKey, signature }] }); }
    requireThat(psbt.validateSignaturesOfAllInputs((pubkey, hash, signature) => secp.verify(hash, pubkey, signature)), 'Invalid Bitcoin input signature');
    psbt.finalizeAllInputs(); const tx = psbt.extractTransaction();
    requireThat(BigInt(prepared.fee) >= BigInt(tx.virtualSize()) * BigInt(prepared.feeRate), 'Signed transaction exceeds estimated fee budget');
    return { raw: tx.toHex(), txid: tx.getId() };
  }
  const device = new Ada(transport), signed = await device.signTransaction(prepared.ledgerRequest);
  requireThat(signed.txHashHex.toLowerCase() === prepared.txHash, 'Cardano device transaction hash mismatch');
  const witnesses = C.Vkeywitnesses.new(), expected = new Map();
  for (const input of prepared.ledgerRequest.tx.inputs) expected.set(input.path.join('/'), input.path);
  const seen = new Set();
  for (const witness of signed.witnesses) {
    const key = witness.path.join('/'); requireThat(expected.has(key) && !seen.has(key), 'Unexpected Cardano witness'); seen.add(key);
    const pub = adaOwned(account, witness.path.at(-2), witness.path.at(-1)).pub;
    const signature = C.Ed25519Signature.from_hex(witness.witnessSignatureHex);
    requireThat(pub.verify(bytes(prepared.txHash), signature), 'Invalid Cardano witness signature');
    witnesses.add(C.Vkeywitness.new(C.Vkey.new(pub), signature));
  }
  requireThat(seen.size === expected.size, 'Missing Cardano input witness');
  const witnessSet = C.TransactionWitnessSet.new(); witnessSet.set_vkeys(witnesses);
  const transaction = C.Transaction.new(C.TransactionBody.from_hex(prepared.unsigned), witnessSet);
  requireThat(transaction.to_bytes().length <= prepared.protocol.maxTxSize && BigInt(prepared.fee) >= BigInt(transaction.to_bytes().length) * BigInt(prepared.protocol.minFeeA) + BigInt(prepared.protocol.minFeeB), 'Signed Cardano transaction exceeds the fee or size estimate');
  return { raw: transaction.to_hex(), txid: prepared.txHash };
}
export async function verifyAddress(account, transport = new AndroidTransport()) {
  if (account.chain === 'ETH') return (await new Eth(transport).getAddress(account.path, true)).address;
  if (account.chain === 'TRON') return (await new Trx(transport).getAddress(account.path, true)).address;
  if (account.chain === 'BTC') {
    const device = new AppClient(transport), fingerprint = await device.getMasterFingerprint(), node = accountNode(account);
    const xpub = await device.getExtendedPubkey(false, paths(account.path)); requireThat(xpub === node.toBase58(), 'Different Ledger account');
    return device.getWalletAddress(new WalletPolicy('wpkh(@0/**)', createKey(fingerprint, paths(account.path), xpub)), null, 0, 0, true);
  }
  const device = new Ada(transport);
  await device.showAddress({ network: { networkId: 1, protocolMagic: 764824073 }, address: { type: 0, params: { spendingPath: paths(account.path).concat([0, 0]), stakingPath: paths(account.path).concat([2, 0]) } } });
  const result = await device.deriveAddress({ network: { networkId: 1, protocolMagic: 764824073 }, address: { type: 0, params: { spendingPath: paths(account.path).concat([0, 0]), stakingPath: paths(account.path).concat([2, 0]) } } });
  return C.Address.from_bytes(bytes(result.addressHex)).to_bech32();
}
globalThis.runLedgerOperation = async (id, operation, payload) => {
  try { const result = operation === 'build' ? build(payload) : operation === 'sign' ? await sign(payload) : operation === 'validate' ? { address: validateRecipient(payload.chain, payload.address) } : operation === 'verifyAddress' ? await verifyAddress(payload) : fail('Unknown engine operation');
    if (operation === 'verifyAddress') requireThat(payload.chain === 'ETH' ? result.toLowerCase() === payload.address.toLowerCase() : result === payload.address, 'Ledger displayed a different address');
    globalThis.NativeLedger.complete(id, JSON.stringify(result), '');
  } catch (error) { globalThis.NativeLedger.complete(id, '', String(error.message || error)); }
};
if (globalThis.NativeLedger?.ready) globalThis.NativeLedger.ready();
