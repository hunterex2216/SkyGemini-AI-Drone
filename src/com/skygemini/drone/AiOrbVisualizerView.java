package com.skygemini.drone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * Holographic Gemini AI Visualizer Orb.
 * Renders an interactive pulsing cyber orb indicating:
 * - Offline / Connecting: Dim amber pulse
 * - Idle / Listening: Cyan breathing pulse
 * - User Speaking: Vibrant emerald green ripples
 * - Gemini Speaking: Electric violet & cyan audio waveform pulses
 */
public class AiOrbVisualizerView extends View {

    private Paint glowPaint;
    private Paint corePaint;
    private Paint ringPaint;
    private Paint wavePaint;

    private boolean isConnected = false;
    private boolean isAiSpeaking = false;
    private boolean isUserSpeaking = false;

    private float animPhase = 0f;
    private RadialGradient cachedGlowShader;
    private int lastGlowColor = 0;
    private float lastGlowR = 0f;
    private RadialGradient cachedCoreShader;
    private int lastCoreColor = 0;
    private float lastCoreR = 0f;

    public AiOrbVisualizerView(Context context) {
        super(context);
        init();
    }

    public AiOrbVisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AiOrbVisualizerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringPaint.setStyle(Paint.Style.STROKE);
        wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        wavePaint.setStyle(Paint.Style.STROKE);
    }

    public void setStates(boolean connected, boolean aiSpeaking, boolean userSpeaking) {
        this.isConnected = connected;
        this.isAiSpeaking = aiSpeaking;
        this.isUserSpeaking = userSpeaking;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float cx = w / 2f;
        float cy = h / 2f;
        float baseR = Math.min(w, h) * 0.32f;

        long time = SystemClock.uptimeMillis();
        animPhase = (time % 2000) / 2000f; // 0.0 to 1.0

        int mainColor;
        int glowColor;

        if (!isConnected) {
            mainColor = Color.parseColor("#FFCC00"); // Yellow/Amber
            glowColor = Color.parseColor("#33FFCC00");
        } else if (isAiSpeaking) {
            mainColor = Color.parseColor("#A855F7"); // Purple/Violet
            glowColor = Color.parseColor("#44A855F7");
        } else if (isUserSpeaking) {
            mainColor = Color.parseColor("#00FF88"); // Green
            glowColor = Color.parseColor("#4400FF88");
        } else {
            mainColor = Color.parseColor("#00F0FF"); // Cyan
            glowColor = Color.parseColor("#3300F0FF");
        }

        // 1. Outer Pulse Waves (expanding circles)
        float pulseSpeed = (isAiSpeaking || isUserSpeaking) ? 2.5f : 1.0f;
        float p1 = ((time * pulseSpeed) % 1500) / 1500f;
        float p2 = (((time * pulseSpeed) + 750) % 1500) / 1500f;

        wavePaint.setColor(mainColor);
        wavePaint.setStrokeWidth(2f);
        wavePaint.setAlpha((int) ((1f - p1) * 120));
        canvas.drawCircle(cx, cy, baseR + p1 * baseR * 0.9f, wavePaint);

        wavePaint.setAlpha((int) ((1f - p2) * 120));
        canvas.drawCircle(cx, cy, baseR + p2 * baseR * 0.9f, wavePaint);

        // 2. Diffuse Radial Glow
        float glowRadius = baseR * 1.6f;
        if (cachedGlowShader == null || lastGlowColor != glowColor || Math.abs(lastGlowR - glowRadius) > 1f) {
            cachedGlowShader = new RadialGradient(cx, cy, glowRadius,
                    glowColor, Color.TRANSPARENT, Shader.TileMode.CLAMP);
            lastGlowColor = glowColor;
            lastGlowR = glowRadius;
        }
        glowPaint.setShader(cachedGlowShader);
        canvas.drawCircle(cx, cy, glowRadius, glowPaint);

        // 3. Rotating Outer Arc Rings
        ringPaint.setColor(mainColor);
        ringPaint.setStrokeWidth(2.5f);
        ringPaint.setAlpha(200);

        canvas.save();
        float rotAngle = (time % 4000) / 4000f * 360f;
        canvas.rotate(rotAngle, cx, cy);
        canvas.drawCircle(cx, cy, baseR * 1.05f, ringPaint);
        canvas.restore();

        // 4. Solid Core Sphere with Breathing Pulse
        float breath = (float) (Math.sin(animPhase * Math.PI * 2.0) * 0.08);
        float currentCoreR = baseR * (0.85f + breath);
        if (cachedCoreShader == null || lastCoreColor != mainColor || Math.abs(lastCoreR - currentCoreR) > 2f) {
            cachedCoreShader = new RadialGradient(cx - currentCoreR * 0.2f, cy - currentCoreR * 0.2f,
                    currentCoreR, Color.WHITE, mainColor, Shader.TileMode.CLAMP);
            lastCoreColor = mainColor;
            lastCoreR = currentCoreR;
        }
        corePaint.setShader(cachedCoreShader);
        canvas.drawCircle(cx, cy, currentCoreR, corePaint);

        // Throttle redraw to 30 FPS (~33ms) for smooth animation without burning CPU/GPU
        postInvalidateDelayed(33);
    }
}
