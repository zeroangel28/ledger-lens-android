# Validation record

## v0.2.0 — 2026-10-03

- Debug build, JVM tests, Android Lint and instrumentation APK build succeeded. **33 JVM tests passed; 10 offline transaction/signature tests passed; 10 Android instrumentation tests passed** on the isolated API 36 emulator. Lint: **0 errors, 8 warnings**.
- Offline tests construct BTC, ETH, TRX, ADA, ERC-20 USDT, TRC-20 USDT and Cardano NIGHT transfers and exercise real cryptographic verification through official Ledger SDK adapters. Tests cover incorrect signatures, ownership, fees, BTC change and Cardano token conservation/minimum ADA. No user wallet keys or real transactions were used.
- Android tests exercise the actual offline WebView/WASM engine, Kotlin Ledger bridge, signing permits and the translated send/receive screens. All seven asset builders run on Android; mocked ETH/USDT approvals verify the native signing bridge. Cardano SDK v7/v8 is tested offline, not on physical hardware.
- Inspected receive QR/full address, NIGHT review with attached ADA separated from fees, and English light send form. Screenshots use public deterministic fixtures: [receive](screenshots/v020-receive.png), [NIGHT review](screenshots/v020-review.png), [send form](screenshots/v020-send-light.png). Screens scroll; the full address and explicit approval remain accessible.
- Market cache persists available/missing pairs for 24 hours. Foreground quotes batch only held, verified pairs every 30 seconds. Cardano ordinary holdings retain the daily limit; transaction preparation and first confirmation explicitly bypass it. Cache/error/single-submission regressions are covered by JVM tests.
- APK SHA-256: `3740621dfcb61f1b72508b9489b3f8855f0b59d673de365263cd558d7edf4094`. Debug signing certificate SHA-256: `d85236d6b2b4b426fd1929c514d3453240cf7062e6c7f9e5509a9c83e82a553a`, matching v0.1.1/v0.1.2 for an in-place update.
- **Physical Flex USB/BLE approval, rejection, disconnect and all seven asset end-to-end transfers remain pending. No mainnet funds were sent. This is an experimental debug build, not a security-audited production release.** Live network/provider availability and Android API 26–35 runtime need device checks.

## Historical v0.1 evidence

## v0.1.2 update, 2026-10-03

- `testDebugUnitTest lintDebug assembleDebug`: successful. **25 JVM tests passed**, 0 failures/errors; Lint **0 errors, 8 warnings**.
- New regression: Cardano NIGHT recovers a missing quote through NIGHTUSDT Spot; uppercase hex identifies the same asset; duplicate accounts share the pair request; a different policy, asset name or chain cannot obtain NIGHT's quote by copying its ticker.
- Live read-only Binance check: NIGHTUSDT reports `TRADING`, `isSpotTradingAllowed=true` and a positive ticker price. Cardano Foundation's token registry confirms the full NIGHT asset unit, ticker and six decimals. See `PUBLIC_API_V0.1.2.json` and `V0.1.2.md` for sources.
- APK certificate SHA-256 matches the delivered v0.1.1 build: `d85236d6b2b4b426fd1929c514d3453240cf7062e6c7f9e5509a9c83e82a553a`. APK SHA-256: `e85ae029b7129ce1ed7afa37fb43ce32ff67c3b4ae2d4cafadcde92a8e77bc44`.
- No UI, holdings lookup or cooldown logic changed; the existing v0.1.1 UI test evidence remains applicable to unchanged screens. This patch adds no Cardano API requests. Samsung installation is pending a running, authorized local ADB service.

## v0.1.1 update, 2026-10-03

