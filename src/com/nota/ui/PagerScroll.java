package com.nota.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;

/**
 * A horizontal scroller that comes to rest on whole pages.
 *
 * <p>Its children are stretched to the width of the scroller, so a drag either turns the page or
 * gives it back; left to itself the strip would settle halfway and show two half pages at once.
 */
public class PagerScroll extends HorizontalScrollView {

    private boolean flung;

    public PagerScroll(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        ViewGroup strip = (ViewGroup) getChildAt(0);
        if (strip != null && width > 0) {
            for (int i = 0; i < strip.getChildCount(); i++) {
                View page = strip.getChildAt(i);
                if (page.getLayoutParams().width != width) {
                    page.getLayoutParams().width = width;
                }
            }
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) flung = false;
        boolean handled = super.onTouchEvent(event);
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && !flung) {
            snapTo(nearestPage());
        }
        return handled;
    }

    @Override
    public void fling(int velocityX) {
        flung = true;
        snapTo(nearestPage() + (velocityX > 0 ? 1 : -1));
    }

    private void snapTo(int page) {
        int width = pageWidth();
        if (width <= 0) return;
        int last = Math.max(0, pageCount() - 1);
        smoothScrollTo(Math.max(0, Math.min(last, page)) * width, 0);
    }

    private int nearestPage() {
        int width = pageWidth();
        return width <= 0 ? 0 : (getScrollX() + width / 2) / width;
    }

    private int pageWidth() {
        return getWidth() - getPaddingLeft() - getPaddingRight();
    }

    private int pageCount() {
        ViewGroup strip = (ViewGroup) getChildAt(0);
        return strip == null ? 0 : strip.getChildCount();
    }
}
