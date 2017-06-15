package com.mrq.gpufilterpager;

import android.app.Activity;
import android.graphics.BitmapFactory;
import android.opengl.GLSurfaceView;
import android.os.Bundle;

import com.mrq.library.gpufilterpager.GPUImagePager;
import com.mrq.library.gpufilterpager.ScaleType;

import java.io.IOException;
import java.util.ArrayList;

import jp.co.cyberagent.android.gpuimage.GPUImageAlphaBlendFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageBrightnessFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageColorInvertFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageContrastFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageGrayscaleFilter;
import jp.co.cyberagent.android.gpuimage.GPUImageHueFilter;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        GLSurfaceView glSurfaceView = (GLSurfaceView) findViewById(R.id.gl_surface_view);
        GPUImagePager gpuImagePager = new GPUImagePager(glSurfaceView);
        ArrayList<GPUImageFilter> filters = new ArrayList<>();
        filters.add(new GPUImageColorInvertFilter());
        filters.add(new GPUImageHueFilter());
        filters.add(new GPUImageBrightnessFilter(-0.5f));
        filters.add(new GPUImageBrightnessFilter(0.5f));
        filters.add(new GPUImageAlphaBlendFilter());
        filters.add(new GPUImageGrayscaleFilter());
        filters.add(new GPUImageContrastFilter(40));
        gpuImagePager.setScaleType(ScaleType.CENTER_CROP);
        gpuImagePager.setFilterList(filters);
        try {
            gpuImagePager.setImage(BitmapFactory.decodeStream(getAssets().open("img.jpg")));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
