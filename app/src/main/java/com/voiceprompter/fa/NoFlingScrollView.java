package com.voiceprompter.fa;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

/** A ScrollView that stops where the finger lifts, so the prompter stays in control. */
public class NoFlingScrollView extends ScrollView {
    public NoFlingScrollView(Context context) { super(context); }

    public NoFlingScrollView(Context context, AttributeSet attrs) { super(context, attrs); }

    public NoFlingScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void fling(int velocityY) {
        // no momentum scrolling
    }
}
