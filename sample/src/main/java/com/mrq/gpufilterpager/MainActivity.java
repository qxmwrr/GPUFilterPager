package com.mrq.gpufilterpager;

import android.app.Activity;
import android.graphics.BitmapFactory;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import com.mrq.library.gpufilterpager.DefaultFilterFactory;
import com.mrq.library.gpufilterpager.Filter;
import com.mrq.library.gpufilterpager.GPUImagePager;
import com.mrq.library.gpufilterpager.ScaleType;

import java.io.IOException;
import java.nio.FloatBuffer;
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

        final GPUImagePager gpuImagePager = (GPUImagePager) findViewById(R.id.gpu_image_pager);
        gpuImagePager.init(new DefaultFilterFactory() {
            @Override
            public Filter create() {
                return new FilterWrapper(new GPUImageFilter());
            }
        });
        ArrayList<Filter> filters = new ArrayList<>();
        filters.add(new FilterWrapper(new GPUImageColorInvertFilter()));
        filters.add(new FilterWrapper(new GPUImageHueFilter()));
        filters.add(new FilterWrapper(new GPUImageBrightnessFilter(-0.5f)));
        filters.add(new FilterWrapper(new GPUImageBrightnessFilter(0.5f)));
        filters.add(new FilterWrapper(new GPUImageAlphaBlendFilter()));
        filters.add(new FilterWrapper(new GPUImageGrayscaleFilter()));
        filters.add(new FilterWrapper(new GPUImageContrastFilter(40)));
        gpuImagePager.setScaleType(ScaleType.CENTER_CROP);
        gpuImagePager.setFilterList(filters);
        try {
            gpuImagePager.setImage(BitmapFactory.decodeStream(getAssets().open("img.jpg")));
        } catch (IOException e) {
            e.printStackTrace();
        }

        View.OnClickListener clickListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switch (v.getId()){
                    case R.id.b1:
                        gpuImagePager.setCurrentItem(0);
                        break;
                    case R.id.b2:
                        gpuImagePager.setCurrentItem(1);
                        break;
                    case R.id.b3:
                        gpuImagePager.setCurrentItem(2);
                        break;
                    case R.id.b4:
                        gpuImagePager.setCurrentItem(3);
                        break;
                    case R.id.b5:
                        gpuImagePager.setCurrentItem(4);
                        break;
                    case R.id.b6:
                        gpuImagePager.setCurrentItem(5);
                        break;
                    case R.id.b7:
                        gpuImagePager.setCurrentItem(6);
                        break;
                }
            }
        };
        findViewById(R.id.b1).setOnClickListener(clickListener);
        findViewById(R.id.b2).setOnClickListener(clickListener);
        findViewById(R.id.b3).setOnClickListener(clickListener);
        findViewById(R.id.b4).setOnClickListener(clickListener);
        findViewById(R.id.b5).setOnClickListener(clickListener);
        findViewById(R.id.b6).setOnClickListener(clickListener);
        findViewById(R.id.b7).setOnClickListener(clickListener);
    }

    private class FilterWrapper implements Filter{

        GPUImageFilter filter;

        FilterWrapper(GPUImageFilter filter) {
            this.filter = filter;
        }

        @Override
        public void init() {
            filter.init();
        }

        @Override
        public void onOutputSizeChanged(int width, int height) {
            filter.onOutputSizeChanged(width, height);
        }

        @Override
        public void onDraw(int textureId, FloatBuffer cubeBuffer, FloatBuffer textureBuffer) {
            filter.onDraw(textureId, cubeBuffer, textureBuffer);
        }

        @Override
        public void destroy() {
            filter.destroy();
        }

        @Override
        public String toString() {
            return filter.getClass().getSimpleName();
        }
    }
}
