package com.nota.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ScrollView;

/**
 * A ScrollView that can be told to keep its hands off touches.
 *
 * <p>The lyrics list lives inside it and scrolls on its own; without this the page would
 * take every vertical drag before the list ever saw one.
 */
public class LockScrollView extends ScrollView {

    private boolean locked;

    public LockScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setLocked(boolean value) {
        locked = value;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return !locked && super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return !locked && super.onTouchEvent(event);
    }
}
