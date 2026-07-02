# Contributing to Scanly

Thanks for helping build a scanner that respects its users.

## The rules that make Scanly *Scanly*

These are not negotiable — PRs that violate them won't be merged:

1. **No network in the core.** The `foss` flavor must never declare
   `android.permission.INTERNET`. CI enforces this. Any networking belongs behind a
   user action in the `gplay` flavor only.
2. **No ads, no trackers, no analytics SDKs.** Not even "anonymous" ones.
3. **No subscriptions and no feature paywalls.** The optional tip jar is a *tip*; every
   feature works without it.
4. **No watermarks on any output.**
5. **Proprietary deps stay in `gplay`.** Anything not GPL-3-compatible (ML Kit, Billing)
   must sit behind a `platform/` interface with a working FOSS implementation.

## Dev setup

- Android Studio (latest stable), JDK 17.
- `./gradlew assembleFossDebug` builds the pure-FOSS app.
- `./gradlew testFossDebugUnitTest` runs unit tests.

## Architecture

See [`docs/BUILD_PLAN.md`](docs/BUILD_PLAN.md). Briefly: Compose UI + MVVM ViewModels →
domain use cases → `DocumentRepository` → Room + file storage. Edge detection, filters,
OCR, and PDF generation are all on-device.

## Where things are

| Area | Path |
|---|---|
| Flavor interfaces | `app/src/main/java/com/scanly/platform/` |
| FOSS impls (OpenCV/Tesseract) | `app/src/foss/` |
| gplay impls (ML Kit/Billing) | `app/src/gplay/` |
| CV (warp/filters/detect) | `app/src/main/java/com/scanly/cv/` |
| Searchable PDF | `app/src/main/java/com/scanly/pdf/` |
| OCR worker | `app/src/main/java/com/scanly/ocr/` |

## Tests we care about

- `CaptureStateMachineTest` — guards the "auto-capture breaks batch" regression.
- CV/OCR regression corpora (see build plan) — add fixtures with your PR when touching
  detection or OCR.
