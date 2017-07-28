package com.yixia.codec.codec;

import android.opengl.GLES20;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Created by yangjie on 2017/1/4.
 */

public class YXPngFileWriter {
    private final  static int DATA_FLAG_RGBA = 0;
    private static final String TAG = "YXPngFileWriter";
    private static ByteBuffer mByteBuffer;
    private static int mMaxSizeInBytes = 0;
    private static final String DEFAULT_PATH = "/sdcard/VideoEdit/defaultJava";
    private static final String DEFAULT_PATH_BASE = "/sdcard/VideoEdit/xxx";
    private int mWriteCount = -1;

    /**
     * 将图像数据存储为png
     * @param _data     图像数据
     * @param _width    图像宽度
     * @param _height   图像高度
     * @return
     */
    public static int writePngByRGBA( String path, byte[] _data, int _width, int _height)
    {
        Log.e( TAG, "writePngByRGBA " + path);
        return nativeWritePng( path, _data, _width, _height, DATA_FLAG_RGBA);
    }


    /**
     * 将当前EGL环境的图像写入png图片;
     * @return OK = 0, ERROR = -1;
     */
    public int writePngByCurrent( int _width, int _height)
    {
        int ret;
        int needSize = _width * _height *4;
        if ( mByteBuffer == null || needSize > mMaxSizeInBytes)
        {
            mByteBuffer = ByteBuffer.allocate( needSize);
            mMaxSizeInBytes = needSize;
        }

        GLES20.glReadPixels(0, 0, _width, _height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, mByteBuffer);
        ret = writePngByRGBA( DEFAULT_PATH_BASE + mWriteCount + ".png", mByteBuffer.array(), _width, _height);

        return ret;
    }

    public void reset()
    {
        mWriteCount = 0;
    }

    public int writeCurTex2PngByCount( int _width, int _height, int _count)
    {
        if ( mWriteCount >= 0 && mWriteCount < _count)
        {
            writePngByCurrent( _width, _height);
            ++mWriteCount;
        }
        return 0;
    }




    /**
     * 将图像数据存储为png
     * @param _data     图像数据
     * @param _width    图像宽度
     * @param _height   图像高度
     * @param _flag     图像数据排布标记，默认 DATA_FLAG_RGBA
     * @return
     */
    private static native int nativeWritePng( String path, byte[] _data, int _width, int _height, int _flag);


}
