package com.boomys.jarvis;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Kırmızı arc-reactor HUD çizimi: dönen halkalar + nabız gibi atan çekirdek. */
public class ReactorView extends View {

    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private float phase = 0f;
    private int state = 0; // 0 hazır, 1 dinliyor, 2 düşünüyor, 3 konuşuyor

    public ReactorView(Context context) {
        super(context);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1.2f));
        ringPaint.setColor(Color.parseColor("#66FF2B3D"));

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(dp(2f));
        tickPaint.setColor(Color.parseColor("#CCFF2B3D"));

        corePaint.setStyle(Paint.Style.FILL);
        corePaint.setColor(Color.parseColor("#FF9B0D1B"));

        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setColor(Color.parseColor("#33FF2B3D"));

        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(dp(11f));
        labelPaint.setFakeBoldText(true);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setState(int s) {
        this.state = s;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float max = Math.min(cx, cy) - dp(4f);

        phase += (state == 1 ? 2.4f : state == 2 ? 4.2f : 1.1f);
        float pulse = (float) (0.5 + 0.5 * Math.sin(Math.toRadians(phase * 2)));

        // dış dönen yay
        float r1 = max * 0.94f;
        arcRect.set(cx - r1, cy - r1, cx + r1, cy + r1);
        canvas.save();
        canvas.rotate(phase, cx, cy);
        ringPaint.setAlpha(150);
        canvas.drawArc(arcRect, 0, 120, false, ringPaint);
        canvas.drawArc(arcRect, 180, 120, false, ringPaint);
        canvas.restore();

        // tikli çember
        int ticks = 48;
        for (int i = 0; i < ticks; i++) {
            float angle = (float) Math.toRadians(i * (360f / ticks) + phase * 0.35f);
            float rOut = max * 0.82f;
            float rIn = rOut - (i % 4 == 0 ? dp(9f) : dp(4f));
            tickPaint.setAlpha(i % 4 == 0 ? 200 : 90);
            canvas.drawLine(
                    cx + (float) Math.cos(angle) * rIn, cy + (float) Math.sin(angle) * rIn,
                    cx + (float) Math.cos(angle) * rOut, cy + (float) Math.sin(angle) * rOut,
                    tickPaint);
        }

        // orta halka
        float r2 = max * 0.62f;
        arcRect.set(cx - r2, cy - r2, cx + r2, cy + r2);
        ringPaint.setAlpha(220);
        canvas.save();
        canvas.rotate(-phase * 1.5f, cx, cy);
        canvas.drawArc(arcRect, 40, 250, false, ringPaint);
        canvas.restore();

        // çekirdek + parlama
        float coreR = max * 0.40f + dp(4f) * pulse;
        glowPaint.setAlpha((int) (60 + 80 * pulse));
        canvas.drawCircle(cx, cy, coreR * 1.34f, glowPaint);
        corePaint.setAlpha((int) (170 + 70 * pulse));
        canvas.drawCircle(cx, cy, coreR, corePaint);
        canvas.drawCircle(cx, cy, coreR * 0.72f, glowPaint);

        // durum yazısı
        String label = state == 1 ? "DİNLİYOR" : state == 2 ? "DÜŞÜNÜYOR" : state == 3 ? "KONUŞUYOR" : "HAZIR";
        canvas.drawText(label, cx, cy + dp(4f), labelPaint);

        if (!isInEditMode()) postInvalidateOnAnimation();
    }
}
