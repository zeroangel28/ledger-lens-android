# Validation record

## v0.4.0 — 2026-10-09

- `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` succeeded with JDK 17 / Gradle 8.14 / SDK 35: **68 JVM tests passed**, including **14 portfolio-history tests**. Lint: **0 errors, 9 existing warnings**.
- History regressions cover calendar month ends/leap years, complete UTC daily closes rather than candle opens, exclusion of unfinished/duplicate/out-of-range/zero-price candles, fixed current quantities across multiple/hidden accounts, exclusion of unknown balances, canonical token identities, USDT as the valuation unit, absent/pre-listing dates at zero without interpolation, invalid current quotes, persisted daily cache reuse across ranges/restarts, UTC-day refresh, clock rollback, provider errors retaining prior history, Retry-After backoff and cancellation without false cache success.
- Live public Binance reads returned **183 complete UTC daily candles each for BTCUSDT and NIGHTUSDT**, covering 2026-04-09 through 2026-10-08. Positive closing prices and all open/close timestamps were validated. No wallet data was sent. See `PUBLIC_API_V0.4.0.json`; this validates the development machine's response, not the user's endpoint access.
- Three new Android UI tests compile for default one-month/four-range selection, date buttons/data table, privacy semantics and large-text light-theme missing-data/error controls. The chart uses semantic theme colors, a zero baseline, date/value readouts, accessible slider state, 48dp controls, alternative daily data and no animated chart transitions. **Actual instrumentation, Samsung rendering, TalkBack, small/large/tablet/landscape layouts, contrast measurement and reduced-motion inspection remain pending. ADB remains off per the user's prior request; no phone was installed.**
- APK version **0.4.0 / versionCode 8**. SHA-256: `538b1b13b0daa7a932b3487cad6ef84a77a04a3d7b819c77d70da38c984031ed`. Certificate SHA-256: `d85236d6b2b4b426fd1929c514d3453240cf7062e6c7f9e5509a9c83e82a553a`, matching earlier delivered builds.
- Historical pricing never queries chain balances or Ledger. Chart ranges use cached public candles, while estimates use current quantities in memory. Missing prices are deliberately zero and identified; the chart is not actual historical holdings or profit/loss. Existing rise/fall alerts, Cardano routine holdings limits, receiving derivation and signing/transfers are unchanged. No funds were moved. [Traditional Chinese chart instructions](HISTORY.zh-TW.md).

## v0.3.1 — 2026-10-09

- `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` succeeded with JDK 17 / Gradle 8.14 / SDK 35: **54 JVM tests passed**, including **14 price-alert tests**. Lint: **0 errors, 9 warnings** (existing platform and pinned dependency warnings).
- Added exact −3% and −5% boundaries, no rounded false triggers, sustained-fall suppression, falling rearm/cooldown, independent rise/fall cooldowns during direction reversals, falling clock rollback, encrypted-journal round-trip of both timestamps, and migration from v0.3.0 without losing settings or rise history. Both directions evaluate the same quotes/reference candles and make no additional Binance requests.
- Traditional Chinese, Simplified Chinese and English settings now describe both directions and show ±3% / ±5%. Fall notifications use a signed negative percentage and falling message; privacy and generic lock-screen behavior remain intact. Updated Android UI tests compile. **Samsung notification delivery, background wakeups, actual Android instrumentation and visual inspection remain pending; ADB remains off at the user's prior request. No phone install was performed.**
- APK version: **0.3.1 / versionCode 7**. SHA-256: `4721587161f52ed4e2dca4c9dd2bfab9c88fd2aec9cacfc024ed156093ff9f03`. Certificate SHA-256: `d85236d6b2b4b426fd1929c514d3453240cf7062e6c7f9e5509a9c83e82a553a`, matching previous delivered builds for an in-place update.
- Ledger transport, receiving derivation, signing and transfer behavior are unchanged. Physical Flex checks remain pending; no funds were moved. See [notification and transfer test instructions](TESTING.zh-TW.md).

## v0.3.0 — 2026-10-09

