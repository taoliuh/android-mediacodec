package com.yixia.codec.codec;

import android.view.Surface;

/**
 * Created by yangjie on 16/9/2.
 */
public class YXCodecInputSurface {

    private int m_nativeHandle = 0;

    public void init( Surface _inputSurface)
    {
        m_nativeHandle = __alloc();
        __init( m_nativeHandle, _inputSurface);
    }

    public void release()
    {
        __release( m_nativeHandle);
        __free( m_nativeHandle);
    }

    public void updateSurface( Surface _inputSurface)
    {
        __updateSurface( m_nativeHandle, _inputSurface);
    }

    public void makeCurrent()
    {
        __makeCurrent( m_nativeHandle);
    }

    public void swapBuffers( )
    {
        __swapBuffers(m_nativeHandle);
    }

    public void setPresentationTime( long _nsecs)
    {
        __setPresentationTime(m_nativeHandle, _nsecs);
    }


    private static native int     __alloc();

    private static native void    __free( int _nativeHandle);

    private static native void    __init( int _nativeHandle, Surface _inputSurface);

    private static native void    __release( int _nativeHandle);

    private static native int     __updateSurface( int _nativeHandle, Surface _inputSurface);

    private static native void    __makeCurrent( int _nativeHandle);

    private static native void    __swapBuffers( int _nativeHandle);

    private static native void    __setPresentationTime( int _nativeHandle, long _nsecs);

}
