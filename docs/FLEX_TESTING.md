# Physical Ledger Flex acceptance checklist

Hardware testing is still pending. Run with a real Flex and Android phone before calling this release production-ready. Use public test accounts; never disclose seeds or private keys in logs/issues.

1. Record Android version/phone model, Flex firmware and chain-app versions.
2. USB: use an OTG/data cable; grant/deny permission; verify reconnect after unplug, screen rotation and app restart. Unplug during an APDU and confirm a visible failure with no account saved.
3. BLE: grant/deny Android permissions; scan, pair and reconnect. Confirm notification setup/MTU exchange and multi-packet responses. Turn off Bluetooth mid-import and verify no partially imported account.
4. Open each chain app. Import indices 0 and 1, approve, then reject one request. Ensure rejection does not save an account. Reimporting the same public key/path is rejected.
5. BTC Native SegWit: compare account xpub and receive/change addresses with an independent trusted wallet. Verify a used zero-balance address does not terminate discovery. Check scan-cap warning.
6. ETH/TRON: compare displayed and locally derived addresses; verify multiple accounts and ERC-20/TRC-10/TRC-20 assets against explorers. Check empty/unactivated TRON accounts.
7. Cardano major 7/8: verify account xpub and first base address, device address display, multiple receive/change addresses and native asset quantities. Check Blockfrost key errors and unsupported firmware rejection.
8. Compare four native Spot USDT prices with Binance at their recorded timestamp. Unknown contracts must remain unpriced even if named USDT/BTC. Deny network access and confirm stale/error states preserve previous values.
9. Remove a token's entire balance and refresh: old token may remain with quantity 0 and respect zero filters. Hidden assets must remain in portfolio totals and be restorable.
10. Restart the app to confirm encrypted persistence of accounts, exact quantities, settings and API keys. Check that repository/APK contains no wallet-specific data or provider credentials.

The unit/emulator checks in `VALIDATION.md` do not substitute for these device checks.

## v0.2 send/receive checks

Repeat each flow on USB and BLE for BTC Native SegWit, ETH, TRX, ADA, Ethereum USDT, TRON USDT and Cardano NIGHT. Record firmware/app versions; use separately controlled minimal-value accounts.

1. Receive: compare imported address, QR payload, copied/shared text and device-displayed address. Check asset/network labels and Ledger rejection.
2. Send: verify paste/QR, exact decimals, maximum amount, fresh balance/fee data, insufficient native fees and full recipient. NIGHT attached ADA must be shown independently and unrelated Cardano tokens must return as change.
3. Confirm: only the explicit phone action starts signing. Compare device address, amount and token; reject once. No rejected or incorrectly verified signature may broadcast.
4. Disconnect/lock/change chain app during signing: show a failure; no automatic retry or transaction submission. Expired/edited drafts require fresh preparation and review.
5. After broadcast, show pending until first successful chain confirmation. Restart while pending; preserve transaction ID and prevent another unresolved transfer from the same account. Test an uncertain HTTP response; never automatically resubmit.
6. On first confirmation refresh source holdings once. Ordinary Cardano refresh remains daily; price-only refresh must not query Cardano holdings. Backgrounding stops quote/status timers.

Passing mock/emulator checks is not evidence that the physical device displayed or approved a transaction.
