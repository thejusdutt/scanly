# Scanly — Feature Specification

This spec is derived directly from the multi-source research into what users actually
need (Reddit, Play Store reviews, blogs, HN). Every feature traces back to a documented
pain point. The structure is: **pain points → features → acceptance criteria.**

---

## 1. The user, and what they actually want

Three overlapping personas, all converging on the same demands:

- **The resentful payer** — uses CamScanner/Adobe Scan, furious that scanning + OCR
  (work the phone does locally) is behind a subscription, and that exports carry
  watermarks. *Wants:* the same output, free, no watermark, no ads.
- **The privacy-conscious user** — won't scan IDs/medical/financial docs into "a black
  box." Knows CamScanner's history (server uploads, 2019 malware incident). *Wants:*
  provable on-device processing, no network, optional encryption.
- **The stranded user** — Microsoft Lens (their free default) is being retired. *Wants:*
  a permanent, won't-get-killed replacement → open source = trust.

**The single sentence:** *"Let me scan, OCR, and export documents perfectly, entirely on
my phone, for free, forever, without ads or watermarks, and without my passport leaving
the device."*

---

## 2. Pain-point → feature map

| # | Documented pain point | Scanly feature | Priority |
|---|---|---|---|
| P1 | Subscription for offline work | Everything free, no IAP gate | MVP |
| P2 | Watermarks on export | No watermark, ever | MVP |
| P3 | Ads | No ads, no ad SDK | MVP |
| P4 | Scans uploaded to cloud / privacy | No network permission in core; on-device only | MVP |
| P5 | Free default (Lens) dying / apps vanish | OSS, F-Droid, reproducible builds | MVP |
| P6 | Auto-capture **breaks batch mode** (FOSS leader bug) | Batch state machine that survives auto-capture | MVP |
| P7 | Weak edge auto-detect, esp. in batch | Robust detection + manual corner adjust fallback | MVP |
| P8 | **No greyscale export** (FOSS leader gap) | Color / Greyscale / B&W / Magic Color filters | MVP |
| P9 | OCR scatters text / multi-page OCR crashes | Reliable per-page OCR via WorkManager, isolated failures | v1 |
| P10 | Can't tell which pages are OCR'd | Per-page OCR status badge | v1 |
| P11 | Confusing storage location | Clear default folder + SAF "save as" | v1 |
| P12 | Paid "hooks": signature, ID/passport modes | Built-in, free | v1 |
| P13 | Subscription/dark-pattern billing distrust | One-time optional tip only (gplay), clearly labeled | v1 |

---

## 3. Functional requirements

### 3.1 Capture
- Live camera preview (CameraX) with real-time document boundary overlay.
- **Auto edge detection** drawn at ≥10 fps on a downscaled preview frame; full-res frame
  used on capture.
- **Auto-capture**: when a stable, well-framed quad is held for ~1s, capture
  automatically. Auto-capture is a *mode*, and **must not interfere with batch** (P6):
  after each auto-capture the pipeline returns to "searching" for the next page without
  exiting batch.
- **Manual capture** button always available.
- Capture settings: flash (on/off/auto), grid, batch on/off, auto-capture on/off.
- Manual **corner adjustment** UI after capture for when detection is wrong (P7) —
  draggable handles with magnifier loupe.

**Acceptance:** In batch + auto-capture, scanning 10 pages in a row produces 10 pages
with zero mode resets and per-page corner-correctable crops.

### 3.2 Image processing & filters (P8)
For each page, on-device via OpenCV:
- Perspective correction (warp the detected quad to a rectangle).
- Filters: **Color** (white-balanced enhance), **Greyscale**, **Black & White**
  (adaptive threshold), **Magic Color** (auto contrast + shadow removal).
- Per-page adjustments: rotate (90° steps + fine), brightness, contrast, crop.
- Batch-apply a filter to all pages.

**Acceptance:** Greyscale and B&W are selectable per page and on export; output of a
text receipt in B&W is legible and ≤ the size of the color version.

### 3.3 Multi-page documents
- A "document" = ordered list of pages. Reorder (drag), delete, insert, re-scan a page.
- Append pages to an existing document later.

