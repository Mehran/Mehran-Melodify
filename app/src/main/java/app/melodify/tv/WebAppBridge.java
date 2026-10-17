package app.melodify.tv;

import android.util.Log;
import android.webkit.JavascriptInterface;

/**
 * Small, deliberately harmless bridge between the injected TV helper and the
 * activity. It only exposes keyboard control and a readiness signal, so a page
 * reached through an in-app link cannot use it to access device data.
 */
public final class WebAppBridge {

    public static final String NAME = "MelodifyTV";

    private static final String TAG = "MelodifyTV-Web";
    private final MainActivity activity;

    WebAppBridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public void showKeyboard() {
        activity.runOnUiThread(activity::showKeyboard);
    }

    @JavascriptInterface
    public void hideKeyboard() {
        activity.runOnUiThread(activity::hideKeyboard);
    }

    @JavascriptInterface
    public void onPageReady() {
        activity.runOnUiThread(activity::onPageReady);
    }

    @JavascriptInterface
    public void log(String message) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, String.valueOf(message));
        }
    }
}
