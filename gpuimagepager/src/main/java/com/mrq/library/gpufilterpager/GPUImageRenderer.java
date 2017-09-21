package com.mrq.library.gpufilterpager;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.LinkedList;
import java.util.Queue;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import static com.mrq.library.gpufilterpager.TextureRotationUtil.TEXTURE_NO_ROTATION;

/**
 *
 * Created by mrq on 2017/6/13.
 */

public class GPUImageRenderer implements GLSurfaceView.Renderer, Camera.PreviewCallback {
    private static final String TAG = "GPUImagePager";
    private static final boolean DEBUG = true;
    public static final int NO_IMAGE = OpenGlUtils.NO_TEXTURE;

    public final Object mSurfaceChangedWaiter = new Object();

    private Filter mFilter;
    private Filter mLeftFilter;
    private Filter mCurFilter;
    private Filter mRightFilter;

    private boolean mDragToLeft;

    private int mGLTextureId = NO_IMAGE;
    private SurfaceTexture mSurfaceTexture = null;
    private final FloatBuffer mGLLeftCubeBuffer;
    private final FloatBuffer mGLRightCubeBuffer;
    private final FloatBuffer mGLLeftTextureBuffer;
    private final FloatBuffer mGLRightTextureBuffer;

    private final FloatBuffer mGLLeftNormalCubeBuffer;
    private final FloatBuffer mGLRightNormalCubeBuffer;
    private final FloatBuffer mGLLeftFlipTextureBuffer;
    private final FloatBuffer mGLRightFlipTextureBuffer;

    private int[] mFrameBuffers;
    private int[] mFrameBufferTextures;

    private int mOutputWidth;
    private int mOutputHeight;
    private int mImageWidth;
    private int mImageHeight;

    private final Queue<Runnable> mRunOnDraw;
    private final Queue<Runnable> mRunOnDrawEnd;
    private Rotation mRotation;
    private boolean mFlipHorizontal;
    private boolean mFlipVertical;
    private ScaleType mScaleType = ScaleType.CENTER_CROP;

    private float mBackgroundRed = 0;
    private float mBackgroundGreen = 0;
    private float mBackgroundBlue = 0;

    private int mScrollX = 0;

