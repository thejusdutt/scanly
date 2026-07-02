# Scanly — Build Plan

How we build it: the technology choices (with licenses, because this is GPL-3 OSS),
the architecture, and a phased roadmap with shippable milestones.

---

## 1. Technology stack & library choices

All core libraries are permissively licensed and GPL-3-compatible. Proprietary options
are isolated to the `gplay` flavor and never required for a working app.

### Core (both flavors)

| Concern | Choice | License | Why |
|---|---|---|---|
| Language | **Kotlin** | — | Modern Android standard; matches existing projects |
| UI | **Jetpack Compose + Material 3 (Material You)** | Apache-2.0 | Modern, themeable; addresses "free apps look basic" |
| Camera | **CameraX** (`androidx.camera`) | Apache-2.0 | Stable preview/analysis/capture, auto-exposure, device coverage |
| CV / edge detection / filters | **OpenCV Android SDK 4.x** | Apache-2.0 | Contour detection, perspective warp, adaptive threshold (greyscale/B&W/magic) — the FOSS-safe engine |
| OCR (foss) | **Tesseract4Android** (`com.github.adaptech-cz:Tesseract4Android`) | Apache-2.0 | Maintained Tesseract 5 + Leptonica wrapper (tess-two is dead); gives word bounding boxes for the searchable-PDF text layer |
| PDF generation | **PdfBox-Android** (`com.tom-roush:pdfbox-android`) | Apache-2.0/BSD | Can place an **invisible text layer** (render mode 3) over the image → true searchable PDF; native `PdfDocument` can't |
| Local DB | **Room** + **FTS4** | Apache-2.0 | Document/page metadata + full-text search over OCR text |
| Background work | **WorkManager** | Apache-2.0 | Per-page OCR jobs that survive backgrounding; isolated failures |
| Image loading | **Coil** | Apache-2.0 | Compose-native thumbnails |
| DI | **Hilt** | Apache-2.0 | Standard, testable wiring |
| Encryption (files) | **androidx.security:security-crypto** (`EncryptedFile`) | Apache-2.0 | At-rest page-image encryption |
| Encryption (DB) | **SQLCipher for Android** | BSD-style | Optional encrypted Room DB |
| Biometric unlock | **androidx.biometric** | Apache-2.0 | Unlock encrypted vault |

### `gplay` flavor only (optional, on-device, never required)

| Concern | Choice | License | Note |
|---|---|---|---|
| Edge detection / scan UI | **ML Kit Document Scanner** (`play-services-mlkit-document-scanner`) | Proprietary | On-device; higher-quality detection + ready UI. Behind a flavor interface so `foss` uses OpenCV instead |
| Fast Latin OCR | **ML Kit Text Recognition v2** | Proprietary | On-device; faster than Tesseract for Latin scripts |
| Optional tip jar | **Play Billing** (one-time product) | Proprietary | A *tip*, not a feature gate; never a subscription |

> **Flavor rule:** every proprietary capability sits behind a Kotlin interface
> (`DocumentDetector`, `TextRecognizer`) with a FOSS (OpenCV/Tesseract) implementation and
> a gplay (ML Kit) implementation. The `foss` build must compile and run with zero
> Play-Services / proprietary dependencies, and with **no INTERNET permission**.

---

## 2. Architecture

Single-module-to-start, clean layering; split into Gradle modules once it grows.

```
app/
 ├─ ui/            Compose screens + ViewModels (MVVM, unidirectional state)
 │   ├─ capture/        camera + live edge overlay + auto/batch state machine
 │   ├─ review/         crop, corner-adjust, filters, reorder
 │   ├─ document/       page list, OCR status, export
 │   ├─ library/        folders, search, rename
 │   ├─ signature/      draw + manage signatures
 │   └─ settings/       privacy panel, encryption, language packs
 ├─ domain/        use cases: DetectEdges, WarpPage, ApplyFilter, RunOcr,
 │                 BuildSearchablePdf, ExportDocument  (pure, testable)
 ├─ data/
 │   ├─ db/             Room entities (Document, Page, OcrResult) + FTS DAO
 │   ├─ files/          storage (default folder + SAF), encrypted file store
 │   └─ repo/           DocumentRepository, OcrRepository
 ├─ cv/            OpenCV wrappers: detection, perspective, filters
 ├─ ocr/           Tesseract engine + trained-data manager
 ├─ pdf/           PdfBox builder with invisible text-layer placement
 └─ platform/      flavor interfaces: DocumentDetector, TextRecognizer
foss/  → OpenCvDetector, TesseractRecognizer            (no proprietary deps)
gplay/ → MlKitDetector, MlKitRecognizer, BillingTipJar
```

### Key technical designs

- **Capture state machine** (fixes the batch/auto-capture bug, P6):
  `IDLE → SEARCHING → STABLE(quad held ~1s) → CAPTURED → (batch? back to SEARCHING : REVIEW)`.
  Auto-capture only transitions `STABLE→CAPTURED`; it never tears down batch state.
- **Edge detection pipeline:** downscale preview frame → grayscale → blur →
  Canny/adaptive threshold → `findContours` → largest 4-point convex quad by area →
  map back to full-res coords. Manual corner override always available.
