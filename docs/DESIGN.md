# Interface decisions

Applied the installed `ui-ux-pro-max` skill to the Android Compose interface. Financial-dashboard palette guidance was used; marketing-page recommendations were discarded because this is a native watch-only portfolio app.

- Material 3 navigation separates portfolio, imported accounts and settings.
- Navy surfaces and mint accents communicate calm status; semantic Material colors keep light/dark contrast consistent.
- System fonts support Traditional Chinese, Simplified Chinese and English without downloading fonts.
- The total card names the USDT unit, Binance Spot source and update time. Unknown quotes, stale data and partial totals have text labels, not only colors.
- Portfolio assets open a detail sheet containing exact quantity, quote state/time and contract identity. Manual hiding is reversible from Settings.
- Empty onboarding has an explicit connection action and a clearly marked offline demo preview.
- USB/BLE permission prompts follow deliberate connection actions. Account import identifies the mainnet/path and requests device confirmation.
- Standard controls offer minimum 48 dp targets; primary actions are at least 52 dp tall. Lists scroll, chain/language/theme chips wrap, content is capped at 840 dp on wide screens.
- No decorative timed animation or motion-dependent content. Platform/Compose navigation animations respect platform animator settings.
- Visibility and amount privacy affect display only; totals and provider behavior are explained near their controls.

See `VALIDATION.md` for inspected emulator sizes, themes and text scaling. Low-level provider/transport diagnostic errors may remain in English; normal navigation and user instructions are translated.