    public GPUImageRenderer(DefaultFilterFactory filterFactory) {
        mFilter = filterFactory.create();
        mLeftFilter = filterFactory.create();
        mCurFilter = filterFactory.create();
        mRightFilter = filterFactory.create();

        mRunOnDraw = new LinkedList<Runnable>();
        mRunOnDrawEnd = new LinkedList<Runnable>();

        mGLLeftCubeBuffer = ByteBuffer.allocateDirect(CUBE.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mGLLeftCubeBuffer.put(getLeftCubeFull()).position(0);
        mGLRightCubeBuffer = ByteBuffer.allocateDirect(CUBE.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mGLRightCubeBuffer.put(getRightCubeEmpty()).position(0);
        mGLLeftTextureBuffer = ByteBuffer.allocateDirect(TEXTURE_NO_ROTATION.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mGLRightTextureBuffer = ByteBuffer.allocateDirect(TEXTURE_NO_ROTATION.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();

        mGLLeftNormalCubeBuffer = ByteBuffer.allocateDirect(CUBE.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();

        mGLRightNormalCubeBuffer = ByteBuffer.allocateDirect(CUBE.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();

        mGLLeftFlipTextureBuffer = ByteBuffer.allocateDirect(TEXTURE_NO_ROTATION.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        mGLRightFlipTextureBuffer = ByteBuffer.allocateDirect(TEXTURE_NO_ROTATION.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();

        setRotation(Rotation.NORMAL, false, false);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(mBackgroundRed, mBackgroundGreen, mBackgroundBlue, 1);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);

        filterInit(mFilter);
        filterInit(mLeftFilter);
        filterInit(mCurFilter);
        filterInit(mRightFilter);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        filterOutputSizeChanged(mFilter, width, height);
        filterOutputSizeChanged(mLeftFilter, width, height);
        filterOutputSizeChanged(mCurFilter, width, height);
        filterOutputSizeChanged(mRightFilter, width, height);
        GLES20.glViewport(0, 0, width, height);
        mOutputWidth = width;
        mOutputHeight = height;

        createEmptyFBO(width, height);
        adjustImageScaling();

        synchronized (mSurfaceChangedWaiter) {
            mSurfaceChangedWaiter.notifyAll();
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        runAll(mRunOnDraw);
        if (mFrameBuffers == null || mFrameBufferTextures == null){
            return;
        }
        int previousTexture = mGLTextureId;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFrameBuffers[0]);
        GLES20.glClearColor(0, 0, 0, 0);
        if (DEBUG) Log.d(TAG, "onDrawFrame " + (mDragToLeft ? "drag out left screen" : "drag out right screen"));
        if (mDragToLeft) {//当前屏幕和左屏拖拽
            if (mLeftFilter != null) {
                mLeftFilter.onDraw(previousTexture, mGLLeftCubeBuffer, mGLLeftTextureBuffer);
            } else {
                if (DEBUG) Log.w(TAG, "left filter is null");
                mFilter.onDraw(previousTexture, mGLLeftCubeBuffer, mGLLeftTextureBuffer);
            }
            mFilter.onDraw(previousTexture, mGLRightCubeBuffer, mGLRightTextureBuffer);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            previousTexture = mFrameBufferTextures[0];

            mFilter.onDraw(previousTexture, mGLLeftNormalCubeBuffer, mGLLeftFlipTextureBuffer);
            mCurFilter.onDraw(previousTexture, mGLRightNormalCubeBuffer, mGLRightFlipTextureBuffer);
        } else {//当前屏幕和右屏拖拽
            mCurFilter.onDraw(previousTexture, mGLLeftCubeBuffer, mGLLeftTextureBuffer);
            mFilter.onDraw(previousTexture, mGLRightCubeBuffer, mGLRightTextureBuffer);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            previousTexture = mFrameBufferTextures[0];

            mFilter.onDraw(previousTexture, mGLLeftNormalCubeBuffer, mGLLeftFlipTextureBuffer);
            if (mRightFilter != null) {
                mRightFilter.onDraw(previousTexture, mGLRightNormalCubeBuffer, mGLRightFlipTextureBuffer);
            } else {
                if (DEBUG) Log.w(TAG, "right filter is null");
                mFilter.onDraw(previousTexture, mGLRightNormalCubeBuffer, mGLRightFlipTextureBuffer);
            }
        }

        runAll(mRunOnDrawEnd);
        if (mSurfaceTexture != null) {
            mSurfaceTexture.updateTexImage();
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        //TODO 相机
    }

    public void setScrollX(final Filter targetFilter, final int scrollX, final boolean dragToLeft) {
        runOnDraw(new Runnable() {
            @Override
            public void run() {
                if (mCurFilter != targetFilter){
                    if (DEBUG) Log.v(TAG, "setScroll abandoned");
                    return;
                }
                if (DEBUG) Log.v(TAG, "setScroll " + scrollX + " dragToLeft " + dragToLeft);
                GPUImageRenderer.this.mScrollX = scrollX;
                GPUImageRenderer.this.mDragToLeft = dragToLeft;
                adjustImageScaling();
            }
        });
    }

    public void setFilter(final Filter leftFilter, final Filter curFilter, final Filter rightFilter) {
        if (leftFilter != mLeftFilter  || curFilter != mCurFilter || rightFilter != mRightFilter){
            runOnDraw(new Runnable() {
                @Override
                public void run() {
                    final Filter oldLeftFilter = mLeftFilter;
                    final Filter oldCurFilter = mCurFilter;
                    final Filter oldRightFilter = mRightFilter;
                    mLeftFilter = leftFilter;
                    mCurFilter = curFilter;
                    mRightFilter = rightFilter;
                    filterDestroy(oldLeftFilter);
                    filterDestroy(oldCurFilter);
                    filterDestroy(oldRightFilter);
                    filterInit(mLeftFilter);
                    filterInit(mCurFilter);
                    filterInit(mRightFilter);
                    filterOutputSizeChanged(mLeftFilter, mOutputWidth, mOutputHeight);
                    filterOutputSizeChanged(mCurFilter, mOutputWidth, mOutputHeight);
                    filterOutputSizeChanged(mRightFilter, mOutputWidth, mOutputHeight);
                    if (DEBUG) Log.d(TAG, (mLeftFilter != null ? mLeftFilter.toString() : "null") + " - " +
                            (mCurFilter != null ? mCurFilter.toString() : "null") + " - " +
                            (mRightFilter != null ? mRightFilter.toString() : "null"));
                }
            });
        }
    }

    public void deleteImage() {
        runOnDraw(new Runnable() {

            @Override
            public void run() {
                GLES20.glDeleteTextures(1, new int[]{ mGLTextureId }, 0);
                mGLTextureId = NO_IMAGE;
            }
        });
    }

    public void setImageBitmap(final Bitmap bitmap, final boolean recycle) {
        if (bitmap == null) {
            return;
        }
        runOnDraw(new Runnable() {

            @Override
            public void run() {
                Bitmap resizedBitmap = null;
                if (bitmap.getWidth() % 2 == 1) {
                    resizedBitmap = Bitmap.createBitmap(bitmap.getWidth() + 1, bitmap.getHeight(),
                            Bitmap.Config.ARGB_8888);
                    Canvas can = new Canvas(resizedBitmap);
                    can.drawARGB(0x00, 0x00, 0x00, 0x00);
                    can.drawBitmap(bitmap, 0, 0, null);
                }

                mGLTextureId = OpenGlUtils.loadTexture(
                        resizedBitmap != null ? resizedBitmap : bitmap, mGLTextureId, recycle);
                if (resizedBitmap != null) {
                    resizedBitmap.recycle();
                }
                mImageWidth = bitmap.getWidth();
                mImageHeight = bitmap.getHeight();
                adjustImageScaling();
            }
        });
    }

    private void filterInit(Filter filter) {
        if (filter != null) { filter.init(); }
    }

    private void filterOutputSizeChanged(Filter filter, int width, int height) {
        if (filter != null) { filter.onOutputSizeChanged(width, height);}
    }

    private void filterDestroy(Filter filter) {
        if (filter != null) {
            filter.destroy();
        }
    }

    private void createEmptyFBO(int width, int height) {
        mFrameBuffers = new int[1];
        mFrameBufferTextures = new int[1];
        GLES20.glGenFramebuffers(1, mFrameBuffers, 0);
        GLES20.glGenTextures(1, mFrameBufferTextures, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mFrameBufferTextures[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFrameBuffers[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, mFrameBufferTextures[0], 0);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void adjustImageScaling() {
        float outputWidth = mOutputWidth;
        float outputHeight = mOutputHeight;
        if (mRotation == Rotation.ROTATION_270 || mRotation == Rotation.ROTATION_90) {
            outputWidth = mOutputHeight;
            outputHeight = mOutputWidth;
        }

        //控件宽度是图片宽度的几倍
        float ratio1 = outputWidth / mImageWidth;
        float ratio2 = outputHeight / mImageHeight;
        float ratioMax = Math.max(ratio1, ratio2);
        //根据图片最小边长放大到控件边长
        int imageWidthNew = Math.round(mImageWidth * ratioMax);
        int imageHeightNew = Math.round(mImageHeight * ratioMax);

        //放大后的图片宽度是控件宽度的几倍
        float ratioWidth = imageWidthNew / outputWidth;
        float ratioHeight = imageHeightNew / outputHeight;

        float[] scaleCube = CUBE;
        float[] scaleTextureCords = TextureRotationUtil.getRotation(mRotation, mFlipHorizontal, mFlipVertical);
        //处理缩放
        if (mScaleType == ScaleType.CENTER_CROP) {
            float distHorizontal = (1 - 1 / ratioWidth) / 2;
            float distVertical = (1 - 1 / ratioHeight) / 2;
            scaleTextureCords = new float[]{
                    addDistance(scaleTextureCords[0], distHorizontal), addDistance(scaleTextureCords[1], distVertical),
                    addDistance(scaleTextureCords[2], distHorizontal), addDistance(scaleTextureCords[3], distVertical),
                    addDistance(scaleTextureCords[4], distHorizontal), addDistance(scaleTextureCords[5], distVertical),
                    addDistance(scaleTextureCords[6], distHorizontal), addDistance(scaleTextureCords[7], distVertical),
            };
        } else {
            scaleCube = new float[]{
                    CUBE[0] / ratioHeight, CUBE[1] / ratioWidth,
                    CUBE[2] / ratioHeight, CUBE[3] / ratioWidth,
                    CUBE[4] / ratioHeight, CUBE[5] / ratioWidth,
                    CUBE[6] / ratioHeight, CUBE[7] / ratioWidth,
            };
        }

        float offset = mScrollX * 1.0f / mOutputWidth;

        float[] leftScaleCube = adjustLeftCube(scaleCube, offset);
        float[] rightScaleCube = adjustRightCube(scaleCube, offset);

        float[] leftScaleTexture = adjustLeftTexture(scaleTextureCords, offset);
        float[] rightScaleTexture = adjustRightTexture(scaleTextureCords, offset);

        float[] textureCords = TextureRotationUtil.getRotation(mRotation, mFlipHorizontal, mFlipVertical);
        float[] leftNormalTexture = adjustLeftTexture(textureCords, offset);
        float[] rightNormalTexture = adjustRightTexture(textureCords, offset);

        float[] leftNormalCube = adjustLeftCube(CUBE, offset);
        float[] rightNormalCube = adjustRightCube(CUBE, offset);
        float[] leftFlipTexture = {
                leftNormalTexture[0], flip(leftNormalTexture[1]),
                leftNormalTexture[2], flip(leftNormalTexture[3]),
                leftNormalTexture[4], flip(leftNormalTexture[5]),
                leftNormalTexture[6], flip(leftNormalTexture[7]),
        };
        float[] rightFlipTexture = {
                rightNormalTexture[0], flip(rightNormalTexture[1]),
                rightNormalTexture[2], flip(rightNormalTexture[3]),
                rightNormalTexture[4], flip(rightNormalTexture[5]),
                rightNormalTexture[6], flip(rightNormalTexture[7]),
        };

        mGLLeftCubeBuffer.clear();
        mGLLeftTextureBuffer.clear();
        mGLRightCubeBuffer.clear();
        mGLRightTextureBuffer.clear();

        mGLLeftCubeBuffer.put(leftScaleCube).position(0);
        mGLLeftTextureBuffer.put(leftScaleTexture).position(0);
        mGLRightCubeBuffer.put(rightScaleCube).position(0);
        mGLRightTextureBuffer.put(rightScaleTexture).position(0);

        mGLLeftNormalCubeBuffer.clear();
        mGLRightNormalCubeBuffer.clear();
        mGLLeftFlipTextureBuffer.clear();
        mGLRightFlipTextureBuffer.clear();

        mGLLeftNormalCubeBuffer.put(leftNormalCube).position(0);
        mGLRightNormalCubeBuffer.put(rightNormalCube).position(0);
        mGLLeftFlipTextureBuffer.put(leftFlipTexture).position(0);
        mGLRightFlipTextureBuffer.put(rightFlipTexture).position(0);
    }

    private static float flip(final float i) {
        if (i == 0.0f) {
            return 1.0f;
        }
        return 0.0f;
    }

    /**
     * 调整左图顶点坐标
     * @param cube 原顶点坐标
     * @param offset 距离控件左侧偏移百分比
     * @return 新顶点坐标
     */
    private float[] adjustLeftCube(float[] cube, float offset) {
        float cube2 = (cube[2] - cube[0]) * offset + cube[0];
        float cube6 = (cube[6] - cube[4]) * offset + cube[4];
        return new float[] {
                cube[0], cube[1],
                cube2  , cube[3],
                cube[4], cube[5],
                cube6  , cube[7],
        };
    }

    /**
     * 调整左图贴图选区坐标
     * @param texture 原贴图选区坐标
     * @param offset 距离控件左侧偏移百分比
     * @return 新贴图选区坐标
     */
    private float[] adjustLeftTexture(float[] texture, float offset) {
        float texture2 = (texture[2] - texture[0]) * offset + texture[0];
        float texture6 = (texture[6] - texture[4]) * offset + texture[4];
        return new float[] {
                texture[0], texture[1],
                texture2  , texture[3],
                texture[4], texture[5],
                texture6  , texture[7],
        };
    }

    private float[] adjustRightCube(float[] cube, float offset) {
        float cube0 = (cube[2] - cube[0]) * offset + cube[0];
        float cube4 = (cube[6] - cube[4]) * offset + cube[4];
        return new float[] {
                cube0, cube[1],
                cube[2], cube[3],
                cube4, cube[5],
                cube[6], cube[7],
        };
    }

    private float[] adjustRightTexture(float[] texture, float offset) {
        float texture0 = (texture[2] - texture[0]) * offset + texture[0];
        float texture4 = (texture[6] - texture[4]) * offset + texture[4];
        return new float[] {
                texture0, texture[1],
                texture[2]  , texture[3],
                texture4, texture[5],
                texture[6]  , texture[7],
        };
    }

    private float addDistance(float coordinate, float distance) {
        return coordinate == 0.0f ? distance : 1 - distance;
    }

    public void setScaleType(ScaleType scaleType) {
        mScaleType = scaleType;
    }

    /**
     * Sets the background color
     *
     * @param red red color value
     * @param green green color value
     * @param blue red color value
     */
    public void setBackgroundColor(float red, float green, float blue) {
        mBackgroundRed = red;
        mBackgroundGreen = green;
        mBackgroundBlue = blue;
    }

    public void setRotation(final Rotation rotation) {
        mRotation = rotation;
        adjustImageScaling();
    }

    public void setRotation(final Rotation rotation,
                            final boolean flipHorizontal, final boolean flipVertical) {
        mFlipHorizontal = flipHorizontal;
        mFlipVertical = flipVertical;
        setRotation(rotation);
    }

    protected int getFrameWidth() {
        return mOutputWidth;
    }

    protected int getFrameHeight() {
        return mOutputHeight;
    }

    public Rotation getRotation() {
        return mRotation;
    }

    public boolean isFlippedHorizontally() {
        return mFlipHorizontal;
    }

    public boolean isFlippedVertically() {
        return mFlipVertical;
    }

    private float[] getLeftCubeEmpty() {
        return new float[]{
                CUBE[0], CUBE[1],
                -1.0f  , CUBE[3],
                CUBE[4], CUBE[5],
                -1.0f  , CUBE[7],
        };

    }

    private float[] getLeftCubeFull() {
        return new float[]{
                CUBE[0], CUBE[1],
                1.0f   , CUBE[3],
                CUBE[4], CUBE[5],
                1.0f   , CUBE[7],
        };
    }

    private float[] getRightCubeEmpty() {
        return new float[]{
                1.0f   , CUBE[1],
                CUBE[2], CUBE[3],
                1.0f   , CUBE[5],
                CUBE[6], CUBE[7],
        };
    }

    private float[] getRightCubeFull() {
        return new float[]{
                -1.0f  , CUBE[1],
                CUBE[2], CUBE[3],
                -1.0f  , CUBE[5],
                CUBE[6], CUBE[7],
        };
    }

    private void runAll(Queue<Runnable> queue) {
        synchronized (queue) {
            while (!queue.isEmpty()) {
                queue.poll().run();
            }
        }
    }

    protected void runOnDraw(final Runnable runnable) {
        synchronized (mRunOnDraw) {
            mRunOnDraw.add(runnable);
        }
    }

    protected void runOnDrawEnd(final Runnable runnable) {
        synchronized (mRunOnDrawEnd) {
            mRunOnDrawEnd.add(runnable);
        }
    }

    static final float CUBE[] = {
            -1.0f, -1.0f,       //左下
            1.0f, -1.0f,        //右下
            -1.0f, 1.0f,        //左上
            1.0f, 1.0f,         //右上
    };
}
