/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.util.AttributeSet;
import android.util.EventLog;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;

import com.android.systemui.DejankUtils;
import com.android.systemui.EventLogTags;
import com.android.systemui.R;

import com.android.systemui.statusbar.BarTransitions;

public class PhoneStatusBarView extends PanelBar {
    private static final String TAG = "PhoneStatusBarView";
    private static final boolean DEBUG = PhoneStatusBar.DEBUG;
    private static final boolean DEBUG_GESTURES = false;

    private int mHorizontalShift = 0;
    private int mVerticalShift = 0;
    private int mHorizontalDirection = 1;
    private int mVerticalDirection = 1;
    private int mBasePaddingBottom;
    private int mBasePaddingLeft;
    private int mBasePaddingRight;
    private int mBasePaddingTop;
    private int mHorizontalMaxShift = getContext().getResources().getDimensionPixelSize(R.dimen.horizontal_max_swift);
    // total of ((vertical_max_swift - 1) * 2) pixels can be moved
    private int mVerticalMaxShift = getContext().getResources().getDimensionPixelSize(R.dimen.vertical_max_swift) - 1;
    ViewGroup mStatusBarContents;

    PhoneStatusBar mBar;

    PanelView mLastFullyOpenedPanel = null;
    PanelView mNotificationPanel;
    private final PhoneStatusBarTransitions mBarTransitions;
    private ScrimController mScrimController;
    private float mMinFraction;
    private float mPanelFraction;
    private Runnable mHideExpandedRunnable = new Runnable() {
        @Override
        public void run() {
            mBar.makeExpandedInvisible();
        }
    };

    private int mShowCarrierLabel;
    private TextView mCarrierLabel;

    private ContentObserver mObserver = new ContentObserver(new Handler()) {
        public void onChange(boolean selfChange, Uri uri) {
            showStatusBarCarrier();
            updateVisibilities();
        }
    };

    public PhoneStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        showStatusBarCarrier();

