package com.mrq.library.gpufilterpager;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ConfigurationInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.AsyncTask;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.animation.Interpolator;
import android.widget.Scroller;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import jp.co.cyberagent.android.gpuimage.GPUImageFilter;

/**
 *
 * Created by mrq on 2017/6/9.
 */

public class GPUImagePager implements View.OnTouchListener {
    private static final String TAG = "GPUImagePager";
    private static final boolean DEBUG = true;

    private static final int MIN_FLING_VELOCITY = 400; // dips      最小滑动速度
    private static final int MAX_SETTLE_DURATION = 600; // ms       最大滑动持续时间
    private static final int MIN_DISTANCE_FOR_FLING = 25; // dips   滑动最小距离

    private final Context mContext;
    private final GPUImageRenderer mRenderer;
    private GLSurfaceView mGlSurfaceView;
    private Bitmap mCurrentBitmap;
    private ScaleType mScaleType = ScaleType.CENTER_CROP;


    private int mCurItem;
    private List<ItemInfo> mItems = new ArrayList<>();
    private boolean mFirstLayout = true;

    public GPUImagePager(final GLSurfaceView glSurfaceView) {
        if (!supportsOpenGLES2(glSurfaceView.getContext())) {
            throw new IllegalStateException("OpenGL ES 2.0 is not supported on this phone.");
        }
        this.mContext = glSurfaceView.getContext();
        float density = mContext.getResources().getDisplayMetrics().density;
        ViewConfiguration configuration = ViewConfiguration.get(mContext);
        mTouchSlop = configuration.getScaledTouchSlop();
        mMinimumVelocity = (int) (MIN_FLING_VELOCITY * density);
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mFlingDistance = (int) (MIN_DISTANCE_FOR_FLING * density);

        mScroller = new Scroller(mContext, sInterpolator);

        mRenderer = new GPUImageRenderer();
        setGLSurfaceView(glSurfaceView);
        glSurfaceView.setOnTouchListener(this);
        glSurfaceView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (mFirstLayout){
                    mFirstLayout = false;
                    scrollTo(0);
                }
            }
        });
    }

    public void setCurrentItem(int item) {
        setCurrentItem(item, false);
    }

    public void setCurrentItem(int item, boolean smoothScroll) {
        populate(item);
        setCurrentItemInternal(item, smoothScroll, 0);
    }

    /**
     * Sets the background color
     */
    public void setBackgroundColor(float red, float green, float blue) {
        mRenderer.setBackgroundColor(red, green, blue);
    }

    /**
     * Request the preview to be rendered again.
     */
    private void requestRender() {
        if (mGlSurfaceView != null) {
            mGlSurfaceView.requestRender();
        }
    }

    /**
     * Sets the image on which the filter should be applied.
     */
    public void setImage(final Bitmap bitmap) {
        mCurrentBitmap = bitmap;
        mRenderer.setImageBitmap(bitmap, false);
        requestRender();
    }

    public void setImage(Uri uri) {
        new LoadImageUriTask(this, uri).execute();
    }

    /**
     * This sets the scale type of GPUImage. This has to be run before setting the image.
     * If image is set and scale type changed, image needs to be reset.
     */
    public void setScaleType(ScaleType scaleType) {
        mScaleType = scaleType;
        mRenderer.setScaleType(scaleType);
        mRenderer.deleteImage();
        mCurrentBitmap = null;
        requestRender();
    }

    /**
     * Deletes the current image.
     */
    public void deleteImage() {
        mRenderer.deleteImage();
        mCurrentBitmap = null;
        requestRender();
    }
    
    public void setFilterList(List<GPUImageFilter> filterList) {
        mItems.clear();
        for (int i = 0; i < filterList.size(); i++) {
            ItemInfo itemInfo = new ItemInfo();
            itemInfo.filter = filterList.get(i);
            itemInfo.position = i;
            itemInfo.widthFactor = 1;
            itemInfo.offset = i;
            mItems.add(itemInfo);
            if (i == 0) {
                mFirstOffset = itemInfo.offset;
            } else if (i == filterList.size() - 1) {
                mLastOffset = itemInfo.offset;
            }
        }
        populate();
        if (getClientWidth() == 0){
            mFirstLayout = true;
        } else {
            scrollTo(0);
        }
    }

    //--------------------vp-------------------



    private boolean mPopulatePending;

