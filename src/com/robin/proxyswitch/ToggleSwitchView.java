package com.robin.proxyswitch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 单键开关：点一下就在“开/关”之间切换，同时用图形和文字表示当前状态。
 * 遥控器用方向键移到它上面时，会画一圈高亮描边作为焦点提示；按 OK 键触发点击。
 */
public class ToggleSwitchView extends View {

    private boolean checked = false;
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float d = 1f;

    public ToggleSwitchView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        d = getResources().getDisplayMetrics().density;
        trackPaint.setStyle(Paint.Style.FILL);
        knobPaint.setStyle(Paint.Style.FILL);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(19 * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setFakeBoldText(true);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(3));
        ringPaint.setColor(Color.parseColor("#8FBFFF"));

        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
    }

    private float dp(float v) {
        return v * d;
    }

    public void setChecked(boolean value) {
        if (checked != value) {
            checked = value;
            invalidate();
        }
    }

    public boolean isChecked() {
        return checked;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float inset = dp(5);
        RectF track = new RectF(inset, inset, w - inset, h - inset);
        float radius = track.height() / 2f;

        // 轨道：开启绿色，关闭石板灰
        trackPaint.setColor(checked ? 0xFF1E8B5C : 0xFF39465C);
        canvas.drawRoundRect(track, radius, radius, trackPaint);

        // 滑块位置
        float knobR = radius - dp(8);
        float cx = checked ? (track.right - radius) : (track.left + radius);
        float cy = h / 2f;

        knobPaint.setColor(0x44000000);
        canvas.drawCircle(cx + dp(1), cy + dp(2), knobR, knobPaint);
        knobPaint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, knobR, knobPaint);

        // 状态文字
        String label = checked ? "已开启 · 点按关闭" : "已关闭 · 点按开启";
        float textX = checked ? (track.left + dp(30)) : (track.left + radius * 2 + dp(16));
        float baseY = cy - (textPaint.ascent() + textPaint.descent()) / 2f;
        canvas.drawText(label, textX, baseY, textPaint);

        // 遥控器焦点高亮
        if (isFocused()) {
            RectF ring = new RectF(track.left - dp(3), track.top - dp(3),
                    track.right + dp(3), track.bottom + dp(3));
            canvas.drawRoundRect(ring, radius + dp(3), radius + dp(3), ringPaint);
        }
    }
}
