# Scanly — the open-source, privacy-first document scanner

> Free, ad-free, no-subscription, no-account, **no-cloud** document scanner for Android.
> Your documents never leave your phone — and in the FOSS build, they provably *can't*.

Scanly exists because the document-scanner category is hostile to users: the popular
apps (CamScanner, Adobe Scan, TurboScan, SwiftScan) gate **work the phone already does
locally** behind subscriptions, stamp watermarks on exports, run ads, require accounts,
and ship scans of your passport and medical records to someone else's servers.

Scanly is the alternative that should exist: everything runs on-device, nothing is
gated, no ads, no account, no watermark — and the code is open (MIT) so anyone can
fork it, ship it, or build on it.

## Principles (non-negotiable)

1. **100% on-device.** The `foss` build declares **no INTERNET permission** — enforced
   by CI on every commit, verifiable with `aapt` on the APK. Scanning, OCR, and PDF
   generation all happen locally. Privacy is the architecture, not a setting.
2. **No subscription. No ads. No watermark. No account. Ever.** These are the table
   stakes the whole category violates.
3. **No cloud SDKs at all.** There is deliberately no Google Drive or Dropbox
   integration. Export goes through the Android share sheet and the Storage Access
   Framework — which can write straight into Drive/Dropbox/Nextcloud via their *system
   file providers*, without Scanly ever holding a network permission or a token.
4. **Open source, permissive.** MIT-licensed — free to use, fork, and redistribute.
5. **It won't disappear.** No proprietary core dependency; the OpenCV + Tesseract +
   PdfBox pipeline is fully free software.

## Features (working today)

- Point-and-shoot capture with live **auto edge detection** and auto-capture
- **Multi-page batch** scanning that does *not* break when auto-capture is on
- **Manual corner adjustment** — draggable handles re-crop from the original capture
- **Import from gallery** via the system Photo Picker (no storage permission)
- Filters: **Color, Greyscale, Black & White, Magic Color** (shadow removal)
- Page **rotate** and **reorder**; append pages to any document later
- **Offline OCR → searchable PDF** (invisible text layer; English model bundled,
  works on first run with airplane mode on)
- **Copy recognized text** to the clipboard; per-page OCR status badges
- Full-text **search** across your library by scanned content
- **Signature**: draw once, then stamp onto any page (drag + resize, WYSIWYG)
- **Password-protected PDF export** (AES-128) — free here, paywalled elsewhere
- Export as **PDF or per-page JPEGs** via share sheet / Storage Access Framework
- Smart auto-naming (`2026-07-02_Scan_001`), rename, no watermark on anything

## Roadmap

- ID / passport mode (front + back composed onto one page)
- Folders and tags
- Additional OCR languages via user-supplied `.traineddata` (SAF import — still no network)
- Optional encryption at rest (passphrase / biometric)
- Annotation and markup

## Privacy model, concretely

| | foss build | gplay build |
|---|---|---|
| INTERNET permission | **none** (CI-enforced) | only for optional ML Kit model fetch & one-time tip |
| Account required | never | never |
| Analytics / telemetry | none | none |
| Cloud SDKs | none | none |
| Where scans live | app-private storage on your device | same |
| How scans leave | only when *you* share or save them | same |

## Distribution & sustainability

- **`foss` flavor**: pure free software — OpenCV + Tesseract pipeline, zero proprietary
  dependencies, no Play Services. Intended for F-Droid.
- **`gplay` flavor**: same app; may optionally use Google's on-device ML Kit text
  recognition (still 100% on-device) plus an **optional one-time "tip jar"** (never a
  subscription, never a feature gate). All features are free in both flavors.

## Building

Requires JDK 17 and the Android SDK.

```
./gradlew assembleFossDebug        # provably-offline build
./gradlew assembleGplayDebug
./gradlew testFossDebugUnitTest testGplayDebugUnitTest
```

CI (`.github/workflows/ci.yml`) runs the unit tests, assembles both flavors, and then
**fails the build if the merged foss manifest contains the INTERNET permission** — the
privacy claim is machine-checked, not marketing.

## Documents

- [`docs/SPEC.md`](docs/SPEC.md) — feature spec, grounded in documented user pain points
- [`docs/BUILD_PLAN.md`](docs/BUILD_PLAN.md) — tech stack, architecture, phased roadmap

## License

MIT. See [`LICENSE`](LICENSE).
