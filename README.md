# Sahl PDF · سهل PDF

A calm, fast PDF reader for Android phones and TVs, part of the Sahl apps family.

Sahl PDF 2 is a new app, written from scratch. It shares no code with earlier versions (those were built on MJ PDF; that code is kept only on the `legacy-mjpdf` tag). It's built step by step: each stage ships as a normal update.

## Features (stage 4: text & notes)
- **Open** from the file picker, from "Open with…" or "Share" in any app, or from the **On this device** list (needs file access, which you're asked for only there).
- **Send from phone** (on TV): open the local address and 6-digit code in any phone browser on the same Wi-Fi to send PDFs straight to `Download/Sahl PDF`.
- **Recent** list with page thumbnails and reading progress. Each book reopens on the page where you stopped.
- **Smooth reading**: continuous vertical scroll or horizontal page-by-page (left-to-right and right-to-left for Arabic books), **two-page spread**, fit width or fit whole page, fling, pinch and double-tap zoom, sharp text when zoomed in.
- **Text selection & Xodo-style annotations**: long-press with drag handles to select text, copy, highlight (5 colors), underline, strikethrough, squiggly, or attach formatted Sahl notes; **Annotate bar** with Pen, freehand Highlighter (works on scanned and locked PDFs), Rect, Circle, Arrow, Free Text, Sticky Notes, Eraser, and **Save annotated PDF**.
- **Links**: tap internal PDF links to jump pages or external links to open in your browser.
- **Search** inside the document with match highlights and next/previous navigation.
- **Bookmarks**: bookmark pages and jump back to them anytime.
- **Crop margins**: automatically trim white page margins for comfortable reading.
- **Contents** (the PDF's table of contents), **go to page**, a page slider and a page counter.
- **Night pages** (inverted colours), keep the screen on, password-protected PDFs.
- **Share / Open with** and document info.
- **TV edition**: first-start TV defaults (two-page spread, whole page visible), **TV menu** on OK/Menu, ▲ ▼ scroll, ◀ ▶ previous/next spread (respects RTL), ⏪ ⏩ and channel +/− turn pages.
- **In-app updates** from GitHub Releases, and the Sahl Union app strip in Settings.
- Arabic and English, light and dark.

## Coming next
| Stage | Contents |
|---|---|
| 5. PDF tools | Organize pages, merge/split, document scanner |

## Install
Download from [Releases](../../releases/latest):
- Most phones: `SahlPDF-arm64-v8a.apk`.
- Mi TV and older 32-bit devices: `SahlPDF-armeabi-v7a.apk`.
- Not sure: `SahlPDF.apk` (universal). Needs Android 8.0 or newer.

It uses the same app ID (`com.aly.sahlpdf`) and signing key as Sahl PDF 1.x, so it installs over it and updates in place. Old reading positions and bookmarks are not carried over.

**TV:** to let the **On this device** list see your files, allow access once from a computer:
```
adb shell appops set com.aly.sahlpdf MANAGE_EXTERNAL_STORAGE allow
```

## Build
JDK 17, Android SDK 36 and Gradle 8.14: `gradle testDebugUnitTest assembleRelease`. CI (`.github/workflows/build.yml`) signs with the Sahl key from the repository secrets and publishes each `main` build as release `build-N`, plus a fixed `latest` release.

## Licence
MIT, see [LICENSE](LICENSE). Third-party: PDFium (BSD-3-Clause) through PdfiumAndroidKt (Apache-2.0), and the Kotlin libraries (Apache-2.0).
