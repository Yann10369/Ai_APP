package com.ai_photo;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.GridView;

/**
 * 解决 GridView 在 ScrollView 中无法 wrap_content 展开的问题：
 * 重写 onMeasure 让其高度按所有子项总高计算。
 */
public class NonScrollGridView extends GridView {
    public NonScrollGridView(Context context) {
        super(context);
    }

    public NonScrollGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public NonScrollGridView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int expandSpec = MeasureSpec.makeMeasureSpec(Integer.MAX_VALUE >> 2, MeasureSpec.AT_MOST);
        super.onMeasure(widthMeasureSpec, expandSpec);
    }
}
