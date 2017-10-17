package com.mrq.library.gpufilterpager;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.Scroller;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by mrq on 2017/10/17.
 */

public abstract class FilterPagerLayout extends FrameLayout {
    private static final String TAG = "GPUImagePager";
    private static final boolean DEBUG = true;

    private static final int MIN_FLING_VELOCITY = 400; // dips      最小滑动速度

    private static final int MIN_DISTANCE_FOR_FLING = 25; // dips   滑动最小距离
    private static final int DEFAULT_GUTTER_SIZE = 16; // dips
    private static final int MAX_SETTLE_DURATION = 600; // ms       最大滑动持续时间
    private int mCurItem;
    private int mNextItem = -1;
    protected List<Filter> mItems = new ArrayList<>();


    private boolean mPopulatePending;

    private Scroller mScroller;
    private boolean mIsScrollStarted;

    private static final Interpolator sInterpolator = new Interpolator() {
        public float getInterpolation(float t) {
            t -= 1.0f;
            return t * t * t * t * t + 1.0f;
        }
    };

    private boolean mIsBeingDragged;
    private boolean mIsUnableToDrag;
    private int mDefaultGutterSize;
    private int mGutterSize;
    private int mTouchSlop;
    private float mLastMotionX;
    private float mLastMotionY;
    private float mInitialMotionX;
    private float mInitialMotionY;
    private int mActivePointerId = INVALID_POINTER;

    private float mCurrentItemOffsetPixel;

    private static final int INVALID_POINTER = -1;
    private VelocityTracker mVelocityTracker;
    private int mMinimumVelocity;
    private int mMaximumVelocity;
    private int mFlingDistance;
    private int mCloseEnough;

    // If the pager is at least this close to its final position, complete the scroll
    // on touch down and let the user interact with the content inside instead of
    // "catching" the flinging pager.
    private static final int CLOSE_ENOUGH = 2; // dp

    private int mVirtualScrollX = 0;

    public static final int SCROLL_STATE_IDLE = 0;
    public static final int SCROLL_STATE_DRAGGING = 1;
    public static final int SCROLL_STATE_SETTLING = 2;
    private int mScrollState = SCROLL_STATE_IDLE;

    private final Runnable mEndScrollRunnable = new Runnable() {
        public void run() {
            setScrollState(SCROLL_STATE_IDLE);
            populate();
        }
    };

    public FilterPagerLayout(Context context) {
        this(context, null);
    }

    public FilterPagerLayout(Context context, AttributeSet attrs) {
        super(context, attrs);

        float density = context.getResources().getDisplayMetrics().density;
        ViewConfiguration configuration = ViewConfiguration.get(context);
        mTouchSlop = configuration.getScaledTouchSlop();
        mMinimumVelocity = (int) (MIN_FLING_VELOCITY * density);
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mFlingDistance = (int) (MIN_DISTANCE_FOR_FLING * density);
        mCloseEnough = (int) (CLOSE_ENOUGH * density);
        mDefaultGutterSize = (int) (DEFAULT_GUTTER_SIZE * density);

        mScroller = new Scroller(context, sInterpolator);
    }

    protected abstract void setFilter(Filter left, Filter cur, Filter right);

    public abstract void setScrollX(Filter cur, int scrollX, boolean dragToLeft);

    protected abstract void requestLayoutGpuImageView();

    protected abstract void onFirstLayout();

    public void setFilterList(List<Filter> filterList) {
        mItems.clear();
        for (int i = 0; i < filterList.size(); i++) {
            mItems.add(filterList.get(i));
        }
        populate();
        if (getClientWidth() == 0) {
            onFirstLayout();
        } else {
            scrollTo(0);
        }
    }

    public void setCurrentItem(int item) {
        setCurrentItem(item, false);
    }

    public void setCurrentItem(int item, boolean smoothScroll) {
        populate(item);
        setCurrentItemInternal(item, smoothScroll, 0);
    }

