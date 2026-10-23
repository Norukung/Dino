# DinoBlog Phase 11 — DTN image messages

Version **3.2-relay-cleanup** (versionCode **15**), Room schema **8**.

Phase 11 adds image bundles to the existing nearby DTN messaging path. Text messages, Spray-and-Wait, Phase 8 smart relay selection, Phase 10 relay participation, and signed delivery receipts remain part of this version. Images are stored in app-private files and transferred with manifest, chunk, hash, and resume checks.

Build the Android app with `.\build.ps1`. The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. The detailed implementation and manual test procedure are in [PHASE11_IMAGE_DTN.md](PHASE11_IMAGE_DTN.md).

Earlier phase reports: [Phase 10](PHASE10_RELAY_CLEANUP.md), [Phase 9](PHASE9_IDENTITY_UI.md), [Phase 8](PHASE8_SMART_RELAYS.md), [Phase 7](PHASE7_SPRAY_AND_WAIT.md), [Phase 6](PHASE6_DELIVERY_RECEIPTS.md).

Android instrumentation and multi-phone transport behavior require devices connected to ADB. Building the APK does not verify a physical encounter.
