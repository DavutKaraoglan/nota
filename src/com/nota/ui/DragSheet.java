package com.nota.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;

/**
 * A screen that can be pushed back down with a finger.
 *
 * <p>The gesture has to be claimed before the buttons and the seek bar see it, which only a
 * parent can do, so the whole screen is this view rather than a listener on one child.
 */
public class DragSheet extends LinearLayout {

    public interface Listener {
        void onDismissed();
    }

    private final int slop;
    private final int dismissPx;
    private Listener listener;
    private View scroller;
    private boolean enabled = true;
    private float downY;
    private boolean dragging;

    public DragSheet(Context context, AttributeSet attrs) {
        super(context, attrs);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        dismissPx = Ui.dp(context, 110);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** The page below this one; the sheet only gives way once that page is back at its top. */
    public void setScroller(View view) {
        scroller = view;
    }

    /** Turned off while the lyrics list is up, so the drag does not eat its scrolling. */
    public void setDragEnabled(boolean value) {
        enabled = value;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        if (!enabled) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = event.getRawY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (scroller != null && scroller.getScrollY() > 0) break;
                if (event.getRawY() - downY > slop * 2) {
                    dragging = true;
                    return true;
                }
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!enabled) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = event.getRawY();
                return true;
            case MotionEvent.ACTION_MOVE:
                setTranslationY(Math.max(0, event.getRawY() - downY));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean far = getTranslationY() > dismissPx;
                dragging = false;
                if (far && listener != null) {
                    listener.onDismissed();
                } else {
                    animate().translationY(0).setDuration(160).start();
                }
                return true;
        }
        return dragging;
    }
}