    private void setCurrentItemInternal(int position, boolean smooth, int velocity) {
        if (mItems.size() <= 0) {
            return;
        }

        if (position < 0) {
            position = 0;
        } else if (position >= mItems.size()) {
            position = mItems.size() - 1;
        }

//        mCurItem = position;//把将要滑动到的item设置为curItem。
//        final int width = getClientWidth();
//        int destX = width * position;//偏移
//        if (smooth) {
//            smoothScrollTo(destX, velocity);
//        } else {
//            scrollTo(destX);
//        }
        populate(position);
        scrollToItem(position, smooth, velocity);

    }

    private void scrollToItem(int item, boolean smoothScroll, int velocity) {


        final int width = getClientWidth();
        int destX = width * item;//偏移

        if (smoothScroll) {
            smoothScrollTo(destX, velocity);
        } else {
            completeScroll(false);
            scrollTo(destX);
        }
    }

    private void smoothScrollTo(int x, int velocity) {
        int sx;
        if (!mScroller.isFinished()) {
            //如果没有滑动结束 正在滑动中就返回当前滑动位置、如果还没开始滑动则返回开始滑动位置
            sx = mIsScrollStarted ? mScroller.getCurrX() : mScroller.getStartX();
            mScroller.abortAnimation();
        } else {
            sx = getVirtualScrollX();
        }
        int dx = x - sx;//滑动目的位置-当前位置
        if (dx == 0) {
            populate();
            setScrollState(SCROLL_STATE_IDLE);
            mPopulatePending = false;
            return;
        }

        setScrollState(SCROLL_STATE_SETTLING);

        final int width = getClientWidth();
        final int halfWidth = width / 2;
        final float distanceRatio = Math.min(1f, 1.0f * Math.abs(dx) / width);
        final float distance = halfWidth + halfWidth *
                distanceInfluenceForSnapDuration(distanceRatio);

        int duration;
        duration = calculateDuration(velocity, dx, distance);

        // Reset the "scroll started" flag. It will be flipped to true in all places
        // where we call computeScrollOffset().
        mIsScrollStarted = false;
        mScroller.startScroll(sx, 0, dx, 0, duration);
        computeScroll();
//        requestRender();
    }

    //计算动画持续时间
    private int calculateDuration(int velocity, int dx, float distance) {
        int duration;
        velocity = Math.abs(velocity);
        if (velocity > 0) {
            duration = 4 * Math.round(1000 * Math.abs(distance / velocity));
        } else {
            final float pageDelta = (float) Math.abs(dx) / (getClientWidth());
            duration = (int) ((pageDelta + 1) * 100);
        }
        duration = Math.min(duration, MAX_SETTLE_DURATION);
        return duration;
    }

    private float distanceInfluenceForSnapDuration(float f) {
        f -= 0.5f; // center the values about 0.
        f *= 0.3f * Math.PI / 2.0f;
        return (float) Math.sin(f);
    }

    private int getVirtualScrollX() {
        return mVirtualScrollX;
    }

    protected void scrollTo(int scrollX) {
        Filter curItem = mItems.get(mCurItem);
        if (curItem != null) {
            Filter left = null;
            Filter cur = mItems.get(mCurItem);
            Filter right = null;
            int leftIndex = mCurItem - 1;
            int rightIndex = mCurItem + 1;

            if (leftIndex >= 0) {
                left = mItems.get(leftIndex);
            }
            if (rightIndex < mItems.size()) {
                right = mItems.get(rightIndex);
            }
            if (DEBUG) Log.d(TAG, "left (" + leftIndex + " " + (left != null) + ")"
                    + "cur (" + mCurItem + " " + (cur != null) + ")"
                    + "right (" + rightIndex + " " + (right != null) + ")");
            if (DEBUG) Log.d(TAG, (left != null ? left.toString() : "null") + " - " +
                    (cur != null ? cur.toString() : "null") + " - " +
                    (right != null ? right.toString() : "null"));
            setFilter(left, cur, right);
        }

        mVirtualScrollX = scrollX;
        boolean dragToLeft = scrollX - mCurrentItemOffsetPixel < 0;
        Filter cur = mItems.get(mCurItem);
        if (dragToLeft) {
            int scrollX1 = (int) (mCurrentItemOffsetPixel - scrollX);
            if (DEBUG) Log.d(TAG, "cur:" + mCurItem + "drag to left " + dragToLeft + "  scroll " + scrollX1);
            setScrollX(cur, scrollX1, true);
        } else {
            int scrollX1 = (int) (mCurrentItemOffsetPixel + getClientWidth() - scrollX);
            if (DEBUG) Log.d(TAG, "cur:" + mCurItem + "drag to left " + dragToLeft + "  scroll " + scrollX1);
            setScrollX(cur, scrollX1, false);
        }
        requestLayoutGpuImageView();
    }