//    private final int mOffscreenPageLimit = 1;
    private Scroller mScroller;
    private boolean mIsScrollStarted;

    private static final Interpolator sInterpolator = new Interpolator() {
        public float getInterpolation(float t) {
            t -= 1.0f;
            return t * t * t * t * t + 1.0f;
        }
    };
    private float mFirstOffset = -Float.MAX_VALUE;
    private float mLastOffset = Float.MAX_VALUE;

    private boolean mIsBeingDragged;

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

    public static final int SCROLL_STATE_IDLE = 0;
    public static final int SCROLL_STATE_DRAGGING = 1;
    public static final int SCROLL_STATE_SETTLING = 2;
    private int mScrollState = SCROLL_STATE_IDLE;
    private final Runnable mEndScrollRunnable = new Runnable() {
        public void run() {
            setScrollState(SCROLL_STATE_IDLE);
            if (DEBUG) Log.d(TAG, "on end scroll runnable curItem " + mCurItem);
            populate();
        }
    };

    @Override
    public boolean onTouch(View v, MotionEvent ev) {
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
                mPopulatePending = false;
//                populate();

                mLastMotionX = mInitialMotionX = ev.getX();
                mLastMotionY = mInitialMotionY = ev.getY();
                mActivePointerId = ev.getPointerId(0);
                break;
            }
            case MotionEvent.ACTION_MOVE:{
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
//                        mGlSurfaceView.requestParentDisallowInterceptTouchEvent(true);
                        mLastMotionX = x - mInitialMotionX > 0 ? mInitialMotionX + mTouchSlop :
                                mInitialMotionX - mTouchSlop;
                        mLastMotionY = y;
                        setScrollState(SCROLL_STATE_DRAGGING);
//                        setScrollingCacheEnabled(true);

                        // Disallow Parent Intercept, just in case
                        ViewParent parent = mGlSurfaceView.getParent();
                        if (parent != null) {
                            parent.requestDisallowInterceptTouchEvent(true);
                        }
                    }
                }
                if (mIsBeingDragged) {
                    // Scroll to follow the motion event
                    final int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                    final float x = ev.getX(activePointerIndex);
//                    needsInvalidate |= performDrag(x);
                    performDrag(x);
                }
                break;
            }
            case MotionEvent.ACTION_UP:{
                if (mIsBeingDragged) {
                    final VelocityTracker velocityTracker = mVelocityTracker;
                    velocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
                    int initialVelocity = (int) velocityTracker.getXVelocity(mActivePointerId);
                    mPopulatePending = true;
                    final int width = getClientWidth();
                    final int scrollX = getScrollX();

                    final ItemInfo ii = infoForCurrentScrollPosition();
                    final int currentPage = ii.position;
                    final float pageOffset = (((float) scrollX / width) - ii.offset) / ii.widthFactor;
                    final int activePointerIndex = ev.findPointerIndex(mActivePointerId);
                    final float x = ev.getX(activePointerIndex);
                    final int totalDelta = (int) (x - mInitialMotionX);
                    int nextPage = determineTargetPage(currentPage, pageOffset, initialVelocity, totalDelta);
                    if (DEBUG) Log.d(TAG, "action up scroll from page " + currentPage + " to page " + nextPage + " with speed " + initialVelocity);
                    setCurrentItemInternal(nextPage, true, initialVelocity);

                    resetTouch();
                }
            }
        }

        return true;
    }
