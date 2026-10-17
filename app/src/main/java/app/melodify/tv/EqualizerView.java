package app.melodify.tv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Simple animated audio-spectrum used by the intro screen: a row of rounded bars
 * that rise and fall on a sine wave, tinted with the app's green/teal gradient.
 * Everything is drawn on the canvas so the intro needs no image assets or XML
 * animation resources, and it keeps animating smoothly on low-end TV boxes.
 */
public class EqualizerView extends View {

    private static final int BAR_COUNT = 7;
    private static final long PERIOD_MS = 1500L;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float[] heights = new float[BAR_COUNT];

    private ValueAnimator animator;
    private float phase;
    private boolean running;

    public EqualizerView(Context context) {
        super(context);
        init();
    }

    public EqualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EqualizerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < BAR_COUNT; i++) {
            heights[i] = 0.25f;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        paint.setShader(new LinearGradient(
                0, 0, w, 0,
                new int[]{0xFF1ED760, 0xFF35E0A4, 0xFF0E9F8E},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
    }

    /** Start the loop. Safe to call repeatedly. */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        animator = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        animator.setDuration(PERIOD_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    /** Stop the loop and release the animator (called from the host activity). */
    public void stop() {
        running = false;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float w = getWidth();
        final float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        final float gap = w * 0.018f;
        final float barWidth = (w - gap * (BAR_COUNT - 1)) / BAR_COUNT;
        final float radius = Math.min(barWidth / 2f, h * 0.12f);
        final float maxBar = h;

        for (int i = 0; i < BAR_COUNT; i++) {
            // Two out-of-phase waves so the meter never freezes in a flat line.
            float wave = (float) Math.sin(phase + i * 0.82f);
            float wave2 = (float) Math.sin(phase * 1.7f + i * 0.35f);
            float level = 0.30f + 0.62f * Math.abs(wave) + 0.08f * Math.abs(wave2);
            heights[i] += (Math.min(1f, level) - heights[i]) * 0.35f;

            float barHeight = Math.max(barWidth, heights[i] * maxBar);
            float left = i * (barWidth + gap);
            rect.set(left, h - barHeight, left + barWidth, h);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
    }
}