    private void setScrollState(int newState) {
        if (mScrollState == newState) {
            return;
        }

        mScrollState = newState;
    }

    private void populate() {
        populate(mCurItem);
    }

    private void populate(int newCurItem) {
        if (newCurItem < 0 || newCurItem >= mItems.size()) {
            if (DEBUG) Log.i(TAG, "populate use a wrong position");
            return;
        }
        mCurItem = newCurItem;
        if (mPopulatePending) {
            if (DEBUG) Log.i(TAG, "populate is pending, skipping for now...");
            return;
        }

        Filter curItem = mItems.get(mCurItem);
        if (curItem != null) {
            if (DEBUG) Log.d(TAG, "populate " + mCurItem);
            mCurrentItemOffsetPixel = mCurItem * getClientWidth();

//            Filter left = null;
//            Filter cur = mItems.get(mCurItem);
//            Filter right = null;
//            int leftIndex = mCurItem - 1;
//            int rightIndex = mCurItem + 1;
//
//            if (leftIndex >= 0) {
//                left = mItems.get(leftIndex);
//            }
//            if (rightIndex < mItems.size()) {
//                right = mItems.get(rightIndex);
//            }
//            if (DEBUG) Log.d(TAG, "left (" + leftIndex + " " + (left != null) + ")"
//                    + "cur (" + mCurItem + " " + (cur != null) + ")"
//                    + "right (" + rightIndex + " " + (right != null) + ")");
//            if (DEBUG) Log.d(TAG, (left != null ? left.toString() : "null") + " - " +
//                    (cur != null ? cur.toString() : "null") + " - " +
//                    (right != null ? right.toString() : "null"));
//            mRenderer.setFilter(left, cur, right);
        }
    }


    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        final int measuredWidth = getMeasuredWidth();
        final int maxGutterSize = measuredWidth / 10;
        mGutterSize = Math.min(maxGutterSize, mDefaultGutterSize);
    }

    @Override
    public void computeScroll() {
        mIsScrollStarted = true;
        if (!mScroller.isFinished() && mScroller.computeScrollOffset()) {
            int oldX = getVirtualScrollX();
            int x = mScroller.getCurrX();

            if (oldX != x) {
                if (DEBUG) Log.d(TAG, "computeScroll ing from " + oldX + " to " + x);
                scrollTo(x);
            }

            // Keep on drawing until the animation has finished.
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    computeScroll();
                }
            }, 1000 / 30);
            return;
        }

        if (mNextItem != -1){
            mCurItem = mNextItem;
            mNextItem = -1;
        }

        // Done with scroll, clean up state.
        completeScroll(true);
    }

    //滑动结束
    private void completeScroll(boolean postEvents) {
        boolean needPopulate = mScrollState == SCROLL_STATE_SETTLING;
        if (needPopulate) {
            boolean wasScrolling = !mScroller.isFinished();
            if (wasScrolling) {
                mScroller.abortAnimation();
                int oldX = getVirtualScrollX();
                int x = mScroller.getCurrX();
                if (DEBUG)
                    Log.i(TAG, "scroll finish but scroller wasScrolling form " + oldX + " to " + x);
                if (oldX != x) {
                    scrollTo(x);
                }
            }
        }
        mPopulatePending = false;
        if (needPopulate) {
            if (postEvents) {
                if (DEBUG) Log.v(TAG, "run end scroll runnable delay 10");
                postDelayed(mEndScrollRunnable, 10);
            } else {
                if (DEBUG) Log.v(TAG, "run end scroll runnable right now");
                mEndScrollRunnable.run();
            }
        }
    }

    private boolean isGutterDrag(float x, float dx) {
        return (x < mGutterSize && dx > 0) || (x > getWidth() - mGutterSize && dx < 0);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
/*
         * This method JUST determines whether we want to intercept the motion.
         * If we return true, onMotionEvent will be called and we do the actual
         * scrolling there.
         */

        final int action = ev.getAction() & MotionEvent.ACTION_MASK;

        // Always take care of the touch gesture being complete.
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            // Release the drag.
            if (DEBUG) Log.v(TAG, "Intercept done!");
            resetTouch();
            return false;
        }

        // Nothing more to do here if we have decided whether or not we
        // are dragging.
        if (action != MotionEvent.ACTION_DOWN) {
            if (mIsBeingDragged) {
                if (DEBUG) Log.v(TAG, "Intercept returning true!");
                return true;
            }
            if (mIsUnableToDrag) {
                if (DEBUG) Log.v(TAG, "Intercept returning false!");
                return false;
            }
        }

        switch (action) {
            case MotionEvent.ACTION_MOVE: {
                /*
                 * mIsBeingDragged == false, otherwise the shortcut would have caught it. Check
                 * whether the user has moved far enough from his original down touch.
                 */

                /*
                * Locally do absolute value. mLastMotionY is set to the y value
                * of the down event.
                */
                final int activePointerId = mActivePointerId;
                if (activePointerId == INVALID_POINTER) {
                    // If we don't have a valid id, the touch down wasn't on content.
                    break;
                }

                final int pointerIndex = ev.findPointerIndex(activePointerId);
                final float x = ev.getX(pointerIndex);
                final float dx = x - mLastMotionX;
                final float xDiff = Math.abs(dx);
                final float y = ev.getY(pointerIndex);
                final float yDiff = Math.abs(y - mInitialMotionY);
                if (DEBUG) Log.v(TAG, "Moved x to " + x + "," + y + " diff=" + xDiff + "," + yDiff);

                if (dx != 0 && !isGutterDrag(mLastMotionX, dx)
                        && canScroll(this, false, (int) dx, (int) x, (int) y)) {
                    // Nested view has scrollable area under this point. Let it be handled there.
                    mLastMotionX = x;
                    mLastMotionY = y;
                    mIsUnableToDrag = true;
                    return false;
                }
                if (xDiff > mTouchSlop && xDiff * 0.5f > yDiff) {
                    if (DEBUG) Log.v(TAG, "Starting drag!");
                    mIsBeingDragged = true;
                    requestParentDisallowInterceptTouchEvent(true);
                    setScrollState(SCROLL_STATE_DRAGGING);
                    mLastMotionX = dx > 0
                            ? mInitialMotionX + mTouchSlop : mInitialMotionX - mTouchSlop;
                    mLastMotionY = y;
                } else if (yDiff > mTouchSlop) {
                    // The finger has moved enough in the vertical
                    // direction to be counted as a drag...  abort
                    // any attempt to drag horizontally, to work correctly
                    // with children that have scrolling containers.
                    if (DEBUG) Log.v(TAG, "Starting unable to drag!");
                    mIsUnableToDrag = true;
                }
                if (mIsBeingDragged) {
                    // Scroll to follow the motion event
                    performDrag(x);
                }
                break;
            }

            case MotionEvent.ACTION_DOWN: {
                /*
                 * Remember location of down touch.
                 * ACTION_DOWN always refers to pointer index 0.
                 */
                mLastMotionX = mInitialMotionX = ev.getX();
                mLastMotionY = mInitialMotionY = ev.getY();
                mActivePointerId = ev.getPointerId(0);
                mIsUnableToDrag = false;

                mIsScrollStarted = true;
                mScroller.computeScrollOffset();
                if (mScrollState == SCROLL_STATE_SETTLING
                        && Math.abs(mScroller.getFinalX() - mScroller.getCurrX()) > mCloseEnough) {
                    // Let the user 'catch' the pager as it animates.
                    mScroller.abortAnimation();
                    if (mNextItem != -1){
                        mCurItem = mNextItem;
                        mNextItem = -1;
                    }
                    mPopulatePending = false;
                    populate();
                    mIsBeingDragged = true;
                    requestParentDisallowInterceptTouchEvent(true);
                    setScrollState(SCROLL_STATE_DRAGGING);
                } else {
                    completeScroll(false);
                    mIsBeingDragged = false;
                }

                if (DEBUG) {
                    Log.v(TAG, "Down at " + mLastMotionX + "," + mLastMotionY
                            + " mIsBeingDragged=" + mIsBeingDragged
                            + "mIsUnableToDrag=" + mIsUnableToDrag);
                }
                break;
            }

            case MotionEvent.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;
        }

        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);

        /*
         * The only time we want to intercept motion events is if we are in the
         * drag mode.
         */
        return mIsBeingDragged;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN && ev.getEdgeFlags() != 0) {
            // Don't handle edge touches immediately -- they may actually belong to one of our
            // descendants.
            return false;
        }
        if (mItems.size() == 0) {
            // Nothing to present or scroll; nothing to touch.
            return false;
        }
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);

        final int action = ev.getAction();

        switch (action & MotionEvent.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: {
                mScroller.abortAnimation();
                if (DEBUG) Log.v(TAG, "scrolling abort");
                mPopulatePending = false;
                if (mNextItem != -1){
                    mCurItem = mNextItem;
                    mNextItem = -1;
                }
                populate();

                mLastMotionX = mInitialMotionX = ev.getX();
                mLastMotionY = mInitialMotionY = ev.getY();
                mActivePointerId = ev.getPointerId(0);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!mIsBeingDragged) {
                    final int pointerIndex = ev.findPointerIndex(mActivePointerId);
                    if (pointerIndex == -1) {
                        // A child has consumed some touch events and put us into an inconsistent state.
                        resetTouch();
                        break;
                    }
                    final float x = ev.getX(pointerIndex);
                    final float xDiff = Math.abs(x - mLastMotionX);
                    final float y = ev.getY(pointerIndex);
                    final float yDiff = Math.abs(y - mLastMotionY);
                    if (DEBUG)
                        Log.v(TAG, "Moved x to " + x + "," + y + " diff=" + xDiff + "," + yDiff);
                    if (xDiff > mTouchSlop && xDiff > yDiff) {
                        if (DEBUG) Log.v(TAG, "Starting drag!");
                        mIsBeingDragged = true;
                        requestParentDisallowInterceptTouchEvent(true);
                        mLastMotionX = x - mInitialMotionX > 0 ? mInitialMotionX + mTouchSlop :
                                mInitialMotionX - mTouchSlop;
                        mLastMotionY = y;
                        setScrollState(SCROLL_STATE_DRAGGING);

                        // Disallow Parent Intercept, just in case
                        ViewParent parent = getParent();
                        if (parent != null) {
                            parent.requestDisallowInterceptTouchEvent(true);
                        }
                    }
                }
                if (mIsBeingDragged) {
                    // Scroll to follow the motion event
                    final int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                    final float x = ev.getX(activePointerIndex);
                    performDrag(x);
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (mIsBeingDragged) {
                    final VelocityTracker velocityTracker = mVelocityTracker;
                    velocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
                    int initialVelocity = (int) velocityTracker.getXVelocity(mActivePointerId);
                    mPopulatePending = true;
                    final int width = getClientWidth();
                    final int scrollX = getVirtualScrollX();

                    final int position = infoForCurrentScrollPosition();
                    if (position != -1) {
                        final int currentPage = position;
                        final float pageOffset = ((float) scrollX / width) - position;
                        final int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                        final float x = ev.getX(activePointerIndex);
                        final int totalDelta = (int) (x - mInitialMotionX);
                        int nextPage = determineTargetPage(currentPage, pageOffset, initialVelocity, totalDelta);
                        if (DEBUG)
                            Log.d(TAG, "action up scroll from page " + currentPage + " to page " + nextPage + " with speed " + initialVelocity);
//                        setCurrentItemInternal(nextPage, true, initialVelocity);
                        mNextItem = nextPage;
                        scrollToItem(nextPage, true, initialVelocity);
                    }

                    resetTouch();
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                if (mIsBeingDragged) {
                    scrollToItem(mCurItem, true, 0);
                    resetTouch();
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN: {
                final int index = ev.getActionIndex();
                final float x = ev.getX(index);
                mLastMotionX = x;
                mActivePointerId = ev.getPointerId(index);
                break;
            }
            case MotionEvent.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                mLastMotionX = ev.getX(ev.findPointerIndex(mActivePointerId));
                break;
        }

        return true;
    }

    private void resetTouch() {
        mActivePointerId = INVALID_POINTER;
        endDrag();
    }

    private void requestParentDisallowInterceptTouchEvent(boolean disallowIntercept) {
        final ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallowIntercept);
        }
    }

    private void performDrag(float x) {
        final float deltaX = mLastMotionX - x;
        mLastMotionX = x;

        float oldScrollX = getVirtualScrollX();
        float scrollX = oldScrollX + deltaX;
        final int width = getClientWidth();

        if (scrollX < 0) {
//            if (DEBUG) Log.d(TAG, "滑到最<<<<<<边");
        } else if (scrollX > width * (mItems.size() - 1)) {
//            if (DEBUG) Log.d(TAG, "滑到最>>>>>>边");
        } else {
//            if (DEBUG) Log.d(TAG, "正常滑动<><><>" + scrollX);
            scrollTo((int) scrollX);

        }
        mLastMotionX += scrollX - (int) scrollX;
    }

    private int infoForCurrentScrollPosition() {
        final int width = getClientWidth();
        final float scrollOffset = width > 0 ? (float) getVirtualScrollX() / width : 0;
        int position = (int) (scrollOffset);
        if (position >= 0 && position < mItems.size()) {
            return position;
        }
        return -1;
    }

    private int determineTargetPage(int currentPage, float pageOffset, int velocity, int deltaX) {
        int targetPage;
        if (Math.abs(deltaX) > mFlingDistance && Math.abs(velocity) > mMinimumVelocity) {
            targetPage = velocity > 0 ? currentPage : currentPage + 1;
        } else {
            final float truncator = currentPage >= mCurItem ? 0.4f : 0.6f;
            targetPage = (int) (currentPage + pageOffset + truncator);
        }

        if (mItems.size() > 0) {
            // Only let the user target pages we have items for
            targetPage = Math.max(0, Math.min(targetPage, mItems.size() - 1));
        }

        return targetPage;
    }

    private void onSecondaryPointerUp(MotionEvent ev) {
        final int pointerIndex = ev.getActionIndex();
        final int pointerId = ev.getPointerId(pointerIndex);
        if (pointerId == mActivePointerId) {
            // This was our active pointer going up. Choose a new
            // active pointer and adjust accordingly.
            final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            mLastMotionX = ev.getX(newPointerIndex);
            mActivePointerId = ev.getPointerId(newPointerIndex);
            if (mVelocityTracker != null) {
                mVelocityTracker.clear();
            }
        }
    }

    private void endDrag() {
        mIsBeingDragged = false;

        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    /**
     * Tests scrollability within child views of v given a delta of dx.
     *
     * @param v View to test for horizontal scrollability
     * @param checkV Whether the view v passed should itself be checked for scrollability (true),
     *               or just its children (false).
     * @param dx Delta scrolled in pixels
     * @param x X coordinate of the active touch point
     * @param y Y coordinate of the active touch point
     * @return true if child views of v can be scrolled by delta of dx.
     */
    private boolean canScroll(View v, boolean checkV, int dx, int x, int y) {
        if (v instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) v;
            final int scrollX = v.getScrollX();
            final int scrollY = v.getScrollY();
            final int count = group.getChildCount();
            // Count backwards - let topmost views consume scroll distance first.
            for (int i = count - 1; i >= 0; i--) {
                // TODO: Add versioned support here for transformed views.
                // This will not work for transformed views in Honeycomb+
                final View child = group.getChildAt(i);
                if (x + scrollX >= child.getLeft() && x + scrollX < child.getRight()
                        && y + scrollY >= child.getTop() && y + scrollY < child.getBottom()
                        && canScroll(child, true, dx, x + scrollX - child.getLeft(),
                        y + scrollY - child.getTop())) {
                    return true;
                }
            }
        }

        return checkV && v.canScrollHorizontally(-dx);
    }

    private int getClientWidth() {
        return getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
    }
}
