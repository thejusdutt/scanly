# Bundled Tesseract language data

Drop `eng.traineddata` here before building the FOSS flavor so OCR works fully offline
on first run with zero network.

- Recommended: the `tessdata_fast` English model (small, fast, good enough for scans):
  https://github.com/tesseract-ocr/tessdata_fast/raw/main/eng.traineddata
- For higher accuracy use the standard `tessdata` model instead.

`TrainedDataManager.ensureBundled("eng")` copies this asset to internal storage on first
use. Additional languages are added by the user at runtime via the Storage Access
Framework (the FOSS build has no INTERNET permission to download them).

`eng.traineddata` (tessdata_fast, ~4 MB, Apache-2.0) IS checked in so a clean clone
builds a fully working offline OCR out of the box — a hard requirement for the
"works on first run with no network" privacy promise.