        Resources res = getContext().getResources();
        mBarTransitions = new PhoneStatusBarTransitions(this);
    }

    public BarTransitions getBarTransitions() {
        return mBarTransitions;
    }

    public void setBar(PhoneStatusBar bar) {
        mBar = bar;
    }

    public void setScrimController(ScrimController scrimController) {
        mScrimController = scrimController;
    }

    private void showStatusBarCarrier() {
        mShowCarrierLabel = Settings.System.getIntForUser(getContext().getContentResolver(),
                Settings.System.STATUS_BAR_SHOW_CARRIER, 1, UserHandle.USER_CURRENT);
    }

    public void swiftStatusBarItems()
    {
        if (mStatusBarContents == null)
            return;

        mHorizontalShift += mHorizontalDirection;
        if ((mHorizontalShift >=  mHorizontalMaxShift) ||
            (mHorizontalShift <= -mHorizontalMaxShift))
            mHorizontalDirection *= -1;

        mVerticalShift += mVerticalDirection;
        if ((mVerticalShift >=  mVerticalMaxShift) ||
            (mVerticalShift <= -mVerticalMaxShift))
            mVerticalDirection *= -1;

        mStatusBarContents.setPaddingRelative(mBasePaddingLeft   + mHorizontalShift,
                                              mBasePaddingTop    + mVerticalShift,
                                              mBasePaddingRight  + mHorizontalShift,
                                              mBasePaddingBottom - mVerticalShift);
        invalidate();
    }

    private Handler mSwiftHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            clearSwiftHandlerCallbacks();
            mSwiftHandler.postDelayed(mRunnable, 30 * 1000);
            swiftStatusBarItems();
        }
    };

    private Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            mSwiftHandler.sendEmptyMessage(0);
        }
    };

    public boolean shouldEnableSwift() {
        return SystemProperties.getBoolean("ro.systemui.burn_in_protection", false);
    }
    public void initSwiftHandlerCallbacks() {
        mSwiftHandler.postDelayed(mRunnable, 0);
    }
    public void clearSwiftHandlerCallbacks() {
        mSwiftHandler.removeCallbacks(mRunnable);
        mSwiftHandler.removeMessages(0);
    }

    @Override
    public void onFinishInflate() {
        mCarrierLabel = (TextView) findViewById(R.id.statusbar_carrier_text);
        updateVisibilities();
        mBarTransitions.init();
        mStatusBarContents = ((ViewGroup)findViewById(R.id.status_bar_contents));
        mBasePaddingLeft   = mStatusBarContents.getPaddingStart();
        mBasePaddingTop    = mStatusBarContents.getPaddingTop();
        mBasePaddingRight  = mStatusBarContents.getPaddingEnd();
        mBasePaddingBottom = mStatusBarContents.getPaddingBottom();
    }

    private void updateVisibilities() {
        if (mCarrierLabel != null) {
            if (mShowCarrierLabel == 2) {
                mCarrierLabel.setVisibility(View.VISIBLE);
            } else if (mShowCarrierLabel == 3) {
                mCarrierLabel.setVisibility(View.VISIBLE);
            } else {
                mCarrierLabel.setVisibility(View.GONE);
            }
        }
    }

    @Override
    public void addPanel(PanelView pv) {
        super.addPanel(pv);
        if (pv.getId() == R.id.notification_panel) {
            mNotificationPanel = pv;
        }
    }

    @Override
    public boolean panelsEnabled() {
        return mBar.panelsEnabled();
    }

    @Override
    public boolean onRequestSendAccessibilityEventInternal(View child, AccessibilityEvent event) {
        if (super.onRequestSendAccessibilityEventInternal(child, event)) {
            // The status bar is very small so augment the view that the user is touching
            // with the content of the status bar a whole. This way an accessibility service
            // may announce the current item as well as the entire content if appropriate.
            AccessibilityEvent record = AccessibilityEvent.obtain();
            onInitializeAccessibilityEvent(record);
            dispatchPopulateAccessibilityEvent(record);
            event.appendRecord(record);
            return true;
        }
        return false;
    }

    @Override
    public PanelView selectPanelForTouch(MotionEvent touch) {
        // No double swiping. If either panel is open, nothing else can be pulled down.
        return mNotificationPanel.getExpandedHeight() > 0
                ? null
                : mNotificationPanel;
    }

    @Override
    public void onPanelPeeked() {
        super.onPanelPeeked();
        removePendingHideExpandedRunnables();
        mBar.makeExpandedVisible(false);
    }

    @Override
    public void onAllPanelsCollapsed() {
        super.onAllPanelsCollapsed();
        // Close the status bar in the next frame so we can show the end of the animation.
        DejankUtils.postAfterTraversal(mHideExpandedRunnable);
        mLastFullyOpenedPanel = null;
    }

    public void removePendingHideExpandedRunnables() {
        DejankUtils.removeCallbacks(mHideExpandedRunnable);
    }

    @Override
    public void onPanelFullyOpened(PanelView openPanel) {
        super.onPanelFullyOpened(openPanel);
        if (openPanel != mLastFullyOpenedPanel) {
            openPanel.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        }
        mLastFullyOpenedPanel = openPanel;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean barConsumedEvent = mBar.interceptTouchEvent(event);

        if (DEBUG_GESTURES) {
            if (event.getActionMasked() != MotionEvent.ACTION_MOVE) {
                EventLog.writeEvent(EventLogTags.SYSUI_PANELBAR_TOUCH,
                        event.getActionMasked(), (int) event.getX(), (int) event.getY(),
                        barConsumedEvent ? 1 : 0);
            }
        }

        return barConsumedEvent || super.onTouchEvent(event);
    }

    @Override
    public void onTrackingStarted(PanelView panel) {
        super.onTrackingStarted(panel);
        mBar.onTrackingStarted();
        mScrimController.onTrackingStarted();
    }

    @Override
    public void onClosingFinished() {
        super.onClosingFinished();
        mBar.onClosingFinished();
    }

    @Override
    public void onTrackingStopped(PanelView panel, boolean expand) {
        super.onTrackingStopped(panel, expand);
        mBar.onTrackingStopped(expand);
    }

    @Override
    public void onExpandingFinished() {
        super.onExpandingFinished();
        mScrimController.onExpandingFinished();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return mBar.interceptTouchEvent(event) || super.onInterceptTouchEvent(event);
    }

    @Override
    public void panelScrimMinFractionChanged(float minFraction) {
        if (mMinFraction != minFraction) {
            mMinFraction = minFraction;
            updateScrimFraction();
        }
    }

    @Override
    public void panelExpansionChanged(PanelView panel, float frac, boolean expanded) {
        super.panelExpansionChanged(panel, frac, expanded);
        mPanelFraction = frac;
        updateScrimFraction();
        mBar.setBlur(frac);
    }

    private void updateScrimFraction() {
        float scrimFraction = Math.max(mPanelFraction - mMinFraction / (1.0f - mMinFraction), 0);
        mScrimController.setPanelExpansion(scrimFraction);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        getContext().getContentResolver().registerContentObserver(Settings.System.getUriFor(
                "status_bar_show_carrier"), false, mObserver);
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
    }
}
