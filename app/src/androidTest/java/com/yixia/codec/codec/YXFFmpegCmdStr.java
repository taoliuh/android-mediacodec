package com.yixia.codec.codec;

import java.io.IOException;
import java.util.Locale;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

public class YXFFmpegCmdStr {
	
	/**
	 * 视频信息
	 */
	private String mVideoPath	= null;
	private int mVideoWidth		= 0;
	private int mVideoHeight	= 0;
	private int mVideoBitRate	= 0;
	private int mVideoFps		= 0;
	
	/**
	 * 标准视频信息；
	 */
	private final int m480X480BitratePerFrame = 60000;
	private final int m480X480Area = 480*480;
	
	/**
	 * Logo 信息;
	 */
	private String mLogoPath	= null;
	private int mLogoWidth		= 0;
	private int mLogoHeight		= 0;
	
	/**
	 * 预定义Logo位置信息;
	 */
	private final float mLogoX	= 0.0f;//0.0417f;
	private final float mLogoY	= 0.0f;//0.1000f;
	
	private static final String LOG_TAG = "YXFFmpegCmdStr";
	
	/**
	 * 生成ffmpeg贴Logo转码命令
	 * @param _videoPath		视频路径
	 * @param _logoPath			Logo路径
	 * @param _dstVideoPath		目标输出路径
	 * @return					成功返回命令字符串，失败返回null
	 */
	public String generatePasteLogoCmdStr( String _videoPath, String _logoPath, String _dstVideoPath)
	{
		if( _videoPath==null || _logoPath==null)
		{
			return null;
		}
		
		if (analysisVideoPath(_videoPath)==false)
		{
			return null;
		}
		
		if( analysisLogoPath(_logoPath)==false)
		{
			return null;
		}
		int bitRate = mVideoBitRate;
		int standardBitRate = mVideoWidth*mVideoHeight*m480X480BitratePerFrame/m480X480Area*mVideoFps;
		if( bitRate > standardBitRate)
		{
			bitRate = standardBitRate;
		}
		int logoX = (int) (mVideoWidth*mLogoX + 0.5f);
		int logoY = (int) (mVideoHeight*mLogoY + 0.5f);
		int logoWidth = mLogoWidth*mVideoWidth/1080;
		int logoHeight = mLogoHeight*mVideoHeight/1920;
		String filterStr = String.format(
				Locale.getDefault(),
				"movie=%s [logo],"
				+ "[logo] scale=w=%d:h=%d:force_original_aspect_ratio=decrease[scaleLogo];"
				+ "[in][scaleLogo] overlay=%d:%d [out]",
				_logoPath,
				logoWidth,
				logoHeight,
				logoX,
				logoY
				);
		
		String cmdStr = String.format(
				Locale.getDefault(),
				"ffmpeg -i %s -b:v %d -vf \"%s\" -y %s",
				_videoPath, bitRate, filterStr, _dstVideoPath);
		Log.e(LOG_TAG, cmdStr);
		
		return cmdStr;
	}
	
	/**
	 * 分析视频路径的媒体信息
	 * @param _videoPath	视频路径
	 * @return				true or false
	 */
	private boolean analysisVideoPath( String _videoPath)
	{
		mVideoPath = _videoPath;
		MediaExtractor mex = new MediaExtractor();
	    try {
	        mex.setDataSource(mVideoPath);// the adresss location of the sound on sdcard.
	    } catch (IOException e) {
	    	Log.e(LOG_TAG, "" + _videoPath);
	        e.printStackTrace();
	        return false;
	    }

	    MediaFormat mf = null;//mex.getTrackFormat(0);
	    String mime = null;
	    int trackCont = mex.getTrackCount();
	    for( int i=0; i<trackCont; ++i)
	    {
	    	mf = mex.getTrackFormat(i);
	    	mime = mf.getString(MediaFormat.KEY_MIME);
	    	if( mime.startsWith("video/"))
	    	{
	    		break;
	    	}
	    	else
	    	{
	    		mf = null;
	    	}
	    }
	    if( null == mf)
	    {
	    	return false;
	    }
	    
	    mVideoBitRate 	= mf.getInteger( MediaFormat.KEY_BIT_RATE);
	    mVideoFps		= mf.getInteger( MediaFormat.KEY_FRAME_RATE);
	    mVideoWidth		= mf.getInteger( MediaFormat.KEY_WIDTH);
	    mVideoHeight	= mf.getInteger( MediaFormat.KEY_HEIGHT);
		
		return true;
	}
	
	/**
	 * 分析Logo路径的图片信息
	 * @param _logoPath		Logo路径
	 * @return				true or false
	 */
	private boolean analysisLogoPath( String _logoPath)
	{
		mLogoPath = _logoPath;
		Bitmap logoBmp = BitmapFactory.decodeFile(mLogoPath);
		if( null == logoBmp)
		{
			return false;
		}
		mLogoWidth = logoBmp.getWidth();
		mLogoHeight = logoBmp.getHeight();
		
		return true;
	}
	
	

}
