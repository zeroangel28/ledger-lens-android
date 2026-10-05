// Public deterministic test vectors only. Never use these seeds on a real wallet.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const E = require('./build/engine.cjs');
const ethers = require('ethers');
const bitcoin = require('bitcoinjs-lib');
const C = require('@emurgo/cardano-serialization-lib-nodejs');
const bip32 = require('bip32').BIP32Factory(require('@bitcoinerlab/secp256k1'));
const Transport = require('@ledgerhq/hw-transport').default;
const key = new ethers.SigningKey('0x' + '01'.repeat(32));
const account = { id: 'fixture-eth', chain: 'ETH', path: "44'/60'/0'/0/0", publicKey: key.publicKey.slice(2), address: ethers.computeAddress(key.publicKey) };
const to = '0x0000000000000000000000000000000000000001';
const ethData = { balance: '1000000000000000000', tokenBalance: '25000000', nonce: '1', gasLimit: '65000', maxFeePerGas: '3000000000', maxPriorityFeePerGas: '1000000000' };
const eth = { account, to, amount: '10000000000000000', maximum: false, contract: null, data: ethData };
const usdt = { ...eth, contract: '0xdac17f958d2ee523a2206206994597c13d831ec7', amount: '1500000' };
function tronAddress(address) {
  const b = Buffer.from('41' + address.slice(2), 'hex');
  return ethers.encodeBase58(Buffer.concat([b, Buffer.from(ethers.getBytes(ethers.sha256(ethers.getBytes(ethers.sha256(b))))).subarray(0, 4)]));
}
const tronAccount = { ...account, id: 'fixture-tron', chain: 'TRON', path: "44'/195'/0'/0/0", address: tronAddress(account.address) };
const tron = { account: tronAccount, to: tronAddress(to), amount: '1000000', data: { balance: '100000000', tokenBalance: '25000000', feeBudget: '10000000', timestamp: Date.now(), blockNumber: 100, blockId: '01'.repeat(32) } };
const tronUsdt = { ...tron, contract: 'TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t' };
const node = bip32.fromSeed(Buffer.alloc(32, 7)).derivePath("m/84'/0'/0'");
const child = node.derive(0).derive(0), btcAddress = bitcoin.payments.p2wpkh({ pubkey: child.publicKey }).address;
const prev = new bitcoin.Transaction(); prev.addInput(Buffer.alloc(32, 5), 0); prev.addOutput(bitcoin.address.toOutputScript(btcAddress), 1000000);
const btc = { account: { id: 'fixture-btc', chain: 'BTC', path: "84'/0'/0'", publicKey: node.neutered().toBase58(), address: btcAddress },
  to: bitcoin.payments.p2wpkh({ pubkey: node.derive(0).derive(2).publicKey }).address, amount: '200000', data: { feeRate: '5', changeIndex: 0,
    utxos: [{ txid: prev.getId(), vout: 0, value: '1000000', rawTx: prev.toHex(), branch: 0, index: 0 }] } };
const adaKey = C.Bip32PrivateKey.from_bip39_entropy(Buffer.alloc(16, 8), Buffer.alloc(0)).derive(0x8000073c).derive(0x80000717).derive(0x80000000);
function adaAddress(branch, index) { return C.BaseAddress.new(1, C.Credential.from_keyhash(adaKey.derive(branch).derive(index).to_raw_key().to_public().hash()), C.Credential.from_keyhash(adaKey.derive(2).derive(0).to_raw_key().to_public().hash())).to_address().to_bech32(); }
const NIGHT = '0691b2fecca1ac4f53cb6dfb00b7013e561d1f34403b957cbb5af1fa4e49474854';
const OTHER = 'ab'.repeat(28) + '01';
const ada = { account: { id: 'fixture-ada', chain: 'ADA', path: "1852'/1815'/0'", publicKey: Buffer.from(adaKey.to_public().as_bytes()).toString('hex'), address: adaAddress(0, 0) },
  to: adaAddress(0, 3), amount: '5000000', data: { slot: 170000000, params: { min_fee_a: 44, min_fee_b: 155381, coins_per_utxo_size: '4310', pool_deposit: '500000000', key_deposit: '2000000', max_val_size: 5000, max_tx_size: 16384 },
    utxos: [{ txid: '11'.repeat(32), vout: 0, address: adaAddress(0, 0), branch: 0, index: 0, value: '20000000', tokens: { [NIGHT]: '10000000', [OTHER]: '9' } }] } };
