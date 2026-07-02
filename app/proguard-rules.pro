# OpenCV
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# Tesseract4Android
-keep class cz.adaptech.tesseract4android.** { *; }
-dontwarn cz.adaptech.tesseract4android.**

# PdfBox-Android
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**

# ML Kit (gplay)
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# BouncyCastle (PDF encryption via PDFBox) — registered as a JCE provider reflectively.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-dontwarn org.bouncycastle.**

# Room / Hilt generated code is handled by their own rules.