- `testDebugUnitTest assembleDebug lintDebug assembleDebugAndroidTest`: successful using JDK 17 / Gradle 8.14 / SDK 35.
- **24 JVM tests passed**, including 13 provider/recovery regressions: correct ETH RPC host and native-balance retention; authenticated TRON reads, mainnet USDT identity and metadata-failure isolation; bounded HTTP 429 cooldown; BTC fallback and both branches; per-pair Spot queries, failed-pair isolation and invalid-market handling; unknown-value filters; retained tokens after partial discovery; Cardano cooldown persistence and upgrade migration; Koios address ownership and native assets using fixtures.
- **4 Android UI tests passed** on the isolated API 36 emulator: existing onboarding/language flow; unavailable TRON balance with a visible quote and Cardano next-sync time; saving the new TronGrid key; and an unknown-price total displayed as an em dash.
- Lint: **0 errors, 8 warnings**. APK signed with the same local debug key as v0.1.0 to support an in-place update.
- Inspected new recovery/settings screens. `screenshots/v011-test-*.png` contain test fixtures and fabricated prices, never user wallet data or live prices.
- Cardano uses a 24-hour interval per account, with the last attempt persisted before HTTP access. This limits synchronization attempts, not the number of calls within discovery. Price refresh remains independent; no scheduled background job is installed.
- Physical Flex compatibility, user-network provider availability and real account/key end-to-end validation remain pending. A passing fixture test does not demonstrate a public endpoint's current availability.
- Live read-only checks from the development machine: both Binance public hosts returned HTTP 200, trading Spot metadata and positive quotes for BTCUSDT/ETHUSDT/TRXUSDT/ADAUSDT; each scoped metadata response was about 2.2 KB. The old unfiltered exchange catalog exceeded the original 10,000,000-byte response limit, reproducing the price-loading failure. PublicNode's corrected ETH RPC returned a valid balance result for a public example address. A keyless TronGrid query for the public mainnet USDT contract returned account data; this does not establish availability for the user's phone/IP or eliminate the need for an API key. Koios tip returned HTTP 200. Exact check summary: `PUBLIC_API_V0.1.1.json`.

SHA-256 of the v0.1.1 debug APK:

`9d41860d19966e3beed149b1ef4434d691bd468bbdf5e10513c229a9daafc91f`

The original v0.1.0 record follows.

Verified 2026-10-03 (Asia/Taipei).

| Check | Result |
|---|---|
| Debug APK + instrumentation APK | Built successfully with JDK 17, Gradle 8.14, AGP 8.9.2 and SDK 35 |
| JVM unit suite | 11 passed; 0 failures/errors |
| Android Lint | Passed; 0 errors, 8 warnings (older dependency/target versions and newer manifest attributes) |
| Android UI instrumentation | 1 passed on an isolated Android API 36 x86_64 emulator |
| Portrait light/dark | Inspected onboarding, portfolio and settings at 1080×2400 px |
| Languages | Inspected Traditional Chinese, Simplified Chinese and English |
| Small display + large text | Inspected at 320×720 px / mdpi with font scale 1.6; lists scroll and asset values wrap |
| Landscape | Inspected at 1280×800 px / mdpi; central content limited to 840 dp |
| Motion disabled | Screens inspected with system window/transition/animator scales set to 0 |
| Physical Ledger Flex USB/BLE | **Pending hardware test** |
| Real four-chain holdings + provider credentials | **Pending account/key and device verification** |
| API 26–35 device runtime | **Pending**; min-SDK compatibility checked by Lint, runtime UI tested on API 36 |

The UI test exercises empty onboarding, scrolling to the demo action, the explicit demo notice, preview exit, English selection and return to the portfolio. The preview now scrolls to the top so its notice appears when entered from a scrolled onboarding page. Dark-mode system-bar contrast was corrected and re-inspected.

JVM evidence includes published BIP84 receive/change vectors, IOHK Cardano BIP32 V2 public derivation vector, Ethereum/TRON address conversion from a public curve point, HID/BLE fragmentation and out-of-order rejection, signing-command rejection, APDU path/status handling, precise zero filtering, contract identity pricing and decimal serialization.

Screenshots contain **demo data only**, not real account balances or current prices:

- [Traditional Chinese onboarding](screenshots/01-onboarding-zh-TW.png)
- [Light portfolio](screenshots/02-portfolio-light-zh-TW.png)
- [Dark settings](screenshots/03-settings-dark-zh-TW.png)
- [English dark portfolio](screenshots/04-portfolio-dark-en.png)
- [Simplified Chinese dark portfolio](screenshots/05-portfolio-dark-zh-CN.png)
- [Small display, large text](screenshots/06-small-large-text.png)
- [Landscape portfolio](screenshots/07-landscape.png), [scrolled landscape assets](screenshots/08-landscape-assets.png)
- [Small display asset rows](screenshots/09-small-large-text-assets.png)

SHA-256 of the supplied local debug APK:

`222951e908403f1f81ef151ea688278381680e9b4f1df2c2572836682bc270da`

This is a debug build for testing, not a production-signed release. A build from another machine will use its own debug signing key and can have a different hash. No physical hardware, security audit or end-to-end balance verification is implied by these results.
