package com.skygemini.drone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Cyberpunk Cockpit HUD Overlay View.
 * Renders artificial horizon, pitch ladder, center reticle, and telemetry.
 */
public class HudOverlayView extends View {

    private Paint cyanPaint;
    private Paint cyanFillPaint;
    private Paint greenPaint;
    private Paint yellowPaint;
    private Paint textPaint;
    private Paint smallTextPaint;

    private float pitch = 0f; // -1 to 1
    private float roll = 0f;  // -1 to 1
    private float yaw = 0f;   // -1 to 1
    private float throttle = 0f; // 0 to 1

    private Path bracketPath = new Path();

    public HudOverlayView(Context context) {
        super(context);
        init();
    }

    public HudOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public HudOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        cyanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cyanPaint.setColor(Color.parseColor("#9900F0FF"));
        cyanPaint.setStyle(Paint.Style.STROKE);
        cyanPaint.setStrokeWidth(2f);

        cyanFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cyanFillPaint.setColor(Color.parseColor("#3300F0FF"));
        cyanFillPaint.setStyle(Paint.Style.FILL);

        greenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        greenPaint.setColor(Color.parseColor("#CC00FF88"));
        greenPaint.setStyle(Paint.Style.STROKE);
        greenPaint.setStrokeWidth(2f);

        yellowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        yellowPaint.setColor(Color.parseColor("#EEFFCC00"));
        yellowPaint.setStyle(Paint.Style.STROKE);
        yellowPaint.setStrokeWidth(2.5f);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.parseColor("#CC00F0FF"));
        textPaint.setTextSize(22f);
        textPaint.setFakeBoldText(true);

        smallTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        smallTextPaint.setColor(Color.parseColor("#AA00FF88"));
        smallTextPaint.setTextSize(18f);
    }

    public void updateFlightTelemetry(float pitch, float roll, float yaw, float throttle) {
        this.pitch = pitch;
        this.roll = roll;
        this.yaw = yaw;
        this.throttle = throttle;
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

        // 1. Draw Corner HUD Brackets
        drawCornerBrackets(canvas, w, h);

        // 2. Draw Center Crosshair / Aim Reticle
        drawCenterReticle(canvas, cx, cy);

        // 3. Draw Artificial Horizon & Pitch Ladder
        drawArtificialHorizon(canvas, cx, cy);

        // 4. Draw Telemetry Data on Sides
        drawTelemetry(canvas, w, h);
    }

    private void drawCornerBrackets(Canvas canvas, int w, int h) {
        float len = 40f;
        float pad = 12f;

        // Top-Left
        canvas.drawLine(pad, pad, pad + len, pad, cyanPaint);
        canvas.drawLine(pad, pad, pad, pad + len, cyanPaint);

        // Top-Right
        canvas.drawLine(w - pad - len, pad, w - pad, pad, cyanPaint);
        canvas.drawLine(w - pad, pad, w - pad, pad + len, cyanPaint);

        // Bottom-Left
        canvas.drawLine(pad, h - pad - len, pad, h - pad, cyanPaint);
        canvas.drawLine(pad, h - pad, pad + len, h - pad, cyanPaint);

        // Bottom-Right
        canvas.drawLine(w - pad - len, h - pad, w - pad, h - pad, cyanPaint);
        canvas.drawLine(w - pad, h - pad - len, w - pad, h - pad, cyanPaint);
    }

    private void drawCenterReticle(Canvas canvas, float cx, float cy) {
        float r = 24f;
        // Inner circle
        canvas.drawCircle(cx, cy, r, cyanPaint);
        // Center pip
        canvas.drawCircle(cx, cy, 3f, yellowPaint);
        // Cross tick marks
        canvas.drawLine(cx - r - 15f, cy, cx - r + 5f, cy, cyanPaint);
        canvas.drawLine(cx + r - 5f, cy, cx + r + 15f, cy, cyanPaint);
        canvas.drawLine(cx, cy - r - 15f, cx, cy - r + 5f, cyanPaint);
        canvas.drawLine(cx, cy + r - 5f, cx, cy + r + 15f, cyanPaint);
    }

    private void drawArtificialHorizon(Canvas canvas, float cx, float cy) {
        canvas.save();
        // Roll angle in degrees: roll is -1 to 1, maps to -45 to +45 deg
        float rollDeg = roll * 35f;
        // Pitch offset in pixels: pitch is -1 to 1, maps to -100 to +100 px
        float pitchOffset = pitch * 90f;

        canvas.rotate(-rollDeg, cx, cy);
        canvas.translate(0, -pitchOffset);

        // Main horizon line
        float hLen = 140f;
        canvas.drawLine(cx - hLen, cy, cx - 35f, cy, yellowPaint);
        canvas.drawLine(cx + 35f, cy, cx + hLen, cy, yellowPaint);

        // Downward vertical notch on horizon line
        canvas.drawLine(cx - hLen, cy, cx - hLen, cy + 10f, yellowPaint);
        canvas.drawLine(cx + hLen, cy, cx + hLen, cy + 10f, yellowPaint);

        // Pitch +10 deg line
        float p10 = 50f;
        canvas.drawLine(cx - 70f, cy - p10, cx - 25f, cy - p10, cyanPaint);
        canvas.drawLine(cx + 25f, cy - p10, cx + 70f, cy - p10, cyanPaint);

        // Pitch -10 deg line (dashed-like)
        canvas.drawLine(cx - 70f, cy + p10, cx - 25f, cy + p10, cyanPaint);
        canvas.drawLine(cx + 25f, cy + p10, cx + 70f, cy + p10, cyanPaint);

        canvas.restore();
    }

    private void drawTelemetry(Canvas canvas, int w, int h) {
        // Left telemetry (Altitude / Throttle)
        float startY = h * 0.35f;
        canvas.drawText("THR: " + (int)(throttle * 100) + "%", 30f, startY, smallTextPaint);
        canvas.drawText("YAW: " + (int)(yaw * 100) + "°", 30f, startY + 30f, smallTextPaint);

        // Right telemetry (Pitch / Roll)
        canvas.drawText("PITCH: " + (int)(pitch * 100) + "°", w - 160f, startY, smallTextPaint);
        canvas.drawText("ROLL:  " + (int)(roll * 100) + "°", w - 160f, startY + 30f, smallTextPaint);
    }
}
