package com.nota.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

/** Keeps album art square inside a GridView cell whose height is unconstrained. */
public class SquareImageView extends ImageView {
    public SquareImageView(Context c) {
        super(c);
    }

    public SquareImageView(Context c, AttributeSet a) {
        super(c, a);
    }

    public SquareImageView(Context c, AttributeSet a, int s) {
        super(c, a, s);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, widthSpec);
    }
}