const night = { ...ada, contract: NIGHT, amount: '1500000' };

test('ETH and USDT pin chain, exact decimals, EIP1559 fee reserve and maximum', () => {
  const a = E.build(eth), tx = ethers.Transaction.from(a.unsigned);
  assert.equal(tx.chainId, 1n); assert.equal(tx.value, 10000000000000000n); assert.equal(tx.to.toLowerCase(), to);
  const max = E.build({ ...eth, maximum: true }); assert.equal(BigInt(max.amount) + BigInt(max.fee), BigInt(ethData.balance));
  const t = ethers.Transaction.from(E.build(usdt).unsigned); assert.equal(t.value, 0n);
  assert.equal(t.to.toLowerCase(), usdt.contract); assert.equal(t.data, '0xa9059cbb' + to.slice(2).padStart(64, '0') + '16e360'.padStart(64, '0'));
  assert.throws(() => E.build({ ...usdt, data: { ...ethData, balance: '1' } }), /Insufficient/);
  assert.throws(() => E.build({ ...usdt, contract: '0x' + '03'.repeat(20) }), /Unsupported/);
});
test('BTC verifies prevout ID, ownership and amount and reserves change fees', () => {
  const a = E.build(btc), psbt = bitcoin.Psbt.fromHex(a.unsigned);
  assert.equal(psbt.txOutputs[0].value, 200000);
  assert.equal(psbt.txOutputs[0].value + psbt.txOutputs[1].value + Number(a.fee), 1000000);
  const max = E.build({ ...btc, maximum: true }); assert.equal(bitcoin.Psbt.fromHex(max.unsigned).txOutputs.length, 1);
  assert.equal(BigInt(max.amount) + BigInt(max.fee), 1000000n);
  for (const change of [{ value: '1000001' }, { index: 1 }, { txid: '00'.repeat(32) }]) {
    assert.throws(() => E.build({ ...btc, data: { ...btc.data, utxos: [{ ...btc.data.utxos[0], ...change }] } }), /mismatch/);
  }
});
test('ADA and NIGHT preserve every other asset and separate attached ADA from fee', () => {
  for (const request of [ada, night, { ...night, maximum: true }, { ...ada, maximum: true }]) {
    const p = E.build(request), body = C.TransactionBody.from_hex(p.unsigned), outputs = body.outputs();
    assert.equal(body.inputs().len(), 1); assert.equal(body.fee().to_str(), p.fee);
    let totalAda = BigInt(p.fee), totalNight = 0n, totalOther = 0n;
    for (let i = 0; i < outputs.len(); i++) {
      const value = outputs.get(i).amount(); totalAda += BigInt(value.coin().to_str());
      const multi = value.multiasset();
      if (multi) { for (const [unit, add] of [[NIGHT, n => totalNight += n], [OTHER, n => totalOther += n]]) {
        const assets = multi.get(C.ScriptHash.from_hex(unit.slice(0, 56))); if (assets) { const v = assets.get(C.AssetName.new(Buffer.from(unit.slice(56), 'hex'))); if (v) add(BigInt(v.to_str())); }
      } }
    }
    assert.equal(totalAda, 20000000n); assert.equal(totalNight, 10000000n); assert.equal(totalOther, 9n);
    if (request.contract) assert(BigInt(p.extraNative) > 0n); else assert.equal(p.extraNative, '0');
    assert.equal(C.FixedTransaction.new_from_body_bytes(body.to_bytes()).transaction_hash().to_hex(), p.txHash);
  }
  assert.throws(() => E.build({ ...ada, amount: '1' }), /minimum ADA/);
  assert.throws(() => E.build({ ...ada, data: { ...ada.data, utxos: [{ ...ada.data.utxos[0], index: 1 }] } }), /ownership/);
});
test('TRON uses transfer-only protobuf, exact contract, TAPOS and token fee reserve', () => {
  for (const request of [tron, tronUsdt]) {
    const p = E.build(request); assert.equal(ethers.sha256('0x' + p.unsigned).slice(2), p.transaction.txID);
    assert.equal(p.transaction.raw_data.ref_block_bytes, '0064');
    assert.equal(p.transaction.raw_data.contract[0].type, request.contract ? 'TriggerSmartContract' : 'TransferContract');
    if (request.contract) assert(p.transaction.raw_data.contract[0].parameter.value.data.startsWith('a9059cbb'));
  }
  assert.throws(() => E.build({ ...tron, data: { ...tron.data, timestamp: 1 } }), /stale/);
});
class EthFixture extends Transport {
  constructor(prepared, corrupt = false, reject = false) { super(); this.prepared = prepared; this.corrupt = corrupt; this.reject = reject; this.instructions = []; this.chunks = []; }
  setScrambleKey() {}
  async exchange(apdu) {
    this.instructions.push(apdu[1]);
    if (apdu[1] === 2) return Buffer.concat([Buffer.from([65]), Buffer.from(account.publicKey, 'hex'), Buffer.from([40]), Buffer.from(account.address.slice(2)), Buffer.from('9000', 'hex')]);
    if (apdu[1] === 0x0a) return Buffer.from('9000', 'hex');
    assert.equal(apdu[1], 4);
    if (this.reject) return Buffer.from('6985', 'hex');
    const payload = apdu.subarray(5); this.chunks.push(payload.subarray(apdu[2] === 0 ? 1 + payload[0] * 4 : 0));
    const signature = (this.corrupt ? new ethers.SigningKey('0x' + '02'.repeat(32)) : key).sign(ethers.Transaction.from(this.prepared.unsigned).unsignedHash);
    return Buffer.concat([Buffer.from([signature.yParity]), Buffer.from(signature.r.slice(2) + signature.s.slice(2), 'hex'), Buffer.from('9000', 'hex')]);
  }
}
test('ETH validates Ledger signature and signed metadata; wrong signature/rejection never yields broadcastable result', async () => {
  for (const request of [eth, usdt]) {
    const p = E.build(request), transport = new EthFixture(p), result = await E.sign(p, transport);
    assert.equal(ethers.Transaction.from(result.raw).from, account.address);
    assert.equal(ethers.keccak256(result.raw), result.txid);
    assert.equal(transport.instructions.includes(0x0a), !!request.contract);
    await assert.rejects(E.sign(p, new EthFixture(p, true)), /signature/);
    await assert.rejects(E.sign(p, new EthFixture(p, false, true)));
  }
});
test('Expired draft cannot request a Ledger signature', async () => {
  const p = { ...E.build(eth), expiresAt: 1 }; const transport = new EthFixture(p);
  await assert.rejects(E.sign(p, transport), /expired/); assert.equal(transport.instructions.length, 0);
});
class TronFixture extends Transport {
  constructor(prepared, corrupt = false) { super(); this.prepared = prepared; this.corrupt = corrupt; this.instructions = []; }
  setScrambleKey() {}
  async exchange(apdu) {
    this.instructions.push(apdu[1]);
    if (apdu[1] === 2) return Buffer.concat([Buffer.from([65]), Buffer.from(account.publicKey, 'hex'), Buffer.from([34]), Buffer.from(tronAccount.address), Buffer.from('9000', 'hex')]);
    if (apdu[1] === 6) return Buffer.from('000106009000', 'hex');
    assert.equal(apdu[1], 4);
    if (![0x10, 0x90].includes(apdu[2])) return Buffer.from('9000', 'hex');
    const signature = (this.corrupt ? new ethers.SigningKey('0x' + '02'.repeat(32)) : key).sign('0x' + this.prepared.transaction.txID);
    return Buffer.concat([Buffer.from(signature.r.slice(2) + signature.s.slice(2), 'hex'), Buffer.from([signature.yParity]), Buffer.from('9000', 'hex')]);
  }
}
test('TRX and TRC20 USDT verify full-transaction Ledger signatures and never use blind-hash APDU', async () => {
  for (const request of [tron, tronUsdt]) {
    const p = E.build(request), transport = new TronFixture(p), signed = await E.sign(p, transport);
    assert.equal(JSON.parse(signed.raw).signature[0].length, 130); assert.equal(signed.txid, p.transaction.txID);
    assert(!transport.instructions.includes(5));
    await assert.rejects(E.sign(p, new TronFixture(p, true)), /signature/);
  }
});
class BtcFixture extends Transport {
  constructor(p, corrupt = false) { super(); this.prepared = p; this.corrupt = corrupt; }
  async exchange(apdu) {
    if (apdu[0] === 0xf8) { assert.equal(apdu[1], 1); return Buffer.from('9000', 'hex'); }
    if (apdu[1] === 5) return Buffer.concat([bip32.fromSeed(Buffer.alloc(32, 7)).fingerprint, Buffer.from('9000', 'hex')]);
    if (apdu[1] === 0) return Buffer.concat([Buffer.from(btc.account.publicKey), Buffer.from('9000', 'hex')]);
    assert.equal(apdu[1], 4);
    const psbt = bitcoin.Psbt.fromHex(this.prepared.unsigned); psbt.signInput(0, child);
    const sig = Buffer.from(psbt.data.inputs[0].partialSig[0].signature); if (this.corrupt) sig[10] ^= 1;
    return Buffer.concat([Buffer.from([0x10, 0, 33]), child.publicKey, sig, Buffer.from('e000', 'hex')]);
  }
}
test('BTC official PSBTv2 signer handles yielded signature, verifies all inputs and final fee', async () => {
  const p = E.build(btc), result = await E.sign(p, new BtcFixture(p)), tx = bitcoin.Transaction.fromHex(result.raw);
  assert.equal(result.txid, tx.getId()); assert.equal(tx.ins[0].witness.length, 2);
  await assert.rejects(E.sign(p, new BtcFixture(p, true)), /Invalid|signature/);
});
class AdaFixture extends Transport {
  constructor(prepared, major, corrupt = false, wrongHash = false) { super(); this.prepared = prepared; this.major = major; this.corrupt = corrupt; this.wrongHash = wrongHash; }
  async exchange(apdu) {
    assert.equal(apdu[0], 0xd7);
    if (apdu[1] === 0) return Buffer.from([this.major, this.major === 7 ? 1 : 0, 0, 0, 0x90, 0]);
    assert.equal(apdu[1], 0x21);
    if (apdu[2] === (this.major === 7 ? 10 : 0x12)) return Buffer.concat([Buffer.from(this.wrongHash ? '22'.repeat(32) : this.prepared.txHash, 'hex'), Buffer.from('9000', 'hex')]);
    if (apdu[2] === 15) {
      const data = apdu.subarray(5); const path = Array.from({ length: data[0] }, (_, i) => data.readUInt32BE(1 + i * 4));
      const signing = adaKey.derive(path.at(-2)).derive(path.at(-1)).to_raw_key();
      const sig = Buffer.from(signing.sign(Buffer.from(this.prepared.txHash, 'hex')).to_bytes()); if (this.corrupt) sig[5] ^= 1;
      return Buffer.concat([sig, Buffer.from('9000', 'hex')]);
    }
    return Buffer.from('9000', 'hex');
  }
}
test('ADA and NIGHT official v7/v8 adapters verify transaction hash and every Ed25519 witness', async () => {
  for (const request of [ada, night]) for (const major of [7, 8]) {
    const p = E.build(request), signed = await E.sign(p, new AdaFixture(p, major)), tx = C.Transaction.from_hex(signed.raw);
    assert.equal(signed.txid, p.txHash); assert.equal(tx.witness_set().vkeys().len(), 1);
    const w = tx.witness_set().vkeys().get(0); assert(w.vkey().public_key().verify(Buffer.from(p.txHash, 'hex'), w.signature()));
    await assert.rejects(E.sign(p, new AdaFixture(p, major, true)), /witness/);
    await assert.rejects(E.sign(p, new AdaFixture(p, major, false, true)), /hash mismatch/);
  }
});
test('recipient rejects checksum damage, testnet, reward address and wrong networks', () => {
  assert.throws(() => E.validateRecipient('TRON', tron.to.slice(0, -1) + '2'));
  assert.throws(() => E.validateRecipient('BTC', bitcoin.payments.p2wpkh({ pubkey: child.publicKey, network: bitcoin.networks.testnet }).address));
  const reward = C.RewardAddress.new(1, C.Credential.from_keyhash(adaKey.derive(2).derive(0).to_raw_key().to_public().hash())).to_address().to_bech32();
  assert.throws(() => E.validateRecipient('ADA', reward));
});
async function verifyThroughNativeBridge(payload, transport) {
  let completed;
  global.NativeLedger = {
    exchange(id, apdu) { transport.exchange(Buffer.from(apdu, 'hex')).then(response => global.ledgerReply(id, response.toString('hex'), ''), error => global.ledgerReply(id, '', error.message)); },
    complete(id, result, error) { completed = { result, error }; }
  };
  try { await global.runLedgerOperation('address-test', 'verifyAddress', payload); return completed; }
  finally { delete global.NativeLedger; }
}
test('BTC device address display uses selected receive index and rejects a different returned address', async () => {
  const index = 3, address = bitcoin.payments.p2wpkh({ pubkey: node.derive(0).derive(index).publicKey }).address;
  class AddressFixture extends Transport {
    constructor(wrong = false) { super(); this.wrong = wrong; this.displayed = false; }
    async exchange(apdu) {
      if (apdu[1] === 5) return Buffer.concat([bip32.fromSeed(Buffer.alloc(32, 7)).fingerprint, Buffer.from('9000', 'hex')]);
      if (apdu[1] === 0) return Buffer.concat([Buffer.from(btc.account.publicKey), Buffer.from('9000', 'hex')]);
      assert.equal(apdu[1], 3); assert.equal(apdu[5], 1); assert.equal(apdu.at(-5), 0);
      assert.equal(apdu.readUInt32BE(apdu.length - 4), index); this.displayed = true;
      return Buffer.concat([Buffer.from(this.wrong ? btc.account.address : address), Buffer.from('9000', 'hex')]);
    }
  }
  const fixture = new AddressFixture(), payload = { ...btc.account, receiveIndex: index, address };
  const result = await verifyThroughNativeBridge(payload, fixture);
  assert.equal(result.error, ''); assert.equal(JSON.parse(result.result), address); assert(fixture.displayed);
  assert.match((await verifyThroughNativeBridge(payload, new AddressFixture(true))).error, /different address/);
  assert.match((await verifyThroughNativeBridge({ ...payload, receiveIndex: -1 }, new AddressFixture())).error, /index/);
});
test('Cardano v7/v8 display and return the selected payment path with the original stake path', async () => {
  const index = 2;
  const stake = adaKey.derive(2).derive(0).to_raw_key().to_public().hash();
  const selected = C.BaseAddress.new(1, C.Credential.from_keyhash(adaKey.derive(0).derive(index).to_raw_key().to_public().hash()), C.Credential.from_keyhash(stake)).to_address();
  class AddressFixture extends Transport {
    constructor(major) { super(); this.major = major; this.displayed = false; }
    setScrambleKey() {}
    async exchange(apdu) {
      if (apdu[1] === 0) return Buffer.from([this.major, this.major === 7 ? 1 : 0, 0, 0, 0x90, 0]);
      assert.equal(apdu[1], 0x11);
      const spending = Buffer.alloc(21); spending[0] = 5;
      [0x8000073c, 0x80000717, 0x80000000, 0, index].forEach((value, i) => spending.writeUInt32BE(value, 1 + i * 4));
      assert(apdu.subarray(5).includes(spending));
      if (apdu[2] === 2) { this.displayed = true; return Buffer.from('9000', 'hex'); }
      assert.equal(apdu[2], 1);
      return Buffer.concat([Buffer.from(selected.to_bytes()), Buffer.from('9000', 'hex')]);
    }
  }
  for (const major of [7, 8]) {
    const fixture = new AddressFixture(major), result = await verifyThroughNativeBridge({ ...ada.account, receiveIndex: index, address: selected.to_bech32() }, fixture);
    assert.equal(result.error, ''); assert.equal(JSON.parse(result.result), selected.to_bech32()); assert(fixture.displayed);
  }
});
// Only public requests are shipped to instrumented tests, never the fixture secret keys above.
fs.mkdirSync(path.join(__dirname, '../app/src/androidTest/assets'), { recursive: true });
fs.writeFileSync(path.join(__dirname, '../app/src/androidTest/assets/transaction-fixtures.json'), JSON.stringify([eth, usdt, tron, tronUsdt, btc, ada, night]));
fs.writeFileSync(path.join(__dirname, '../app/src/androidTest/assets/eth-signature-fixtures.json'), JSON.stringify([eth, usdt].map(request => {
  const p = E.build(request), sig = key.sign(ethers.Transaction.from(p.unsigned).unsignedHash);
  return { account, request, signature: sig.yParity.toString(16).padStart(2, '0') + sig.r.slice(2) + sig.s.slice(2) };
})));
