# WebView JS bridge and app entry points are kept by name; the release build
# does not minify, so these rules are a safety net for future minification.
-keepclassmembers class app.melodify.tv.WebAppBridge {
    public *;
}
-keep class app.melodify.tv.MainActivity { *; }
