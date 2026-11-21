package com.midairlogn.seudoorunlock.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.midairlogn.seudoorunlock.R;

/**
 * Custom ambient animation view rendered behind the status orb in MainActivity.
 * Draws mode-specific ripples, wireless arcs, and orbital connection indicators.
 */
public class StatusPulseView extends View {

    public enum Mode {
        IDLE,
        NFC_READY,
        BLE_READY,
        CONNECTING,
        SUCCESS,
        ERROR
    }

    private static final long NFC_CYCLE_MS = 2600L;
    private static final long BLE_CYCLE_MS = 2800L;
    private static final long CONNECTING_CYCLE_MS = 1200L;
    private static final long BURST_DURATION_MS = 650L;

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();

    private Mode mode = Mode.IDLE;
    @ColorInt
    private int accentColor;
    private float innerRadiusPx = 0f;
    private float progress = 0f;
    private ValueAnimator animator;

    public StatusPulseView(Context context) {
        super(context);
        init(context);
    }

    public StatusPulseView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public StatusPulseView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        accentColor = ContextCompat.getColor(context, R.color.primary);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    public void setInnerRadiusPx(float innerRadiusPx) {
        if (this.innerRadiusPx != innerRadiusPx) {
            this.innerRadiusPx = innerRadiusPx;
            invalidate();
        }
    }

