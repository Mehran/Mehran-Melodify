package app.melodify.tv;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Music-themed intro shown on cold start: the logo pops in, the wordmark fades up
 * and an equalizer animates underneath. After a short beat it hands over to the
 * WebView, so the intro never delays access beyond a couple of seconds, and any
 * remote key skips it immediately.
 *
 * The activity finishes itself right after launching {@link MainActivity}, which
 * leaves MainActivity as the task root: returning from HOME resumes the app
 * directly without replaying the intro.
 */
public class SplashActivity extends AppCompatActivity {

    private static final long HOLD_MS = 2400L;

    /**
     * True once the intro has played in this process. Reopening the app from the
     * TV launcher then skips straight to the player, so returning from HOME never
     * flashes a splash over music that is still playing. A fresh process (cold
     * start, reboot, "force stop") shows the intro again.
     */
    private static boolean introShown;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable launchRunnable = this::launchMain;

    private EqualizerView equalizer;
    private boolean launched;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (introShown) {
            launchMain();
            return;
        }
        introShown = true;

        setContentView(R.layout.activity_splash);

        ImageView logo = findViewById(R.id.splash_logo);
        TextView title = findViewById(R.id.splash_title);
        TextView tagline = findViewById(R.id.splash_tagline);
        TextView hint = findViewById(R.id.splash_hint);
        equalizer = findViewById(R.id.splash_equalizer);

        // Logo: quick scale + fade so the mark "lands" like a beat.
        logo.setAlpha(0f);
        logo.setScaleX(0.80f);
        logo.setScaleY(0.80f);
        logo.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay(90L)
                .setDuration(620L)
                .setInterpolator(new OvershootInterpolator(1.6f))
                .start();

        animateUp(title, 260L);
        animateUp(tagline, 400L);
        animateUp(equalizer, 340L);
        animateUp(hint, 620L);

        equalizer.start();
        handler.postDelayed(launchRunnable, HOLD_MS);
    }

    private void animateUp(View view, long delayMs) {
        view.setAlpha(0f);
        view.setTranslationY(dp(14));
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(520L)
                .start();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void launchMain() {
        if (launched || isFinishing()) {
            return;
        }
        launched = true;
        handler.removeCallbacks(launchRunnable);
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // BACK leaves the app, anything else (OK / arrows) jumps straight in.
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            handler.removeCallbacks(launchRunnable);
            // The process outlives this exit, so clear the flag: opening the app
            // again must still show the intro.
            introShown = false;
            finish();
            return true;
        }
        launchMain();
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        launchMain();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(launchRunnable);
        if (equalizer != null) {
            equalizer.stop();
        }
        super.onDestroy();
    }
}
