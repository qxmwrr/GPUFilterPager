package com.mrq.library.gpufilterpager;

import java.nio.FloatBuffer;

/**
 * 抽象滤镜类
 * Created by mrq on 2017/6/19.
 */

public interface Filter {

    /**
     * 滤镜初始化
     */
    void init();

    /**
     * GLSurfaceView尺寸变化
     * @param width 新尺寸宽
     * @param height 新尺寸高
     */
    void onOutputSizeChanged(final int width, final int height);

    /**
     * 执行绘制（滤镜处理），即这里要完成，在Fragment Shader 中对 纹理的变换操作，然后绘制到GLSurfaceView或者FBO中。
     * @param textureId 纹理id，当前滤镜需要处理的纹理对象
     * @param cubeBuffer 绘制区域
     * @param textureBuffer 纹理选取区域
     */
    void onDraw(final int textureId, final FloatBuffer cubeBuffer, final FloatBuffer textureBuffer);

    /**
     * 解除无用资源
     */
    void destroy();
}
