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

import jp.co.cyberagent.android.gpuimage.GPUImageFilter;
import jp.co.cyberagent.android.gpuimage.OpenGlUtils;
import jp.co.cyberagent.android.gpuimage.Rotation;
import jp.co.cyberagent.android.gpuimage.util.TextureRotationUtil;

import static jp.co.cyberagent.android.gpuimage.util.TextureRotationUtil.TEXTURE_NO_ROTATION;

/**
 *
 * Created by mrq on 2017/6/13.
 */

public class GPUImageRenderer implements GLSurfaceView.Renderer, Camera.PreviewCallback {
    private static final String TAG = "GPUImagePager";
    private static final boolean DEBUG = true;
    public static final int NO_IMAGE = -1;

    public final Object mSurfaceChangedWaiter = new Object();

    private GPUImageFilter mFilter;
    private GPUImageFilter mLeftFilter;
    private GPUImageFilter mCurFilter;
    private GPUImageFilter mRightFilter;

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

    public GPUImageRenderer() {
        mFilter = new GPUImageFilter();
        mLeftFilter = new GPUImageFilter();
        mCurFilter = new GPUImageFilter();
        mRightFilter = new GPUImageFilter();

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
        mFilter.init();
        if (mLeftFilter != null) {
            mLeftFilter.init();
        }
        if (mCurFilter != null) {
            mCurFilter.init();
        }
        if (mRightFilter != null) {
            mRightFilter.init();
        }
    }

    private int[] mFrameBuffers;
    private int[] mFrameBufferTextures;
    private int mFBOTexture;

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        mOutputWidth = width;
        mOutputHeight = height;

        if (mFrameBuffers != null) {

        }
        mFilter.onOutputSizeChanged(width, height);
        GLES20.glViewport(0, 0, width, height);
        adjustImageScaling();

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

    public void setScrollX(int scrollX, boolean dragToLeft) {
        if (DEBUG) Log.v(TAG, "setScroll " + scrollX + " dragToLeft " + dragToLeft);
        this.mScrollX = scrollX;
        this.mDragToLeft = dragToLeft;
        adjustImageScaling();
    }

    public void setFilter(final GPUImageFilter leftFilter, final GPUImageFilter curFilter, final GPUImageFilter rightFilter) {
        runOnDraw(new Runnable() {
            @Override
            public void run() {
                final GPUImageFilter oldLeftFilter = mLeftFilter;
                final GPUImageFilter oldCurFilter = mCurFilter;
                final GPUImageFilter oldRightFilter = mRightFilter;
                mLeftFilter = leftFilter;
                mCurFilter = curFilter;
                mRightFilter = rightFilter;
                if (oldLeftFilter != null) {
                    oldLeftFilter.destroy();
                }
                if (oldCurFilter != null) {
                    oldCurFilter.destroy();
                }
                if (oldRightFilter != null) {
                    oldRightFilter.destroy();
                }
                if (mLeftFilter != null) {
                    mLeftFilter.init();
                    mLeftFilter.onOutputSizeChanged(mOutputWidth, mOutputHeight);
                }
                if (mCurFilter != null) {
                    mCurFilter.init();
                    mCurFilter.onOutputSizeChanged(mOutputWidth, mOutputHeight);
                }
                if (mRightFilter != null) {
                    mRightFilter.init();
                    mRightFilter.onOutputSizeChanged(mOutputWidth, mOutputHeight);
                }
            }
        });
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
//                    mAddedPadding = 1;
                } else {
//                    mAddedPadding = 0;
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

        float[] cube = CUBE;
        float[] textureCords = TextureRotationUtil.getRotation(mRotation, mFlipHorizontal, mFlipVertical);
        if (mScaleType == ScaleType.CENTER_CROP) {
            float distHorizontal = (1 - 1 / ratioWidth) / 2;
            float distVertical = (1 - 1 / ratioHeight) / 2;
            textureCords = new float[]{
                    addDistance(textureCords[0], distHorizontal), addDistance(textureCords[1], distVertical),
                    addDistance(textureCords[2], distHorizontal), addDistance(textureCords[3], distVertical),
                    addDistance(textureCords[4], distHorizontal), addDistance(textureCords[5], distVertical),
                    addDistance(textureCords[6], distHorizontal), addDistance(textureCords[7], distVertical),
            };
        } else {
            cube = new float[]{
                    CUBE[0] / ratioHeight, CUBE[1] / ratioWidth,
                    CUBE[2] / ratioHeight, CUBE[3] / ratioWidth,
                    CUBE[4] / ratioHeight, CUBE[5] / ratioWidth,
                    CUBE[6] / ratioHeight, CUBE[7] / ratioWidth,
            };
        }

        float offset = mScrollX * 1.0f / mOutputWidth;

        mGLLeftCubeBuffer.clear();
        float[] leftCube = adjustLeftCube(cube, offset);
        mGLLeftCubeBuffer.put(leftCube).position(0);
        mGLLeftTextureBuffer.clear();

        float[] leftTexture = adjustLeftTexture(textureCords, offset);
        mGLLeftTextureBuffer.put(leftTexture).position(0);

        mGLRightCubeBuffer.clear();
        mGLRightCubeBuffer.put(adjustRightCube(cube, offset)).position(0);
        mGLRightTextureBuffer.clear();
        float[] rightTexture = adjustRightTexture(textureCords, offset);
        mGLRightTextureBuffer.put(rightTexture).position(0);


        textureCords = TextureRotationUtil.getRotation(mRotation, mFlipHorizontal, mFlipVertical);
        leftTexture = adjustLeftTexture(textureCords, offset);
        rightTexture = adjustRightTexture(textureCords, offset);
        mGLLeftNormalCubeBuffer.put(adjustLeftCube(CUBE, offset)).position(0);
        mGLRightNormalCubeBuffer.put(adjustRightCube(CUBE, offset)).position(0);
        mGLLeftFlipTextureBuffer.put(new float[] {
                leftTexture[0], flip(leftTexture[1]),
                leftTexture[2], flip(leftTexture[3]),
                leftTexture[4], flip(leftTexture[5]),
                leftTexture[6], flip(leftTexture[7]),
        }).position(0);
        mGLRightFlipTextureBuffer.put(new float[] {
                rightTexture[0], flip(rightTexture[1]),
                rightTexture[2], flip(rightTexture[3]),
                rightTexture[4], flip(rightTexture[5]),
                rightTexture[6], flip(rightTexture[7]),
        }).position(0);

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