### 3.4 OCR (P9, P10)
- Offline OCR per page (Tesseract in `foss`; ML Kit Text Recognition optional in `gplay`).
- Runs as a **WorkManager** job so it survives app backgrounding; **failures are isolated
  per page** (one bad page never crashes the batch — directly fixes the FOSS leader's
  multi-page OCR crash).
- **Per-page OCR status badge:** none / queued / done / failed-retry.
- Languages: downloadable Tesseract trained-data packs (bundled English; others fetched
  once by explicit user action — the only optional network touch, off by default).
- Output uses: (a) **searchable PDF** with an invisible text layer aligned to word
  bounding boxes; (b) **copy/extract text** per page or whole document.

**Acceptance:** A 20-page mixed-quality batch OCRs to completion with any unreadable page
marked failed (retryable), never aborting the rest; resulting PDF text is selectable and
positioned over the right words.

### 3.5 Export & storage (P11)
- Export formats: **PDF** (single/multi-page, with optional OCR layer), **JPEG**, **PNG**.
- Page size presets for PDF (Auto/A4/Letter) and quality slider.
- **No watermark** on any output (P2).
- Save via **Storage Access Framework** (user picks location) + a clear, documented
  default app folder. Share via the system share sheet.
- Optional user-initiated **WebDAV/SAF export** target (no background sync).

### 3.6 ID / passport mode (P12)
- Card aspect-ratio guide; capture front and back; auto-compose both onto one page
  (side-by-side or stacked). Presets: ID card, passport, business card.

### 3.7 Signature (P12)
- Draw a signature once on a Compose canvas; store transparent PNG; stamp + resize/move
  onto any page. Multiple saved signatures.

### 3.8 Organization
- Folders/tags; list + grid views with thumbnails.
- **Smart rename patterns** (e.g. `yyyy-MM-dd_<DocType>_<counter>`).
- **Search** documents by name and by OCR'd text content (full-text search over the
  extracted text).

### 3.9 Privacy & security (P4, P13)
- `foss` flavor declares **no INTERNET permission**. `gplay` keeps it only for optional
  language-pack download / ML Kit model fetch / tip-jar billing, all user-triggered.
- **Encryption at rest** (optional): encrypt stored page images + DB with a passphrase
  unlocked by biometric/PIN.
- No analytics, no crash SDK that exfiltrates data (opt-in local logs only).
- An **in-app privacy panel** stating plainly what does/doesn't leave the device.

---

## 4. Non-functional requirements
- **Performance:** edge-detection overlay ≥10 fps mid-range device; capture-to-cropped
  preview <500 ms; OCR of one A4 page <4 s (Tesseract) on mid-range hardware.
- **Compatibility:** minSdk 24 (Android 7), target latest stable.
- **Size:** base APK lean; OpenCV/Tesseract via ABI splits; OCR language packs on demand.
- **Accessibility:** TalkBack labels, large-touch corner handles, high-contrast mode.
- **i18n:** UI localizable; OCR multi-language.
- **Offline-first:** fully functional with airplane mode on.

---

## 5. Competitive feature matrix (target)

| Feature | CamScanner (paid) | MS Lens (dying) | OSS Doc Scanner (FOSS) | **Scanly (target)** |
|---|---|---|---|---|
| Fully free / no sub | ❌ | ✅ | ✅ | ✅ |
| No watermark | ❌ (free) | ✅ | ✅ | ✅ |
| No ads | ❌ | ✅ | ✅ | ✅ |
| On-device only | ❌ | ❌ | ✅ | ✅ |
| Reliable batch + auto-capture | ✅ | ✅ | ⚠️ buggy | ✅ |
| Greyscale filter | ✅ | ✅ | ❌ | ✅ |
| Offline OCR → searchable PDF | ✅ | ⚠️ | ⚠️ crashes | ✅ |
| Per-page OCR status | ✅ | ❌ | ❌ | ✅ |
| ID/passport mode | ✅ | ⚠️ | ❌ | ✅ |
| Signature | ✅ | ❌ | ❌ | ✅ |
| Encryption at rest | ⚠️ | ❌ | ✅ | ✅ |
| Open source / won't vanish | ❌ | ❌ | ✅ | ✅ |

The win condition is the **whole column being ✅** — no single competitor manages it.

---

## 6. Out of scope for v1
- PDF → Word/Excel conversion (tempts toward a backend; revisit as on-device stretch).
- Cloud accounts / real-time sync.
- Team/sharing collaboration features.
