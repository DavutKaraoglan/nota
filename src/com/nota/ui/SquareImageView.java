package com.nota.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * Keeps album art square. A grid cell leaves the height open and the width decides; the player
 * gives it what is left between the top bar and the controls, where the height can be the
 * smaller of the two and squaring off the width alone would push the picture past the screen.
 */
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
        int side = MeasureSpec.getSize(widthSpec);
        if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
            side = Math.min(side, MeasureSpec.getSize(heightSpec));
        }
        int square = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY);
        super.onMeasure(square, square);
    }
}
