package app.melodify.tv;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Full-screen WebView shell for the Melodify desktop web app on Android TV.
 *
 * The activity deliberately keeps the WebView alive in the background so music
 * keeps playing, and flushes cookies on every page boundary and lifecycle stop
 * so the login session survives reboots and memory pressure.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MelodifyTV";
    private static final String START_URL = "https://desktop.melodify.app/login/get-number";
    private static final String ENHANCER_ASSET = "tv-enhance.js";
    private static final long DOUBLE_BACK_WINDOW_MS = 2000L;

    private WebView webView;
    private ProgressBar topProgress;
    private View loadingOverlay;
    private View errorOverlay;
    private TextView errorMessage;
    private Button retryButton;

    private String enhancerScript = "";
    private boolean hasLoadedOnce = false;
    private boolean pageError = false;
    private long lastBackPress = 0L;

    private final Handler cookieHandler = new Handler(Looper.getMainLooper());
    private final Runnable cookieFlusher = new Runnable() {
        @Override
        public void run() {
            flushCookies();
            if (!isFinishing()) {
                cookieHandler.postDelayed(this, 5000L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Edge-to-edge full-screen TV surface. System bars are hidden; on a TV
        // box there usually are none, but this also makes phone testing clean.
        WindowInsetsControllerCompat insets =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insets.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        insets.hide(WindowInsetsCompat.Type.systemBars());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);
        bindViews();

        enhancerScript = readAsset(ENHANCER_ASSET);
        configureWebView();
        configureCookies();

        retryButton.setOnClickListener(v -> retry());

        boolean restored = false;
        if (savedInstanceState != null) {
            restored = webView.restoreState(savedInstanceState) != null;
        }
        if (restored) {
            hasLoadedOnce = true;
            hideLoading(true);
        } else {
            webView.loadUrl(START_URL);
        }
    }

    private void bindViews() {
        webView = findViewById(R.id.webview);
        topProgress = findViewById(R.id.top_progress);
        loadingOverlay = findViewById(R.id.loading_overlay);
        errorOverlay = findViewById(R.id.error_overlay);
        errorMessage = findViewById(R.id.error_message);
        retryButton = findViewById(R.id.retry_button);
    }

    // ---------------------------------------------------------------------
    // WebView configuration
    // ---------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        webView.setBackgroundColor(Color.BLACK);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setLongClickable(false);
        webView.setOnLongClickListener(v -> true);
        webView.setHapticFeedbackEnabled(false);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);

        // Music must be able to start without a synthetic tap, and the WebView
        // should keep using its normal media stack on TV.
        settings.setMediaPlaybackRequiresUserGesture(false);

        settings.setLoadsImagesAutomatically(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setGeolocationEnabled(false);
        settings.setSaveFormData(true);
        settings.setDefaultTextEncodingName("UTF-8");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        // Strip the WebView/Mobile markers so the site serves its wide layout,
        // which is the usable one on a 10-foot display.
        String ua = settings.getUserAgentString();
        if (ua == null || ua.isEmpty()) {
            ua = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
        }
        ua = ua.replace("; wv", "")
                .replace("Version/4.0 ", "")
                .replace(" Mobile", "");
        settings.setUserAgentString(ua + " MelodifyTV/" + BuildConfig.VERSION_NAME);

        webView.addJavascriptInterface(new WebAppBridge(this), WebAppBridge.NAME);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUri(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUri(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                pageError = false;
                topProgress.setVisibility(View.VISIBLE);
                if (errorOverlay.getVisibility() == View.VISIBLE) {
                    errorOverlay.setVisibility(View.GONE);
                }
                if (!hasLoadedOnce) {
                    showLoading();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Commit cookies and storage as soon as the document is done;
                // many SPA auth redirects happen immediately afterwards.
                flushCookies();
                topProgress.setVisibility(View.GONE);

                if (pageError) {
                    hideLoading(true);
                    return;
                }

                view.requestFocus();
                injectEnhancer();

                if (!hasLoadedOnce) {
                    hasLoadedOnce = true;
                    // The injected helper normally signals readiness earlier;
                    // this is the fallback for a page whose React root is slow.
                    view.postDelayed(MainActivity.this::hideLoading, 900L);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError();
                }
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int errorCode, String description,
                                        String failingUrl) {
                // Only used on API 21/22; this callback is main-frame only.
                showError();
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                if (request.isForMainFrame()
                        && errorResponse != null
                        && errorResponse.getStatusCode() >= 500) {
                    showError();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                topProgress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // A music player does not need the microphone or camera.
                if (request != null) {
                    request.deny();
                }
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (message != null && (BuildConfig.DEBUG
                        || message.messageLevel() == ConsoleMessage.MessageLevel.ERROR)) {
                    Log.w(TAG, "JS console: " + message.message()
                            + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
                }
                return true;
            }
        });
    }

    private void configureCookies() {
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);
        scheduleCookieFlush();
    }

    private boolean handleUri(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        scheme = scheme.toLowerCase(Locale.US);

        // Keep the whole Melodify flow, including checkout/auth redirects, in
        // the same WebView so cookies and localStorage stay in one session.
        if ("http".equals(scheme) || "https".equals(scheme)) {
            return false;
        }

        // Never execute script/data URLs coming from page content.
        if ("javascript".equals(scheme) || "data".equals(scheme)
                || "blob".equals(scheme) || "about".equals(scheme)) {
            return true;
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.error_body, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // Session persistence
    // ---------------------------------------------------------------------

    private void flushCookies() {
        try {
            CookieManager.getInstance().flush();
        } catch (Throwable t) {
            Log.w(TAG, "Cookie flush failed", t);
        }
    }

    private void scheduleCookieFlush() {
        // A second flush on the next loop safely covers cookies written by the
        // page's first paint without blocking the UI thread.
        webView.postDelayed(MainActivity.this::flushCookies, 1200L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        cookieHandler.removeCallbacks(cookieFlusher);
        cookieHandler.postDelayed(cookieFlusher, 5000L);
    }

    @Override
    protected void onPause() {
        flushCookies();
        cookieHandler.removeCallbacks(cookieFlusher);
        // Intentionally NOT calling webView.onPause(): that suspends timers and
        // can stop audio. The TV may background the app while music plays.
        super.onPause();
    }

    @Override
    protected void onStop() {
        flushCookies();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        cookieHandler.removeCallbacks(cookieFlusher);
        flushCookies();
        if (webView != null) {
            webView.removeJavascriptInterface(WebAppBridge.NAME);
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        if (webView != null) {
            webView.saveState(outState);
        }
        super.onSaveInstanceState(outState);
    }

    // ---------------------------------------------------------------------
    // Remote: back button and focus
    // ---------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            return handleBackPressed();
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        handleBackPressed();
    }

    private boolean handleBackPressed() {
        if (errorOverlay.getVisibility() == View.VISIBLE) {
            // The WebView is showing its own error page underneath; leaving
            // that visible would strand the user, so exit instead.
            finish();
            return true;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastBackPress <= DOUBLE_BACK_WINDOW_MS) {
            finish();
            return true;
        }
        lastBackPress = now;
        Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).show();
        return true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onWindowFocusChanged(" + hasFocus + ")");
        }
        // Do not call webView.requestFocus() here. Re-requesting view focus
        // while the IME owns the window can clear the DOM editor's focus on
        // some TV firmware, leaving the keyboard attached to nothing.
    }

    // ---------------------------------------------------------------------
    // Soft keyboard
    // ---------------------------------------------------------------------

    /**
     * Called from the injected helper when a text field receives DOM focus.
     * SHOW_FORCED is used because TV remotes register as hardware keyboards,
     * which makes Android suppress the soft IME unless forced.
     */
    void showKeyboard() {
        if (webView == null) {
            return;
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "showKeyboard()");
        }

        final InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) {
            return;
        }

        // Do not call webView.requestFocus() here: the WebView already owns
        // focus when a DOM field is focused, and re-requesting it makes some
        // WebView builds re-focus the editor, producing a focus/IME loop.
        webView.postDelayed(() -> {
            if (webView == null) {
                return;
            }
            if (!isImeVisible()) {
                imm.showSoftInput(webView, InputMethodManager.SHOW_FORCED);
            }
        }, 80L);

        // Some boxes ignore the first request while the remote's key-up is
        // still being processed; retry once.
        webView.postDelayed(() -> {
            if (webView != null && !isImeVisible()) {
                imm.showSoftInput(webView, 0);
            }
        }, 500L);
    }

    void hideKeyboard() {
        if (webView == null) {
            return;
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "hideKeyboard()");
        }
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(webView.getWindowToken(), 0);
        }
    }

    private boolean isImeVisible() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
            return insets != null && insets.isVisible(WindowInsets.Type.ime());
        }
        Rect visibleFrame = new Rect();
        getWindow().getDecorView().getWindowVisibleDisplayFrame(visibleFrame);
        int screenHeight = getWindow().getDecorView().getRootView().getHeight();
        if (screenHeight <= 0) {
            return false;
        }
        return (screenHeight - visibleFrame.bottom) > screenHeight * 0.15f;
    }

    // ---------------------------------------------------------------------
    // Loading / error / injected helper
    // ---------------------------------------------------------------------

    void onPageReady() {
        hideLoading();
        injectEnhancer();
    }

    private void injectEnhancer() {
        if (webView == null || enhancerScript.isEmpty()) {
            Log.w(TAG, "Enhancer asset is empty; D-pad enhancements disabled");
            return;
        }
        webView.evaluateJavascript(enhancerScript, null);
    }

    private void showLoading() {
        loadingOverlay.animate().cancel();
        loadingOverlay.setAlpha(1f);
        loadingOverlay.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        hideLoading(false);
    }

    private void hideLoading(boolean immediate) {
        if (loadingOverlay.getVisibility() != View.VISIBLE) {
            return;
        }
        loadingOverlay.animate().cancel();
        if (immediate) {
            loadingOverlay.setVisibility(View.GONE);
            loadingOverlay.setAlpha(1f);
            return;
        }
        loadingOverlay.animate()
                .alpha(0f)
                .setDuration(220L)
                .withEndAction(() -> {
                    loadingOverlay.setVisibility(View.GONE);
                    loadingOverlay.setAlpha(1f);
                })
                .start();
    }

    private void showError() {
        if (pageError && errorOverlay.getVisibility() == View.VISIBLE) {
            return;
        }
        pageError = true;
        topProgress.setVisibility(View.GONE);
        hideLoading(true);
        errorMessage.setText(isOnline() ? R.string.error_body : R.string.error_body_offline);
        errorOverlay.setVisibility(View.VISIBLE);
        retryButton.requestFocus();
    }

    private void hideError() {
        errorOverlay.setVisibility(View.GONE);
        if (webView != null) {
            webView.requestFocus();
        }
    }

    private void retry() {
        hideError();
        if (!isOnline()) {
            showError();
            return;
        }
        String url = webView == null ? null : webView.getUrl();
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            url = START_URL;
        }
        if (webView != null) {
            webView.loadUrl(url);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean isOnline() {
        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) {
                return false;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null
                    && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
        }
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private String readAsset(String name) {
        StringBuilder out = new StringBuilder();
        try (InputStream in = getAssets().open(name);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        } catch (IOException e) {
            Log.e(TAG, "Unable to read asset " + name, e);
        }
        return out.toString();
    }
}
