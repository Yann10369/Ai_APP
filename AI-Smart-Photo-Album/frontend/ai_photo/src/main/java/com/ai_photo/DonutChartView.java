package com.ai_photo;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 简易 Donut Chart (环形图)：
 * - 最多 5 段，每段指定颜色 + 百分比 (整数，合计应为 100)。
 * - 中心镂空，仅绘制彩色环。
 * - 通过 XML 属性 (dcvColor1..5 / dcvPct1..5) 声明数据。
 * - 也支持运行时通过 setData() 覆盖。
 */
public class DonutChartView extends View {

    /** 段数据 */
    public static class Segment {
        public final int color;
        public final int percent; // 0-100
        public Segment(int color, int percent) {
            this.color = color;
            this.percent = percent;
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();
    private final int[] colors = new int[5];
    private final int[] percents = new int[5];
    private int strokeWidthPx;

    public DonutChartView(Context context) {
        this(context, null);
    }

    public DonutChartView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DonutChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        // 默认配色 (蓝 / 绿 / 紫 / 黄 / 粉)
        colors[0] = 0xFF4A90E2;
        colors[1] = 0xFF00C4B6;
        colors[2] = 0xFF9013FE;
        colors[3] = 0xFFFFB74D;
        colors[4] = 0xFFFF7AAE;
        percents[0] = 32;
        percents[1] = 24;
        percents[2] = 18;
        percents[3] = 16;
        percents[4] = 10;
        strokeWidthPx = dp(8);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.DonutChartView);
            colors[0] = a.getColor(R.styleable.DonutChartView_dcvColor1, colors[0]);
            colors[1] = a.getColor(R.styleable.DonutChartView_dcvColor2, colors[1]);
            colors[2] = a.getColor(R.styleable.DonutChartView_dcvColor3, colors[2]);
            colors[3] = a.getColor(R.styleable.DonutChartView_dcvColor4, colors[3]);
            colors[4] = a.getColor(R.styleable.DonutChartView_dcvColor5, colors[4]);
            percents[0] = a.getInt(R.styleable.DonutChartView_dcvPct1, percents[0]);
            percents[1] = a.getInt(R.styleable.DonutChartView_dcvPct2, percents[1]);
            percents[2] = a.getInt(R.styleable.DonutChartView_dcvPct3, percents[2]);
            percents[3] = a.getInt(R.styleable.DonutChartView_dcvPct4, percents[3]);
            percents[4] = a.getInt(R.styleable.DonutChartView_dcvPct5, percents[4]);
            strokeWidthPx = a.getDimensionPixelSize(
                    R.styleable.DonutChartView_dcvStrokeWidth, strokeWidthPx);
            a.recycle();
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    /** 运行时覆盖数据 (Segment 列表) */
    public void setData(Segment[] data) {
        if (data == null) return;
        int n = Math.min(data.length, 5);
        for (int i = 0; i < 5; i++) {
            if (i < n) {
                colors[i] = data[i].color;
                percents[i] = data[i].percent;
            } else {
                colors[i] = Color.TRANSPARENT;
                percents[i] = 0;
            }
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        int size = Math.min(w, h);
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = size / 2f - strokeWidthPx / 2f;
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        paint.setStrokeWidth(strokeWidthPx);

        // 起始角度：从 12 点钟方向 (-90°) 开始，顺时针
        float startAngle = -90f;
        for (int i = 0; i < 5; i++) {
            if (percents[i] <= 0) continue;
            float sweep = percents[i] * 360f / 100f;
            paint.setColor(colors[i]);
            canvas.drawArc(arcRect, startAngle, sweep, false, paint);
            startAngle += sweep;
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
