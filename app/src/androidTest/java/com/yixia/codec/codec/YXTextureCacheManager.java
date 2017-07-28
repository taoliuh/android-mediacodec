package com.yixia.codec.codec;

import java.io.Serializable;

public class YXTextureCacheManager implements Serializable {

	/**
	 * 
	 */
	private static final long serialVersionUID = 4283225576832918114L;
	
	private int m_nativeHandle = 0;
	
	public YXTextureCacheManager(int _videoFrameCount, int _width, int _height)
	{
		m_nativeHandle = _init(_videoFrameCount, _width, _height);
	}

	public int 		initCacheManager()
	{
		return _initCacheManager(m_nativeHandle);
	}

	public void		releaseManager()
	{
		_release(m_nativeHandle);
	}

	public boolean 	addVideoFrame( int _texID)
	{
		return _addVideoFrame(m_nativeHandle, _texID);
	}

	public int 		getVideoFrame()
	{
		return _getVideoFrame(m_nativeHandle);
	}

	public void 		deleteVideoFrame()
	{
		_deleteVideoFrame(m_nativeHandle);
	}

	public void 		surfaceTextureCopy(  int _srcTex)
	{
		_surfaceTextureCopy(m_nativeHandle, _srcTex);
	}

	public boolean 	isCacheFull()
	{
		return _isCacheFull(m_nativeHandle);
	}

	public void 		cleanup()
	{
		_cleanup(m_nativeHandle);
	}

	public void 		reset()
	{
		_reset(m_nativeHandle);
	}

	public int 		getCacheSize()
	{
		return _getCacheSize(m_nativeHandle);
	}

	public void 		videoMutexLock()
	{
		_videoMutexLock(m_nativeHandle);
	}

	public void 		videoMutexUnlock()
	{
		_videoMutexUnlock(m_nativeHandle);
	}

	
	
	// native API;
	private static native int 		_init( int _videoFrameCount, int _width, int _height);
	
	private static native int 		_release( int _nativeHandle);
	
	private static native int 		_initCacheManager( int _nativeHandle);

	private static native boolean 	_addVideoFrame( int _nativeHandle, int _texID);
	
	private static native int 		_getVideoFrame( int _nativeHandle);
	
	private static native void 		_deleteVideoFrame( int _nativeHandle);

	private static native void 		_surfaceTextureCopy( int _nativeHandle, int _srcTex);
	
	private static native boolean 	_isCacheFull( int _nativeHandle);
	
	private static native void 		_cleanup(  int _nativeHandle);
	
	private static native void 		_reset(  int _nativeHandle);
	
	private static native int 		_getCacheSize(  int _nativeHandle);

	private static native void 		_videoMutexLock(  int _nativeHandle);
	
	private static native void 		_videoMutexUnlock(  int _nativeHandle);

}
