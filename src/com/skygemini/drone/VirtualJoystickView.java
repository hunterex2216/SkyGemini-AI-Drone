package com.skygemini.drone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Cyberpunk HUD Virtual Joystick View.
 * Provides normalized (-1.0 to +1.0) coordinates.
 * Auto-centers when released.
 */
public class VirtualJoystickView extends View {

    public interface OnJoystickMovedListener {
        void onMoved(float x, float y);
        void onReleased();
    }

    private Paint basePaint;
    private Paint baseBorderPaint;
    private Paint stickPaint;
    private Paint stickBorderPaint;
    private Paint centerDotPaint;
    private Paint linePaint;

    private float centerX = 0;
    private float centerY = 0;
    private float baseRadius = 0;
    private float stickRadius = 0;

    private float stickX = 0;
    private float stickY = 0;

    private boolean isPressed = false;
    private OnJoystickMovedListener listener;

    public VirtualJoystickView(Context context) {
        super(context);
        init();
    }

    public VirtualJoystickView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public VirtualJoystickView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        basePaint.setColor(Color.parseColor("#1500F0FF"));
        basePaint.setStyle(Paint.Style.FILL);

        baseBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        baseBorderPaint.setColor(Color.parseColor("#6600F0FF"));
        baseBorderPaint.setStyle(Paint.Style.STROKE);
        baseBorderPaint.setStrokeWidth(3f);

        stickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        stickPaint.setColor(Color.parseColor("#4400F0FF"));
        stickPaint.setStyle(Paint.Style.FILL);

        stickBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        stickBorderPaint.setColor(Color.parseColor("#FF00F0FF"));
        stickBorderPaint.setStyle(Paint.Style.STROKE);
        stickBorderPaint.setStrokeWidth(4f);

        centerDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        centerDotPaint.setColor(Color.parseColor("#FF00F0FF"));
        centerDotPaint.setStyle(Paint.Style.FILL);

        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(Color.parseColor("#3300F0FF"));
        linePaint.setStrokeWidth(2f);
    }

    public void setOnJoystickMovedListener(OnJoystickMovedListener listener) {
        this.listener = listener;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2f;
        centerY = h / 2f;
        stickX = centerX;
        stickY = centerY;
        baseRadius = Math.min(w, h) / 2f * 0.85f;
        stickRadius = baseRadius * 0.38f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Crosshairs
        canvas.drawLine(centerX - baseRadius, centerY, centerX + baseRadius, centerY, linePaint);
        canvas.drawLine(centerX, centerY - baseRadius, centerX, centerY + baseRadius, linePaint);

        // Base Circle
        canvas.drawCircle(centerX, centerY, baseRadius, basePaint);
        canvas.drawCircle(centerX, centerY, baseRadius, baseBorderPaint);

        // Inner reference circle
        canvas.drawCircle(centerX, centerY, baseRadius * 0.5f, linePaint);

        // Center dot
        canvas.drawCircle(centerX, centerY, 4f, centerDotPaint);

        // Joystick Stick Knob
        canvas.drawCircle(stickX, stickY, stickRadius, stickPaint);
        canvas.drawCircle(stickX, stickY, stickRadius, stickBorderPaint);
        canvas.drawCircle(stickX, stickY, 6f, centerDotPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getAction();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                isPressed = true;
                float dx = event.getX() - centerX;
                float dy = event.getY() - centerY;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);

                if (distance > baseRadius) {
                    stickX = centerX + (dx / distance) * baseRadius;
                    stickY = centerY + (dy / distance) * baseRadius;
                } else {
                    stickX = event.getX();
                    stickY = event.getY();
                }

                float normX = (stickX - centerX) / baseRadius;
                // Invert Y so up is positive
                float normY = -(stickY - centerY) / baseRadius;

                if (listener != null) {
                    listener.onMoved(normX, normY);
                }
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isPressed = false;
                stickX = centerX;
                stickY = centerY;
                if (listener != null) {
                    listener.onReleased();
                }
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }
}