- **Searchable PDF:** for each page draw the (filtered) image full-bleed, then for each
  OCR word draw its text at its bounding box with PDFBox text render mode 3 (invisible),
  scaled to match. Result: looks like the scan, selects/searches like text.
- **OCR robustness:** one WorkManager work request per page, unique-named per page id;
  a failure marks that page `failed` and continues — never aborts the document (fixes P9).
- **Privacy by construction:** `foss` manifest has no `INTERNET`; a lint/CI check fails
  the build if it reappears. Privacy panel reads the real declared permissions.

---

## 3. Phased roadmap

Each phase is independently shippable/testable. Target: a usable MVP by end of Phase 3.

### Phase 0 — Foundation (scaffold)
- Kotlin + Compose project, Hilt, Material 3, `foss`/`gplay` product flavors.
- GPL-3 LICENSE, README, CONTRIBUTING, CI (build + lint + unit tests), `.editorconfig`.
- CI gate: `foss` build must have no INTERNET permission and no Play-Services deps.
- **Done when:** both flavors build green in CI; empty app launches.

### Phase 1 — Capture & crop (the core loop)
- CameraX preview; OpenCV edge detection overlay; manual + auto capture.
- Capture state machine; **batch mode** that survives auto-capture.
- Perspective warp; manual corner-adjust UI with magnifier.
- **Done when:** scan a page → auto-detected, correctable crop → flat dewarped image.

### Phase 2 — Filters & multi-page editing (closes the greyscale gap)
- Filters: Color / **Greyscale** / B&W / Magic Color; per-page rotate/brightness/contrast.
- Multi-page document model: reorder, delete, insert, append, re-scan a page.
- Room persistence + thumbnails (Coil).
- **Done when:** a multi-page doc with mixed filters persists and reopens correctly.

### Phase 3 — PDF export (MVP shippable)
- PdfBox multi-page PDF, page-size presets, quality slider, **no watermark**.
- JPEG/PNG export; SAF "save as" + clear default folder; system share sheet.
- **Done when:** export a multi-page PDF to a user-chosen location and open it elsewhere.
  → **Ship MVP to F-Droid.**

### Phase 4 — OCR & searchable PDF
- Tesseract4Android (foss) behind `TextRecognizer`; bundled English trained data;
  on-demand language packs (explicit user action).
- WorkManager per-page jobs; **per-page OCR status badge**; isolated failures.
- Searchable-PDF text layer; copy/extract text; FTS search over OCR content.
- gplay: ML Kit Text Recognition implementation.
- **Done when:** 20-page batch OCRs with isolated failures; PDF text is selectable/searchable.

### Phase 5 — The "paid hooks", for free
- **ID/passport mode** (front+back compose, card guides).
- **Signature** draw/save/stamp.
- Smart rename patterns; folders/tags.
- **Done when:** produce a one-page ID scan (both sides) and a signed document.

### Phase 6 — Privacy, security, polish
- Encryption at rest (EncryptedFile + SQLCipher) unlocked by biometric/PIN.
- In-app privacy panel; accessibility pass (TalkBack, contrast, large handles).
- Material You theming, AMOLED dark; settings backup/restore.
- gplay: optional one-time tip jar (clearly a tip).
- **Done when:** encrypted vault round-trips; a11y audit passes.

### Phase 7 — Release engineering
- **Reproducible builds** + F-Droid metadata/inclusion; Play listing (gplay).
- ABI splits for OpenCV/Tesseract; size budget check.
- Crash-free instrumentation that stays on-device (opt-in local logs only).
- **Done when:** F-Droid build reproduces; both flavors released.

---

## 4. Testing strategy
- **Unit:** domain use cases (filter math, quad selection, rename patterns, PDF text
  placement geometry) with fixtures.
- **CV regression:** a corpus of sample photos (receipts, A4, glossy, skewed, low light)
  with expected corner coordinates; assert IoU of detected vs. expected quad.
- **OCR regression:** sample pages with ground-truth text; assert character/word accuracy
  thresholds; assert one corrupt page doesn't abort the batch.
- **Instrumented:** capture→crop→filter→export happy path; batch+auto-capture 10-page run
  (guards against the P6 regression).
- **CI invariant:** `foss` has no INTERNET permission / no proprietary deps.

---

## 5. Key risks & mitigations
| Risk | Mitigation |
|---|---|
| Edge detection quality (hard to beat paid) | OpenCV tuned pipeline + always-available manual corners; ML Kit in gplay as quality ceiling |
| OCR accuracy/perf on low-end devices | Downscale sensibly, background jobs, per-page status; ML Kit fast path in gplay |
| APK size (OpenCV + Tesseract native libs) | ABI splits, on-demand language packs, R8 |
| "Android users expect free" → no revenue | Treat as reputation/OSS; optional one-time tip only; zero server cost = sustainable |
| Play donation-link rejection | No donation links in binary; funding pointers in README/listing only |
| Maintenance burden over time | Copyleft + community contributions; minimal moving parts; no backend to operate |

---

## 6. Definition of done (v1.0)
Every cell in the SPEC §5 matrix is ✅ for Scanly, the `foss` build ships on F-Droid with
no network permission, and a user can scan → auto-crop → greyscale → OCR → export a
searchable, watermark-free, multi-page PDF entirely offline.