    public void setAccentColor(@ColorInt int color) {
        if (this.accentColor != color) {
            this.accentColor = color;
            invalidate();
        }
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(@NonNull Mode newMode) {
        if (this.mode == newMode && isAnimatorRunning()) {
            return;
        }
        this.mode = newMode;
        restartAnimator();
    }

    private boolean isAnimatorRunning() {
        return animator != null && animator.isRunning();
    }

    private void cancelAnimator() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private void restartAnimator() {
        cancelAnimator();
        progress = 0f;

        if (mode == Mode.IDLE || !ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) {
            invalidate();
            return;
        }

        if (mode == Mode.SUCCESS || mode == Mode.ERROR) {
            ValueAnimator burst = ValueAnimator.ofFloat(0f, 1f);
            burst.setDuration(BURST_DURATION_MS);
            burst.setInterpolator(new DecelerateInterpolator(1.6f));
            burst.addUpdateListener(anim -> {
                progress = (float) anim.getAnimatedValue();
                invalidate();
            });
            burst.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (mode == Mode.SUCCESS || mode == Mode.ERROR) {
                        mode = Mode.IDLE;
                        progress = 0f;
                        invalidate();
                    }
                }
            });
            animator = burst;
            animator.start();
            return;
        }

        long duration;
        switch (mode) {
            case NFC_READY:
                duration = NFC_CYCLE_MS;
                break;
            case BLE_READY:
                duration = BLE_CYCLE_MS;
                break;
            case CONNECTING:
            default:
                duration = CONNECTING_CYCLE_MS;
                break;
        }

        ValueAnimator loop = ValueAnimator.ofFloat(0f, 1f);
        loop.setDuration(duration);
        loop.setRepeatCount(ValueAnimator.INFINITE);
        loop.setRepeatMode(ValueAnimator.RESTART);
        loop.setInterpolator(new LinearInterpolator());
        loop.addUpdateListener(anim -> {
            progress = (float) anim.getAnimatedValue();
            invalidate();
        });
        animator = loop;
        animator.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mode != Mode.IDLE && !isAnimatorRunning()) {
            restartAnimator();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnimator();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            if (mode != Mode.IDLE && !isAnimatorRunning() && isAttachedToWindow()) {
                restartAnimator();
            }
        } else {
            cancelAnimator();
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (mode == Mode.IDLE) {
            return;
        }

        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }

        float cx = w * 0.5f;
        float cy = h * 0.5f;
        float maxRadius = Math.min(w, h) * 0.48f;
        float baseRadius = innerRadiusPx > 0f ? innerRadiusPx : maxRadius * 0.64f;
        float density = getResources().getDisplayMetrics().density;

        switch (mode) {
            case NFC_READY:
                drawNfcRipples(canvas, cx, cy, baseRadius, maxRadius, density);
                break;
            case BLE_READY:
                drawBleWaves(canvas, cx, cy, baseRadius, maxRadius, density);
                break;
            case CONNECTING:
                drawConnectingOrbit(canvas, cx, cy, baseRadius, maxRadius, density);
                break;
            case SUCCESS:
            case ERROR:
                drawBurst(canvas, cx, cy, baseRadius, maxRadius, density);
                break;
            case IDLE:
            default:
                break;
        }
    }

    private void drawNfcRipples(Canvas canvas, float cx, float cy,
                                float baseRadius, float maxRadius, float density) {
        final int waveCount = 3;
        float startR = baseRadius * 0.94f;
        float travel = Math.max(1f, maxRadius - startR);

        for (int i = 0; i < waveCount; i++) {
            float phase = (progress + (float) i / waveCount) % 1.0f;
            float eased = 1f - (float) Math.pow(1f - phase, 2.2);
            float radius = startR + travel * eased;

            float alphaFactor;
            if (phase < 0.16f) {
                alphaFactor = phase / 0.16f;
            } else {
                alphaFactor = (float) Math.pow(1f - ((phase - 0.16f) / 0.84f), 1.4);
            }

            int alpha = Math.round(72f * alphaFactor);
            if (alpha <= 0) continue;

            strokePaint.setColor(accentColor);
            strokePaint.setAlpha(alpha);
            strokePaint.setStrokeWidth((1.8f - 0.6f * eased) * density);
            canvas.drawCircle(cx, cy, radius, strokePaint);
        }
    }

    private void drawBleWaves(Canvas canvas, float cx, float cy,
                              float baseRadius, float maxRadius, float density) {
        // Soft ambient breathing halo
        float breath = 0.5f - 0.5f * (float) Math.cos(progress * Math.PI * 2.0);
        fillPaint.setColor(accentColor);
        fillPaint.setAlpha(Math.round(14f + 16f * breath));
        float haloRadius = baseRadius + (maxRadius - baseRadius) * (0.28f + 0.18f * breath);
        canvas.drawCircle(cx, cy, haloRadius, fillPaint);

        // Two staggered pairs of symmetric wireless arcs (left & right)
        final int waveCount = 2;
        float startR = baseRadius + 4f * density;
        float travel = Math.max(1f, maxRadius - startR);

        for (int i = 0; i < waveCount; i++) {
            float phase = (progress + (float) i / waveCount) % 1.0f;
            float eased = 1f - (float) Math.pow(1f - phase, 2.0);
            float radius = startR + travel * eased;

            float alphaFactor = phase < 0.2f
                    ? phase / 0.2f
                    : (float) Math.pow(1f - ((phase - 0.2f) / 0.8f), 1.5);
            int alpha = Math.round(88f * alphaFactor);
            if (alpha <= 0) continue;

            strokePaint.setColor(accentColor);
            strokePaint.setAlpha(alpha);
            strokePaint.setStrokeWidth((2.2f - 0.7f * eased) * density);

            arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
            float sweep = 72f - 14f * eased;
            canvas.drawArc(arcBounds, -sweep * 0.5f, sweep, false, strokePaint);
            canvas.drawArc(arcBounds, 180f - sweep * 0.5f, sweep, false, strokePaint);
        }
    }

    private void drawConnectingOrbit(Canvas canvas, float cx, float cy,
                                     float baseRadius, float maxRadius, float density) {
        // Subtle pulsing outer ring
        float pulse = 0.5f - 0.5f * (float) Math.cos(progress * Math.PI * 2.0);
        float orbitRadius = Math.min(maxRadius - 3f * density, baseRadius + 8f * density);

        strokePaint.setColor(accentColor);
        strokePaint.setAlpha(Math.round(28f + 22f * pulse));
        strokePaint.setStrokeWidth(2.0f * density);
        canvas.drawCircle(cx, cy, orbitRadius, strokePaint);

        // Smooth rotating primary arc
        float startAngle = progress * 360f - 90f;
        float sweepAngle = 68f + 44f * (float) Math.sin(progress * Math.PI * 2.0);

        arcBounds.set(cx - orbitRadius, cy - orbitRadius, cx + orbitRadius, cy + orbitRadius);
        strokePaint.setAlpha(220);
        strokePaint.setStrokeWidth(3.0f * density);
        canvas.drawArc(arcBounds, startAngle, sweepAngle, false, strokePaint);

        // Secondary trailing arc opposite side for balanced elegance
        strokePaint.setAlpha(95);
        strokePaint.setStrokeWidth(2.2f * density);
        canvas.drawArc(arcBounds, startAngle + 180f, sweepAngle * 0.55f, false, strokePaint);
    }

    private void drawBurst(Canvas canvas, float cx, float cy,
                           float baseRadius, float maxRadius, float density) {
        float radius = baseRadius * 0.95f + (maxRadius - baseRadius * 0.95f) * progress;
        int alpha = Math.round(135f * (1f - progress));
        if (alpha <= 0) return;

        strokePaint.setColor(accentColor);
        strokePaint.setAlpha(alpha);
        strokePaint.setStrokeWidth((3.0f - 1.8f * progress) * density);
        canvas.drawCircle(cx, cy, radius, strokePaint);
    }
}