//    void setCurrentItemInternal(int item, boolean smoothScroll, boolean always) {
//        setCurrentItemInternal(item, smoothScroll, always, 0);
//    }

    private void setCurrentItemInternal(int item, boolean smooth, int velocity) {
        if (mItems.size() <= 0) {
            return;
        }

        if (item < 0) {
            item = 0;
        } else if (item >= mItems.size()) {
            item = mItems.size() - 1;
        }

        mCurItem = item;//把将要滑动到的item设置为curItem。
        final ItemInfo curInfo = mItems.get(item);
        final int width = getClientWidth();
        int destX = (int) (width * curInfo.offset);
        if (smooth) {
            smoothScrollTo(destX, velocity);
        } else {
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
            sx = getScrollX();
        }
        int dx = x - sx;//滑动目的位置-当前位置
        if (dx == 0) {
//            completeScroll(false);
            populate();
            setScrollState(SCROLL_STATE_IDLE);
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

    private void computeScroll() {
        mIsScrollStarted = true;
        if (!mScroller.isFinished() && mScroller.computeScrollOffset()) {
            int oldX = getScrollX();
            int x = mScroller.getCurrX();

            if (oldX != x) {
                if (DEBUG) Log.d(TAG, "computeScroll ing from " + oldX + " to " + x);
                scrollTo(x);
            }

            // Keep on drawing until the animation has finished.
            mGlSurfaceView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    computeScroll();
                }
            }, 10);
            return;
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
                int oldX = getScrollX();
                int x = mScroller.getCurrX();
                if (DEBUG) Log.i(TAG, "scroll finish but scroller wasScrolling form " + oldX + " to " + x);
                if (oldX != x) {
                    scrollTo(x);
                }
            }
        }
        mPopulatePending = false;
        if (needPopulate) {
            if (postEvents) {
                mGlSurfaceView.postDelayed(mEndScrollRunnable, 10);
            } else {
                mEndScrollRunnable.run();
            }
        }
    }

    private float distanceInfluenceForSnapDuration(float f) {
        f -= 0.5f; // center the values about 0.
        f *= 0.3f * Math.PI / 2.0f;
        return (float) Math.sin(f);
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
            final ItemInfo firstItem = mItems.get(0);
            final ItemInfo lastItem = mItems.get(mItems.size() - 1);

            // Only let the user target pages we have items for
            targetPage = Math.max(firstItem.position, Math.min(targetPage, lastItem.position));
        }

        return targetPage;
    }

    private ItemInfo infoForCurrentScrollPosition() {
        final int width = getClientWidth();
        final float scrollOffset = width > 0 ? (float) getScrollX() / width : 0;
        int position = getScrollX() / width;
        if (position >= 0 && position < mItems.size()){
            return mItems.get(position);
        }
        return null;

//        int lastPos = -1;
//        float lastOffset = 0.f;
//        float lastWidth = 0.f;
//        boolean first = true;
//
//        ItemInfo lastItem = null;
//        for (int i = 0; i < mItems.size(); i++) {
//            ItemInfo ii = mItems.get(i);
//            float offset;
//            if (!first && ii.position != lastPos + 1) {
//                // Create a synthetic item for a missing page.
//                ii = mTempItem;
//                ii.offset = lastOffset + lastWidth;
//                ii.position = lastPos + 1;
//                ii.widthFactor = mFilterAdapter.getPageWidth(ii.position);
//                i--;
//            }
//            offset = ii.offset;
//
//            final float leftBound = offset;
//            final float rightBound = offset + ii.widthFactor;
//            if (first || scrollOffset >= leftBound) {
//                if (scrollOffset < rightBound || i == mItems.size() - 1) {
//                    return ii;
//                }
//            } else {
//                return lastItem;
//            }
//            first = false;
//            lastPos = ii.position;
//            lastOffset = offset;
//            lastWidth = ii.widthFactor;
//            lastItem = ii;
//        }
//
//        return lastItem;
    }

    private void resetTouch() {
        mActivePointerId = INVALID_POINTER;
        endDrag();
    }

    private void endDrag() {
        mIsBeingDragged = false;

        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    private void performDrag(float x) {
        final float deltaX = mLastMotionX - x;
        mLastMotionX = x;

        float oldScrollX = getScrollX();
        float scrollX = oldScrollX + deltaX;
        final int width = getClientWidth();

        float leftBound = width * mFirstOffset;
        float rightBound = width * mLastOffset;

        if (scrollX < leftBound) {
            if (DEBUG) Log.d(TAG, "滑到最<<<<<<边");
        } else if (scrollX > rightBound) {
            if (DEBUG) Log.d(TAG, "滑到最>>>>>>边");
        } else {
            if (DEBUG) Log.d(TAG, "正常滑动<><><>" + scrollX);
            scrollTo((int) scrollX);
        }
        mLastMotionX += scrollX - (int) scrollX;

}

    private int mScrollX = 0;

    private int getScrollX() {
        return mScrollX;
    }

    private void scrollTo(int scrollX) {
        //computeScroll ing from 7558 to 7559
        //setScroll 1 dragToLeft true
        //computeScroll ing from 7559 to 7560
        //setScroll 1080 dragToLeft false
        mScrollX = scrollX;
        boolean dragToLeft = scrollX - mCurrentItemOffsetPixel < 0;
        if (dragToLeft) {
            mRenderer.setScrollX((int) (mCurrentItemOffsetPixel - scrollX), true);
        } else {
            mRenderer.setScrollX((int) (mCurrentItemOffsetPixel + getClientWidth() - scrollX), false);
        }
        requestRender();
    }

    private void setScrollState(int newState) {
        if (mScrollState == newState) {
            return;
        }

        mScrollState = newState;
//        if (mPageTransformer != null) {
//            // PageTransformers can do complex things that benefit from hardware layers.
//            enableLayers(newState != SCROLL_STATE_IDLE);
//        }
//        dispatchOnScrollStateChanged(newState);
    }

    private void populate()  {
        populate(mCurItem);
    }

    private void populate(int newCurItem) {
        if (newCurItem < 0 || newCurItem >= mItems.size()){
            if (DEBUG) Log.i(TAG, "populate use a wrong position");
            return;
        }
        mCurItem = newCurItem;
        if (mPopulatePending) {
            if (DEBUG) Log.i(TAG, "populate is pending, skipping for now...");
            return;
        }
//        if (mGlSurfaceView.getWindowToken() == null) {
//            return;
//        }

        ItemInfo curItem = mItems.get(mCurItem);
        if (curItem != null) {
            if (DEBUG) Log.d(TAG, "populate " + mCurItem);
            mCurrentItemOffsetPixel = curItem.offset * getClientWidth();

            GPUImageFilter left = null;
            GPUImageFilter cur = mItems.get(mCurItem).filter;
            GPUImageFilter right = null;
            int leftIndex = mCurItem - 1;
            int rightIndex = mCurItem + 1;

            if (leftIndex >= 0){
                left = mItems.get(leftIndex).filter;
            }
            if (rightIndex < mItems.size()) {
                right = mItems.get(rightIndex).filter;
            }
            if (DEBUG) Log.d(TAG, "left (" + leftIndex + " " + (left != null) + ")"
                    + "cur (" + mCurItem + " " + (cur != null) + ")"
                    + "right (" + rightIndex + " " + (right != null) + ")" );
            mRenderer.setFilter(left, cur, right);
//            if (left != null){//借用左侧和中部显示
//                mScrollX = (int) mCurrentItemOffsetPixel;
//                mRenderer.setScrollX(0, true);
//            } else if (right != null) {//借用右侧和中部显示
//                mScrollX = (int) mCurrentItemOffsetPixel + getClientWidth();
//                mRenderer.setScrollX(getClientWidth(), false);
//            } else {
//                if (DEBUG) Log.e(TAG, "error，left and right null");
//            }
        }
    }

    private int getPaddingLeft(){
        return mGlSurfaceView.getPaddingLeft();
    }

    private int getPaddingRight() {
        return mGlSurfaceView.getPaddingRight();
    }

    private int getClientWidth() {
        return mGlSurfaceView.getMeasuredWidth() - getPaddingLeft()
                - mGlSurfaceView.getPaddingRight();
    }

    private static class ItemInfo {
        GPUImageFilter filter;
        int position;
        boolean scrolling;
        float widthFactor;
        float offset;
    }

    //--------------------vp-------------------


    /**
     * Checks if OpenGL ES 2.0 is supported on the current device.
     *
     * @param context the context
     * @return true, if successful
     */
    private boolean supportsOpenGLES2(final Context context) {
        final ActivityManager activityManager = (ActivityManager)
                context.getSystemService(Context.ACTIVITY_SERVICE);
        final ConfigurationInfo configurationInfo =
                activityManager.getDeviceConfigurationInfo();
        return configurationInfo.reqGlEsVersion >= 0x20000;
    }

    private void setGLSurfaceView(final GLSurfaceView view) {
        mGlSurfaceView = view;
        mGlSurfaceView.setEGLContextClientVersion(2);
        mGlSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        mGlSurfaceView.getHolder().setFormat(PixelFormat.RGBA_8888);
        mGlSurfaceView.setRenderer(mRenderer);
        mGlSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        mGlSurfaceView.requestRender();
    }

    private int getOutputWidth() {
        if (mRenderer != null && mRenderer.getFrameWidth() != 0) {
            return mRenderer.getFrameWidth();
        } else if (mCurrentBitmap != null) {
            return mCurrentBitmap.getWidth();
        } else {
            WindowManager windowManager =
                    (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
            Display display = windowManager.getDefaultDisplay();
            return display.getWidth();
        }
    }

    private int getOutputHeight() {
        if (mRenderer != null && mRenderer.getFrameHeight() != 0) {
            return mRenderer.getFrameHeight();
        } else if (mCurrentBitmap != null) {
            return mCurrentBitmap.getHeight();
        } else {
            WindowManager windowManager =
                    (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
            Display display = windowManager.getDefaultDisplay();
            return display.getHeight();
        }
    }


    private class LoadImageUriTask extends LoadImageTask {

        private final Uri mUri;

        public LoadImageUriTask(GPUImagePager gpuImage, Uri uri) {
            super(gpuImage);
            mUri = uri;
        }

        @Override
        protected Bitmap decode(BitmapFactory.Options options) {
            try {
                InputStream inputStream;
                if (mUri.getScheme().startsWith("http") || mUri.getScheme().startsWith("https")) {
                    inputStream = new URL(mUri.toString()).openStream();
                } else {
                    inputStream = mContext.getContentResolver().openInputStream(mUri);
                }
                return BitmapFactory.decodeStream(inputStream, null, options);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }

        @Override
        protected int getImageOrientation() throws IOException {
            Cursor cursor = mContext.getContentResolver().query(mUri,
                    new String[] { MediaStore.Images.ImageColumns.ORIENTATION }, null, null, null);

            if (cursor == null || cursor.getCount() != 1) {
                return 0;
            }

            cursor.moveToFirst();
            int orientation = cursor.getInt(0);
            cursor.close();
            return orientation;
        }
    }

    private abstract class LoadImageTask extends AsyncTask<Void, Void, Bitmap> {

        private final GPUImagePager mGPUImage;
        private int mOutputWidth;
        private int mOutputHeight;

        @SuppressWarnings("deprecation")
        public LoadImageTask(final GPUImagePager gpuImage) {
            mGPUImage = gpuImage;
        }

        @Override
        protected Bitmap doInBackground(Void... params) {
            if (mRenderer != null && mRenderer.getFrameWidth() == 0) {
                try {
                    synchronized (mRenderer.mSurfaceChangedWaiter) {
                        mRenderer.mSurfaceChangedWaiter.wait(3000);
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            mOutputWidth = getOutputWidth();
            mOutputHeight = getOutputHeight();
            return loadResizedImage();
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            super.onPostExecute(bitmap);
            mGPUImage.deleteImage();
            mGPUImage.setImage(bitmap);
        }

        protected abstract Bitmap decode(BitmapFactory.Options options);

        private Bitmap loadResizedImage() {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            decode(options);
            int scale = 1;
            while (checkSize(options.outWidth / scale > mOutputWidth, options.outHeight / scale > mOutputHeight)) {
                scale++;
            }

            scale--;
            if (scale < 1) {
                scale = 1;
            }
            scale = 1;
            options = new BitmapFactory.Options();
            options.inSampleSize = scale;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            options.inPurgeable = true;
            options.inTempStorage = new byte[32 * 1024];
            Bitmap bitmap = decode(options);
            if (bitmap == null) {
                return null;
            }//TODO 暂时加载原图，让opengl来处理图片适用性
//            bitmap = rotateImage(bitmap);
//            bitmap = scaleBitmap(bitmap);
            return bitmap;
        }

        private Bitmap scaleBitmap(Bitmap bitmap) {
            // resize to desired dimensions
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] newSize = getScaleSize(width, height);
            Bitmap workBitmap = Bitmap.createScaledBitmap(bitmap, newSize[0], newSize[1], true);
            if (workBitmap != bitmap) {
                bitmap.recycle();
                bitmap = workBitmap;
                System.gc();
            }

            if (mScaleType == ScaleType.CENTER_CROP) {
                // Crop it
                int diffWidth = newSize[0] - mOutputWidth;
                int diffHeight = newSize[1] - mOutputHeight;
                workBitmap = Bitmap.createBitmap(bitmap, diffWidth / 2, diffHeight / 2,
                        newSize[0] - diffWidth, newSize[1] - diffHeight);
                if (workBitmap != bitmap) {
                    bitmap.recycle();
                    bitmap = workBitmap;
                }
            }

            return bitmap;
        }

        /**
         * Retrieve the scaling size for the image dependent on the ScaleType.<br>
         * <br>
         * If CROP: sides are same size or bigger than output's sides<br>
         * Else   : sides are same size or smaller than output's sides
         */
        private int[] getScaleSize(int width, int height) {
            float newWidth;
            float newHeight;

            float withRatio = (float) width / mOutputWidth;
            float heightRatio = (float) height / mOutputHeight;

            boolean adjustWidth = mScaleType == ScaleType.CENTER_CROP
                    ? withRatio > heightRatio : withRatio < heightRatio;

            if (adjustWidth) {
                newHeight = mOutputHeight;
                newWidth = (newHeight / height) * width;
            } else {
                newWidth = mOutputWidth;
                newHeight = (newWidth / width) * height;
            }
            return new int[]{Math.round(newWidth), Math.round(newHeight)};
        }

        private boolean checkSize(boolean widthBigger, boolean heightBigger) {
            if (mScaleType == ScaleType.CENTER_CROP) {
                return widthBigger && heightBigger;
            } else {
                return widthBigger || heightBigger;
            }
        }

        private Bitmap rotateImage(final Bitmap bitmap) {
            if (bitmap == null) {
                return null;
            }
            Bitmap rotatedBitmap = bitmap;
            try {
                int orientation = getImageOrientation();
                if (orientation != 0) {
                    Matrix matrix = new Matrix();
                    matrix.postRotate(orientation);
                    rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(),
                            bitmap.getHeight(), matrix, true);
                    bitmap.recycle();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            return rotatedBitmap;
        }

        protected abstract int getImageOrientation() throws IOException;
    }
}