- Built debug APK and Android instrumentation APK with JDK 17 / Gradle 8.14 / SDK 35. `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` succeeded: **49 JVM tests passed**, including 9 price-alert regressions. Lint: **0 errors, 9 warnings** (target/platform attribute and pinned dependency updates).
- Alert tests cover opt-in defaults (+3%, optional +5%), exact decimal threshold comparison, persistent crossing/cooldown state, no repeat during a continuous rise, below-threshold rearming, one-hour separation, clock rollback, stale/future/invalid data, canonical positive holdings and duplicate-account filtering, one-hour minute-candle selection, and absent/reference-provider failures.
- Live public Binance check returned a positive BTCUSDT Spot quote and the exact requested one-minute candle 60–61 minutes earlier, with matching open/close timestamps. No wallet address, account key, credential or chain query was involved. See `PUBLIC_API_V0.3.0.json`. This confirms the development machine's endpoint response, not the user's network or background notification delivery.
- Android UI tests for opt-in controls, +5% selection, foreground-only choice, explicit test notification action, blocked-channel instructions and large-text light theme compile. **Samsung notification delivery, WorkManager wakeups, changed UI screenshots, small/large/tablet layouts, landscape/reduced-motion inspection and actual instrumentation execution remain pending.** ADB remains off as previously requested by the user; no phone install was performed.
- Background work only reads the encrypted portfolio, selects canonical positive holdings and calls Binance. Alert settings/crossing records use a separate encrypted file; workers do not overwrite accounts, balances, keys or transaction status. Reference queries are independent of foreground price cadence and bounded to three concurrent reads. Errors never classify missing prices as a rise.
- APK SHA-256: `98a43a04a39154c90b7a9d0b6dcb7840a6b31eb127fac9295d775db3f219c3f9`. Certificate SHA-256: `d85236d6b2b4b426fd1929c514d3453240cf7062e6c7f9e5509a9c83e82a553a`, matching previous delivered builds.
- Ledger transport, private-key policy, signing engine and mainnet transfer rules are unchanged from v0.2.1. Prior offline SDK tests remain historical evidence; physical Flex new-address/receive/send tests remain pending. No mainnet transactions were submitted. [Traditional Chinese test instructions](TESTING.zh-TW.md) cover free address checks, rejection and controlled small-value mainnet tests; testnet is not supported in this build.

## v0.2.1 — 2026-10-05

- `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` succeeded with JDK 17 / Gradle 8.14 / SDK 35. **40 JVM tests passed; 12 offline SDK/cryptographic tests passed. Lint: 0 errors, 2 warnings.**
- Receiving regressions cover published BIP84 index-1 derivation, unchanged source-account identity, persisted indices and migration, confirmed/pending history skipping, failed-provider handling, bounded unused gaps, issued-address balance discovery beyond an earlier empty gap, transfer UTXO discovery at a nonzero receive index, and Cardano public derivation/invalid indices.
- Official Ledger SDK fixtures check BTC device address-display requests at index 3 and Cardano v7/v8 requests at index 2 with the original stake path. A different returned BTC address and invalid index are rejected. These are mocked transport responses, not physical-device evidence.
- The Android instrumentation APK builds with new receive generation/navigation, large text/light theme and saved-state-restoration scenarios. **This version's Android runtime/UI tests, new screenshots, small-screen/landscape/reduced-motion inspection and Samsung installation were deferred at the user's request to leave ADB off.** Historical v0.2.0 emulator results below do not validate these changed screens.
- Cardano address generation does not query holdings. Its 24-hour ordinary holdings limit remains intact. Generated indices are saved before publication; existing addresses and the source signing identity remain intact. ETH/TRON keep the imported account address and require importing another Ledger account for another address.
- APK SHA-256: `75ae33933ebb5048e7f6145a79a461f51e55057c4dd60b537f0310a96c5c8545`. Signing certificate SHA-256: `d85236d6b2b4b426fd1929c514d3453240cf7062e6c7f9e5509a9c83e82a553a`, matching the earlier supplied builds.
- **Physical Flex USB/BLE display at nonzero indices, real receiving/spending and production security review remain pending. No mainnet funds were sent.** See [receiving changes](V0.2.1.md) and [device checks](FLEX_TESTING.md).

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
