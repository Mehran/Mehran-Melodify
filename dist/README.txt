Mehran Melodify 1.1.0 — Android TV / NetBox wrapper
===================================================

APK:      MehranMelodify-1.1.0-armeabi-v7a.apk (2.5 MB, signed, minSdk 21, targetSdk 35)
SHA-256:  e3edc9333b4f4bdb70a23de3de19f86213f238aebde838e14b38d5e327cffe89

Install over ADB:
    adb connect <box-ip>:5555
    adb install -r MehranMelodify-1.1.0-armeabi-v7a.apk

Or copy the APK to the device and open it with a file manager.

Launch "Mehran Melodify" from the Android TV launcher Apps row. A short
music-themed intro plays (logo, wordmark, animated equalizer) and then opens
the Melodify login screen; any remote key skips the intro.
Remote: D-pad to move, OK to select, BACK to go back (twice to exit).

The app contains no native libraries, so it is compatible with 32-bit
armeabi-v7a NetBox devices as well as arm64 devices. A reasonably modern
Android System WebView (90+) is recommended.

This release keeps the same package (app.melodify.tv) and signing key as
1.0.0, so it updates in place. The previous build is kept as
MelodifyTV-1.0.0-armeabi-v7a.apk for rollback.

The complete Android Studio project is in MelodifyTV-AndroidTV-source.zip.
Build instructions and verification notes are in README.md.

This is an independent wrapper for desktop.melodify.app and is not
affiliated with Melodify.
