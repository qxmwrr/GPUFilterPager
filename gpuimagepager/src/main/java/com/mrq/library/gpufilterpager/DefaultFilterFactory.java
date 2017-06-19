package com.mrq.library.gpufilterpager;

/**
 * 创建默认滤镜（即无效果的原图滤镜）工厂接口
 * Created by mrq on 2017/6/19.
 */

public interface DefaultFilterFactory {

    /**
     * 创建一个默认的无效果原图滤镜对象
     * @return 新的原图滤镜对象
     */
    Filter create();
}
