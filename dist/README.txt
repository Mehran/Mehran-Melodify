Melodify TV 1.0.0 — Android TV / NetBox wrapper
===============================================

APK:      MelodifyTV-1.0.0-armeabi-v7a.apk (2.4 MB, signed, minSdk 21, targetSdk 35)
SHA-256:  f97f58725cb764f907e865be1e282ea977852db9d72b3bc50be9aa8441a6d1f1

Install over ADB:
    adb connect <box-ip>:5555
    adb install -r MelodifyTV-1.0.0-armeabi-v7a.apk

Or copy the APK to the device and open it with a file manager.

Launch "Melodify TV" from the Android TV launcher Apps row.
Remote: D-pad to move, OK to select, BACK to go back (twice to exit).
The app contains no native libraries, so it is compatible with 32-bit
armeabi-v7a NetBox devices as well as arm64 devices. A reasonably modern
Android System WebView (90+) is recommended.

The complete Android Studio project is in MelodifyTV-AndroidTV-source.zip.
Build instructions and verification notes are in README.md.

This is an independent wrapper for desktop.melodify.app and is not
affiliated with Melodify.
