package com.yixia.codec.codec;


import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;


/**
 * Created by yangjie on 16/8/16.
 */
@SuppressWarnings("unused")
@SuppressLint("NewApi")
public class YXAvcEncoder {
    private static final String TAG 				= 	"AvcMediaEncoder";
    private static final String VIDEO_MIME_TYPE 	= 	"video/avc";
    private static final int 	TIMEOUT_USEC 		= 	0;
    private static final int	maskBitRate			=	(0x1<<0);
    private static final int	maskFrameRate		=	(0x1<<1);
    private static final int	maskForceRestart	=	(0x1<<2);
    private static final int    minFrameRate        =   7;
    private static final int    maxFrameRate        =   2000;

    // 日志开关;
    private static boolean m_verbose = true;
    private static boolean m_testRuntime = false;
    private static boolean m_bSaveAvc = false;

    // 编码起始纳秒系统时间;
    private long        m_startTime = 0;

    private  boolean    m_bSuccessInit = false;


    // 编码器相关信息;
    private MediaCodec m_mediaCodec 	= null;
    private MediaFormat m_codecFormat	= null;
    private MediaCodecInfo m_codecInfo 	= null;
    private Surface m_surface		= null;
    private Boolean m_useInputSurface = false;
    private long			m_getnerateIndex = 0;
    private boolean m_bSignalEndOfStream = false;
    private boolean m_bNeedSingalEnd = false;

    // TODO:
    private CodecInputSurface mCodecInputSurface = null;
    private S2DTextureRender	mTextureRender = null;
//    private S3DTextureRender	mTextureRender = null;
    private EglStateSaver mEglStateSaver = null;
    private YXTextureCacheManager mTextureManager = null;
    private YXCodecInputSurface mNativeCodecInputSurface =  null;


    // 线程运行相关参数；
    public Thread encoderThread	= null;
    public Boolean isRunning	= false;

    // 异步编码纹理输入队列;
    private static int inputpacketsize = 10;
    private static ArrayBlockingQueue<int[]> inputQueue = new ArrayBlockingQueue<>(inputpacketsize);

    // 异步编码内存输入队列;
    private static ArrayBlockingQueue<byte[]> inputQueue2 = new ArrayBlockingQueue<>(inputpacketsize);

    // 编码数据输出队列;
	private static int avcqueuesize = 25;
	public static ArrayBlockingQueue<CodecData> AVCQueue = new ArrayBlockingQueue<CodecData>(avcqueuesize);
	private CodecData  mLastCodecData		= null;


    public byte[]	configbyte	= null;
    public Boolean isNeedReconfigure	= false;
    public int		configStatus	= 0;
    private byte[]  sps;
    private byte[]  pps;


    // 根据以下参数生成编码格式信息；
    public int 	width				= 640;
    public int	height				= 480;
    public int  frameRate			= 25;
    public int	bitRate				= 2500000;
    public int  iFrameInternal		= 1;
    public int  colorFormat			= MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
    public int  profile             = 0;
    public boolean skipGenKey       = true;

    private static YXAvcEncoder curObj = null;

    public static YXAvcEncoder createEncoderObject()
    {
        curObj = new YXAvcEncoder();
        return curObj;
    }


    // ffmpeg 编解码接口调用API用；
    public int initEncoder( int _width, int _height, int _frameRate, int _colorFormat, int _iFrameInternal, int _bitRate, int _profile, boolean _bUseInputSurface)
    {
        m_bSuccessInit = false;
        if( m_useInputSurface && Build.VERSION.SDK_INT<18 )
        {
            return -1;
        }

        int err = 0;
        configbyte = null;
        m_bSignalEndOfStream = false;
        m_bNeedSingalEnd = false;
        if ( skipGenKey)
        {
            m_useInputSurface = _bUseInputSurface;
            if ( m_useInputSurface)
            {
                _colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
            }
            setEncoder(_width, _height, _frameRate, _bitRate, _iFrameInternal, _colorFormat, _profile);
            isNeedReconfigure = true;
            m_bSuccessInit = true;
            Log.e(TAG, "Java call initEncoder finished [skip generate key info]!!! err:" + err);
        }
        else
        {
            // 先用模拟数据生成关键数据;
            int lv_iColorFormat = getSupportedColorFormat();
            m_useInputSurface = false;

            setEncoder(_width, _height, _frameRate, _bitRate, _iFrameInternal, lv_iColorFormat, _profile);
            startEncoder();
            err = generateExtraData();
            stopEncoder();
            if ( err >=0 )
            {
                // 生成关键数据需要模拟内存数据，所以在模拟内存数据生成完成后置为目标状态；
                m_useInputSurface = _bUseInputSurface;
                if( true==m_useInputSurface)
                {
                    _colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;
                }
                setEncoder(_width, _height, _frameRate, _bitRate, _iFrameInternal, _colorFormat, _profile);
                AVCQueue.clear();

                if( true==m_useInputSurface)
                {
                    err = exceptionCheck();
                }
                else
                {
                    err = 0;
                }

                m_bSuccessInit = err<0?false:true;

            }
            Log.e(TAG, "Java call initEncoder finished!!! err:" + err);
        }


        return err;
    }


    // 设置编码参数；
    public int setEncoder( int _width, int _height, int _frameRate, int _bitRate, int _iFrameInternal, int _iColorFormat, int _profile)
    {
        configStatus = 0;
        if( _width>0)
        {
            width 		= 	_width;
        }

        if( _height>0)
        {
            height		= 	_height;
        }


        if ( _frameRate > 0)
        {
            if ( _frameRate < minFrameRate)
            {
                String str = String.format( Locale.getDefault(), "_frameRate:[%d] is too small, change to %d", _frameRate, minFrameRate);
                Log.e( TAG, str);
                _frameRate = minFrameRate;
            }
            else if( _frameRate > maxFrameRate)
            {
                String str = String.format( Locale.getDefault(), "_frameRate:[%d] is too large, change to %d", _frameRate, maxFrameRate);
                Log.e( TAG, str);
                _frameRate = maxFrameRate;
            }

            if( frameRate!=_frameRate)
            {
                frameRate	=	_frameRate;
                isNeedReconfigure = true;
                configStatus |= maskFrameRate;
            }
        }


        if( _bitRate>0 && bitRate != _bitRate)
        {
            bitRate		=	_bitRate;
            isNeedReconfigure = true;
            configStatus |= maskBitRate;
        }

        if( _iFrameInternal>0)
            iFrameInternal	= _iFrameInternal;

        if( _iColorFormat>0)
            colorFormat	=	_iColorFormat;

        if( _profile>=0)
            profile = _profile;

        return 0;
    }


    // 获取extradata；
    public int getExtraData( byte[] output)
    {
        int length = 0;
        if( null != output && null != configbyte)
        {
            System.arraycopy(configbyte, 0, output, 0, configbyte.length);
            length = configbyte.length;
        }

        YXLog(String.format("output%c=null configbyte%c=null", output==null?'=':'!', configbyte==null?'=':'!'));

        return length;
    }

    // 输出日志
    public static void YXLog(String info)
    {
        Log.i(TAG, info);
    }


    // 生成extradata
    private int generateExtraData()
    {
        int lv_iYSize = width * height;
        int lv_iYUVSize = lv_iYSize * 3 / 2;
        byte[] yuvData = new byte[lv_iYUVSize];
        byte[] avcData = new byte[lv_iYUVSize];
        int lv_iCount = 0;
        int err = 0;
        while( configbyte==null)
        {
            err = encodeVideoFromBuffer(yuvData, avcData);
            if ( err<0)
            {
                break;
            }

             if ( configbyte==null)
            {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            ++lv_iCount;
            if( lv_iCount>=10)
            {
            	break;
            }

        }
        YXLog(String.format("generateExtraData %s !!!", configbyte==null?"failed":"succeed"));

        isNeedReconfigure = true;
        configStatus |= maskForceRestart;
        return err;
    }

    private int exceptionCheck()
    {

        int err = configbyte==null ? -1 : 0;
        try {
            reconfigureMediaFormat();
            m_mediaCodec = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
            m_mediaCodec.configure(m_codecFormat, null, null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);
            m_surface = m_mediaCodec.createInputSurface();
            m_mediaCodec.start();
            m_mediaCodec.stop();
            m_mediaCodec.release();
            m_mediaCodec = null;
            m_surface.release();
            m_surface = null;
            YXLog(String.format("exceptionCheck succeed !!!"));

        } catch (Exception e) {
            e.printStackTrace();
            err = -1;
            YXLog(String.format("exceptionCheck failed !!!"));
        }
        return err;

    }

    // 根据编码参数生成编码格式信息；
    private int reconfigureMediaFormat()
    {
        if( m_verbose)
        {
            Log.d(TAG, "call reconfigureMediaFormat !!!");
        }

        m_codecFormat = MediaFormat.createVideoFormat( VIDEO_MIME_TYPE, width, height);
        m_codecFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
        m_codecFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        m_codecFormat.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        m_codecFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInternal);

        Log.i(TAG, String.format( "width:[%d] height:[%d] frameRate:[%d] iFrameInternal:[%d] bitRate:[%d] colorFormat:[%d]", width, height, frameRate, iFrameInternal, bitRate, colorFormat));

        return 0;
    }


    // 放入图像数据并取出编码后的数据；
    @SuppressLint( "NewApi")
    public int encodeVideoFromBuffer( byte[] input, byte[] output)
    {
        // 重新配置编码器;
        if (true == isNeedReconfigure)
        {
            if( configStatus==maskBitRate && Build.VERSION.SDK_INT>=19)// SDK_INT >= 19 支持动态码率设置，不需要重新配置编码器
            {
                Bundle config = new Bundle();
                config.putInt( MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitRate);
				m_mediaCodec.setParameters(config);
                configStatus = 0;
            }
            else
            {
                restartEncoder();
            }

            isNeedReconfigure = false;
        }
        
        drainOutputBuffer();

        int inputBufferIndex;

        try
        {
            inputBufferIndex = m_mediaCodec.dequeueInputBuffer(TIMEOUT_USEC);
            if (inputBufferIndex >= 0) {
                long pts = computePresentationTime(m_getnerateIndex);
                ByteBuffer inputBuffer = getInputBufferByIdx(inputBufferIndex);
                inputBuffer.clear();
                inputBuffer.put(input);
                m_mediaCodec.queueInputBuffer(inputBufferIndex, 0, input.length, pts, 0);
                ++m_getnerateIndex;
            }

            drainOutputBuffer();
            mLastCodecData = AVCQueue.poll();
            int length = 0;
            if( null != output && null != mLastCodecData)
            {
                length = mLastCodecData.data.length;
                System.arraycopy(mLastCodecData.data, 0, output, 0, length);
            }

            return length;

        }
        catch (Exception e)
        {
            e.printStackTrace();
            return -1;
        }
    }

    public int encodeVideoFromBufferAsyn( byte[] input, byte[] output)
    {
        if( null==encoderThread)
        {
            startEncoderThread();
        }

        if( null != input)
        {
            if( inputQueue2.size()>=inputpacketsize)
            {
                inputQueue2.poll();
            }
            inputQueue2.add(input);
        }

        byte[] data = inputQueue2.poll();
        int length = 0;
        if( null != output && null != data)
        {
            System.arraycopy(data, 0, output, 0, data.length);
            length = data.length;
        }

        return length;

    }

    public int  getLastFrameFlags()
    {
        if( null != mLastCodecData)
        {
            return mLastCodecData.flag;
        }
        return 0;
    }
    
    private void addOutputData( byte[] _data, long _pts, int _flag)
    {
        CodecData data = new CodecData();
        data.data 	= _data;
        data.pts	= _pts;
        data.flag   = _flag;
        try
        {
            AVCQueue.add(data);
        }
        catch ( Exception e)
        {
            e.printStackTrace();
        }
    }

    @SuppressLint("NewApi")
    private void drainOutputBuffer()
    {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        int outputBufferIndex = -1;
        try
        {
            outputBufferIndex = m_mediaCodec.dequeueOutputBuffer( bufferInfo, TIMEOUT_USEC);

        }
        catch ( Exception e)
        {
            e.printStackTrace();
        }

        while (outputBufferIndex >= 0)
        {
            // Log.i("AvcEncoder",
            // "Get H264 Buffer Success! flag = "+bufferInfo.flags+",pts = "+bufferInfo.presentationTimeUs+"");
            ByteBuffer outputBuffer = getOutputBufferByIdx(outputBufferIndex);
            byte[] outData = new byte[bufferInfo.size];
            outputBuffer.position(bufferInfo.offset);
            outputBuffer.limit(bufferInfo.offset
                    + bufferInfo.size);
            outputBuffer.get(outData);
            if (bufferInfo.flags == MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            {
                configbyte = outData;
            }
            else if (bufferInfo.flags == MediaCodec.BUFFER_FLAG_KEY_FRAME)
            {
                if ( configbyte!=null)
                {
                    // I帧数据里面包含关键头数据，为了统一输出格式，在这里将其去掉；
                    if( outData[4] == configbyte[4] && (outData[configbyte.length+4]&0x1f)==5)
                    {
                        byte[] clipData = new byte[outData.length-configbyte.length];
                        System.arraycopy( outData, configbyte.length, clipData, 0, clipData.length);
                        outData = clipData;
                    }
                }
                else
                {
                    // TODO:可能某种些手机通过两种方式都未获取到关键数据，那么这个时候关键数据一定存放在I帧里面
                    // TODO:这个时候需要我们直接从I帧里面提取；
                    Log.e( TAG, "I can't find configbyte!!!! NEED extract from I frame!!!");
                }

                addOutputData( outData, bufferInfo.presentationTimeUs, bufferInfo.flags);

            }
            else if( bufferInfo.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            {
                break;
            }
            else
            {
            	addOutputData( outData, bufferInfo.presentationTimeUs, bufferInfo.flags);
            }

            m_mediaCodec.releaseOutputBuffer(outputBufferIndex,	false);
            outputBufferIndex = m_mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
        }

        if( outputBufferIndex== MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)
        {
            MediaFormat format =  m_mediaCodec.getOutputFormat();
            ByteBuffer csd0 = format.getByteBuffer("csd-0");
            ByteBuffer csd1 = format.getByteBuffer("csd-1");
            if ( csd0 != null && csd1 != null)
            {
                int configLength = 0;
                sps = csd0.array().clone();
                pps = csd1.array().clone();
                configLength = sps.length + pps.length;
                configbyte = new byte[configLength];
                System.arraycopy( sps, 0, configbyte, 0, sps.length);
                System.arraycopy( pps, 0, configbyte, sps.length, pps.length);
            }
        }
    }

    private void dumpHex(String tag, byte[] _data)
    {
        dumpHex( tag, _data, 0, _data.length);
    }

    private void dumpHex(String tag, byte[] _data, int _start, int _end)
    {
        int i;
        int step = 4;
        _start = _start > 0 ? _start : 0;
        _end = _end <= _data.length ? _end : _data.length;
        String outPut = "dumpHex:";
        outPut += String.format(Locale.CHINA, "[%s][%d]\n", tag, _data.length);
        for ( i=_start; i<_end; )
        {
            if ( _end -i >= step)
            {
                int j;
                outPut += String.format(Locale.CHINA, "%2d ~ %2d ", i, i+step-1);
                for ( j=0; j<step; ++j)
                {
                    outPut += String.format( "[%02x] ", _data[i+j]);
                }
                outPut += "\n";
                i += step;
            }
            else
            {
                break;
            }

        }
        int left = _end - i;
        int j;
        if ( left > 0)
        {
            outPut += String.format(Locale.CHINA, "%2d ~ %2d ", i, i+left-1);
            for ( j=0; j<left; ++j)
            {
                outPut += String.format( "[%02x] ", _data[i+j]);
            }
            outPut += "\n";
        }
        Log.e( TAG, outPut);
    }

    // 放入图像纹理并取得编码后的数据;
    @SuppressLint("NewApi")
    public int encodeVideoFromTexture( int[] input, byte[] output)
    {
        if ( m_bSuccessInit==false)
        {
            return 0;
        }

        // 重新配置编码器;
        if( null==mEglStateSaver)
        {
            mEglStateSaver = new EglStateSaver();
            mEglStateSaver.saveEGLState();
        }

        if (isNeedReconfigure||(configStatus&maskForceRestart)!=0)
        {
            if( configStatus==maskBitRate && Build.VERSION.SDK_INT>=19)// SDK_INT >= 19 支持动态码率设置，不需要重新配置编码器
            {
                Bundle config = new Bundle();
                config.putInt( MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitRate);
                m_mediaCodec.setParameters(config);
                configStatus = 0;
            }
            else
            {
                restartEncoder();
            }

            isNeedReconfigure = false;
        }

        // 把数据导出干净，不然数据堵上后就卡在塞数据的地方;
        drainOutputBuffer();
        // TODO: 纹理更新
        int mSrcTex = (int)(input[0]&0xFFFFFFFF);
        if( 0 != mSrcTex)
        {
            try
            {
				if( null != mCodecInputSurface)
				{
                    if( m_testRuntime)
                    Log.e( TAG, "IIIIIIIIIIIII");
				    mCodecInputSurface.makeEncodeContextCurrent();
//                    mNativeCodecInputSurface.makeCurrent();

                    if( m_testRuntime)
                    Log.e( TAG, "makeEncodeContextCurrent");
				    mTextureRender.drawFrame(mSrcTex, width, height);
//                    mTextureManager.surfaceTextureCopy( mSrcTex);

                    if( m_testRuntime)
                    Log.e( TAG, "drawFrame");
				    mCodecInputSurface.setPresentationTime(computePresentationTime(m_getnerateIndex));
//                    mNativeCodecInputSurface.setPresentationTime(computePresentationTime(m_getnerateIndex));

                    if( m_testRuntime)
                    Log.e( TAG, "setPresentationTime");
				    mCodecInputSurface.swapBuffers();
//                    mNativeCodecInputSurface.swapBuffers();

                    if( m_testRuntime)
                    Log.e( TAG, "swapBuffers");

                    m_bNeedSingalEnd = true;

                    // TODO: 确保关键数据生成成功;
                    drainOutputBuffer();
                    if ( configbyte == null)
                    {
                        int count = 0;
                        do
                        {
                            mTextureRender.drawFrame(mSrcTex, width, height);
                            mCodecInputSurface.setPresentationTime(computePresentationTime(m_getnerateIndex));
                            mCodecInputSurface.swapBuffers();
                            drainOutputBuffer();
                            ++count;
                            if (count>30)
                            {
                                break;
                            }
                            else
                            {
                                Thread.sleep(10, 0);
                            }

                        }while (configbyte==null);

                        if ( configbyte==null)
                        {
                            Log.e(TAG, "Generate configData failed!!!" + count);
                        }
                        else
                        {
                            Log.e(TAG, "Generate configData succeed!!!" + count);
                        }

                        // 重置设置;
                        AVCQueue.clear();
                        restartEncoder();
                        m_getnerateIndex = 0;

                        mCodecInputSurface.makeEncodeContextCurrent();
                        mTextureRender.drawFrame(mSrcTex, width, height);
                        mCodecInputSurface.setPresentationTime(computePresentationTime(m_getnerateIndex));
                        mCodecInputSurface.swapBuffers();

                    }



				}
			}
            catch (Exception e)
            {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
            m_getnerateIndex += 1;
        }
        else
        {
            if (m_mediaCodec != null && !m_bSignalEndOfStream && m_bNeedSingalEnd)
            {
                try
                {
                    Log.i( TAG, "m_mediaCodec.flush()");
                    m_bSignalEndOfStream = true;
                    m_mediaCodec.signalEndOfInputStream();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        }

        mEglStateSaver.makeSavedStateCurrent();

        // 这里又导出一遍，也许用不着；
        drainOutputBuffer();

		mLastCodecData = AVCQueue.poll();
		int length = 0;
		if( null != output && null != mLastCodecData)
		{
			length = mLastCodecData.data.length;
			System.arraycopy(mLastCodecData.data, 0, output, 0, length);
		}

        if( m_testRuntime)
            Log.e( TAG, "return");

        return length;
    }



    @SuppressLint("NewApi")
    public int encodeVideoFromTextureAsyn( int[] input, byte[] output)
    {
        // 保存调用线程的EGL信息;
        if( null==mEglStateSaver)
        {
            mEglStateSaver = new EglStateSaver();
            mEglStateSaver.saveEGLState();;
        }

        if( null==encoderThread)
        {
            startEncoderThread();
        }


        // 保存输入信息;
        if ( null==mTextureManager)
        {
            mTextureManager = new YXTextureCacheManager( inputpacketsize, width, height);
            mTextureManager.initCacheManager();
        }

        if( null != input)
        {
            mTextureManager.videoMutexLock();
            if( mTextureManager.getCacheSize()>=inputpacketsize)
            {
                mTextureManager.deleteVideoFrame();
            }
            mTextureManager.addVideoFrame( input[0]);
            mTextureManager.videoMutexUnlock();

        }

        // 输出结果;
        mLastCodecData = AVCQueue.poll();
        int length = 0;
        if( null != output && null != mLastCodecData)
        {
            length = mLastCodecData.data.length;
            System.arraycopy(mLastCodecData.data, 0, output, 0, length);
        }

        return length;
    }

    public Surface getInputSurface()
    {
        return m_surface;
    }


    // 开启编码功能；
    public int startEncoder()
    {
        if( m_verbose)
        {
            Log.d(TAG, "call startEncoder !!!");
        }

        try {
            reconfigureMediaFormat();
            m_mediaCodec = MediaCodec.createEncoderByType(VIDEO_MIME_TYPE);
            m_mediaCodec.configure(m_codecFormat, null, null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE);
            if( Build.VERSION.SDK_INT>=18 && m_useInputSurface)
            {
                try
                {
					m_surface = m_mediaCodec.createInputSurface();
				} catch (Exception e) {
					e.printStackTrace();
				}
                if( null == mCodecInputSurface)
                {
                    mCodecInputSurface = new CodecInputSurface(m_surface, mEglStateSaver.getSavedEGLContext());
//                    mNativeCodecInputSurface = new YXCodecInputSurface();
//                    mNativeCodecInputSurface.init( m_surface);
                 }
                else
                {
                    mCodecInputSurface.updateSurface(m_surface);
//                    mNativeCodecInputSurface.updateSurface(m_surface);
                }

//                mNativeCodecInputSurface.makeCurrent();
                mCodecInputSurface.makeEncodeContextCurrent();

                mTextureRender = new S2DTextureRender();
                mTextureRender.surfaceCreated();
//                 mTextureManager = new YXTextureCacheManager( 3, width, height);
//                mTextureManager.initCacheManager();

            }

            m_mediaCodec.start();
            m_startTime = System.nanoTime();
            isNeedReconfigure = false;

        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }

        return 0;
    }

    // 停止编码功能；
    public void stopEncoder()
    {
        if( m_verbose)
        {
            Log.d(TAG, "call stopEncoder !!!");
        }
		try {
            if( null != mCodecInputSurface)
            {
                mCodecInputSurface.makeEncodeContextCurrent();
            }

            if( null != mNativeCodecInputSurface)
            {
                mNativeCodecInputSurface.makeCurrent();
            }

            if (null != mTextureRender) {
                mTextureRender.release();
                mTextureRender = null;
            }

            if (null != m_mediaCodec) {
				m_mediaCodec.stop();
				m_mediaCodec.release();
				m_mediaCodec = null;
			}

			if ( null != m_surface)
            {
                m_surface.release();;
                m_surface=null;
            }




		} catch (Exception e) {
			e.printStackTrace();
		}

    }

    // 重启编码器;
    public int restartEncoder()
    {
        if( m_verbose)
        {
            Log.d(TAG, "call restartEncoder !!!");
        }
        m_bNeedSingalEnd = false;
        stopEncoder();
        startEncoder();
        return 0;
    }
    
	public int closeEncoder()
	{
        if( m_useInputSurface)
        {
            if( null == mEglStateSaver)
            {
                mEglStateSaver = new EglStateSaver();
            }
            mEglStateSaver.saveEGLState();
        }

        if( null != mTextureManager)
        {
            mTextureManager.cleanup();
            mTextureManager.releaseManager();
            mTextureManager = null;
        }

        stopEncoder();

        if( null != mCodecInputSurface)
        {
            mCodecInputSurface.release();
            mCodecInputSurface = null;
        }

        if( null != mNativeCodecInputSurface)
        {
            mNativeCodecInputSurface.release();
            mNativeCodecInputSurface = null;
        }

        if( m_useInputSurface)
        {
            mEglStateSaver.makeSavedStateCurrent();

            mEglStateSaver = null;
        }
 		Log.e(TAG, "Java call closeEncoder finished!!!");

		return 0;
	}


    public int closeEncoderAsyn()
    {
        stopEncoderThread();
        encoderThread = null;
        return 0;
    }
	
	// 用于获取信息;
	public int getInfoByFlag( int[] _outBuf, int _inFlag)
	{
		int status = -1;
		// 假装用1代表获取时间戳;
		if( _inFlag==1)
		{
			_outBuf[0] = (int)(mLastCodecData.pts&0xFFFFFFFF);
			_outBuf[1] = (int)((mLastCodecData.pts>>32)&0xFFFFFFFF);
			status = 2;
		}
		
		return status;
	}


    @SuppressWarnings("deprecation")
    private ByteBuffer getInputBufferByIdx(int _idx)
    {
        if( Build.VERSION.SDK_INT>=21)
        {
            return m_mediaCodec.getInputBuffer(_idx);
        }
        else
        {
            ByteBuffer[] inputBuffers = m_mediaCodec.getInputBuffers();
            return inputBuffers[_idx];
        }
    }

    @SuppressWarnings("deprecation")
    private ByteBuffer getOutputBufferByIdx(int _idx)
    {
        if( Build.VERSION.SDK_INT>=21)
        {
            return m_mediaCodec.getOutputBuffer(_idx);
        }
        else
        {
            ByteBuffer[] outputBuffers = m_mediaCodec.getOutputBuffers();
            return outputBuffers[_idx];
        }
    }



    /**
     * Generates the presentation time for frame N, in microseconds.
     */
    private long computePresentationTime(long frameIndex) {

        long timestamp = m_startTime + (frameIndex * 1000000000 / frameRate);
        return timestamp;
    }


    @SuppressWarnings("deprecation")
    private static MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();

        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) {
                continue;
            }

            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                    Log.e(TAG, "codecInfo[" + i + "].name=" + codecInfo.getName());
                    return codecInfo;
                }
            }
        }
        return null;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static MediaCodecInfo findCodec(String mimeType)
    {
        MediaCodecList mediaLst = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        MediaCodecInfo[] codecInfos = mediaLst.getCodecInfos();
        for( int i=0; i<codecInfos.length; ++i)
        {
            MediaCodecInfo codecInfo = codecInfos[i];
            if (!codecInfo.isEncoder()) {
                continue;
            }

            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++)
            {
                if (types[j].equalsIgnoreCase(mimeType))
                {
                    Log.e(TAG, "codecInfo[" + i + "].name=" + codecInfo.getName());
                    return codecInfo;
                }
            }

        }
        return null;
    }

    // support these color space currently
    private static boolean isRecognizedFormat(int colorFormat) {
        switch (colorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
                return true;
            default:
                return false;
        }
    }

    // if returned -1, can't find a suitable color format
    public int getSupportedColorFormat() {
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;

        if (m_verbose)
            Log.d(TAG, "manufacturer = " + manufacturer + " model = " + model);

        if( Build.VERSION.SDK_INT>=21)
        {
            m_codecInfo = findCodec(VIDEO_MIME_TYPE);
        }
        else
        {
            m_codecInfo = selectCodec(VIDEO_MIME_TYPE);
        }

        if ((manufacturer.compareTo("Xiaomi") == 0) // for Xiaomi MI 2SC,
                // selectCodec methord is
                // too slow
                && (model.compareTo("MI 2SC") == 0))
            return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;

        if ((manufacturer.compareTo("Xiaomi") == 0) // for Xiaomi MI 2,
                // selectCodec methord is
                // too slow
                && (model.compareTo("MI 2") == 0))
            return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;

        if ((manufacturer.compareTo("samsung") == 0) // for samsung S4,
                // COLOR_FormatYUV420Planar
                // will write green
                // frames
                && (model.compareTo("GT-I9500") == 0))
            return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;

        if ((manufacturer.compareTo("samsung") == 0) // for samsung 混手机,
                // COLOR_FormatYUV420Planar
                // will write green
                // frames
                && (model.compareTo("GT-I9300") == 0))
            return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;


        if (m_codecInfo == null) {
            Log.e(TAG, "Unable to find an appropriate codec for "
                    + VIDEO_MIME_TYPE);

            return -1;
        }

        try {
            MediaCodecInfo.CodecCapabilities capabilities = m_codecInfo
                    .getCapabilitiesForType(VIDEO_MIME_TYPE);

            MediaCodecInfo.CodecProfileLevel[] levels = capabilities.profileLevels;
            Log.e( TAG, "CodecProfileLevel" + levels.toString());

//            for (int i = 0; i < capabilities.colorFormats.length; i++) {
//                int colorFormat = capabilities.colorFormats[i];
//                Log.e( TAG, "colorFormats:[" + i + "]=" + capabilities.colorFormats[i]);
//            }


            for (int i = 0; i < capabilities.colorFormats.length; i++) {
                int colorFormat = capabilities.colorFormats[i];

                if (isRecognizedFormat(colorFormat))
                    return colorFormat;
            }
        } catch (Exception e) {
            if (m_verbose)
                Log.d(TAG, "getSupportedColorFormat exception");

            return -1;
        }

        return -1;
    }

    public static boolean isInNotSupportedList() {
        return false;
    }

    /**
     * Holds state associated with a Surface used for MediaCodec encoder input.
     * <p/>
     * The constructor takes a Surface obtained from MediaCodec.createInputSurface(), and uses
     * that to create an EGL window surface.  Calls to eglSwapBuffers() cause a frame of data to
     * be sent to the video encoder.
     * <p/>
     * This object owns the Surface -- releasing this will release the Surface too.
     */
    private static class CodecInputSurface {
        private static final int EGL_RECORDABLE_ANDROID = 0x3142;
        private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext mEGLEncodeContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface mEGLSurface = EGL14.EGL_NO_SURFACE;
        private Surface mSurface;

        EGLConfig[] configs;
        int[] surfaceAttribs = {
                EGL14.EGL_NONE
        };

        /**
         * Creates a CodecInputSurface from a Surface.
         */
        public CodecInputSurface(Surface surface, EGLContext share_context ) {
            if (surface == null) {
                throw new NullPointerException();
            }
            mSurface = surface;

            eglSetup(share_context);
        }

        public void updateSurface(Surface newSurface){
            // Destroy old EglSurface
            EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
            mSurface = newSurface;
            // create new EglSurface
            mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], mSurface,
                    surfaceAttribs, 0);
            checkEglError("eglCreateWindowSurface");
            // eglMakeCurrent called in chunkRecording() after mVideoEncoder.start()
        }

        /**
         * Prepares EGL.  We want a GLES 2.0 context and a surface that supports recording.
         */
        private void eglSetup( EGLContext share_context ) {
            if(m_verbose) Log.i(TAG, "Creating EGL14 Surface");
            mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
                throw new RuntimeException("unable to get EGL14 display");
            }
            int[] version = new int[2];
            version[0] = 0;
            version[1] = 1;
            if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
                throw new RuntimeException("unable to initialize EGL14");
            }

            // Configure EGL for recording and OpenGL ES 2.0.
            int[] attribList = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
            };
            configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, configs.length,
                    numConfigs, 0);
            checkEglError("eglCreateContext RGB888+recordable ES2");

            // Configure context for OpenGL ES 2.0.
            int[] attrib_list = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
            };
            mEGLEncodeContext = EGL14.eglCreateContext(mEGLDisplay, configs[0], share_context,
                    attrib_list, 0);
            checkEglError("eglCreateContext");

            // Create a window surface, and attach it to the Surface we received.
            mEGLSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, configs[0], mSurface,
                    surfaceAttribs, 0);
            checkEglError("eglCreateWindowSurface");
        }

        /**
         * Discards all resources held by this class, notably the EGL context.  Also releases the
         * Surface that was passed to our constructor.
         */
        public void release() {
            if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface);
                EGL14.eglDestroyContext(mEGLDisplay, mEGLEncodeContext);
            }
            mSurface.release();

            mEGLDisplay = EGL14.EGL_NO_DISPLAY;
            mEGLEncodeContext = EGL14.EGL_NO_CONTEXT;
            mEGLSurface = EGL14.EGL_NO_SURFACE;

            mSurface = null;
        }

        public void makeEncodeContextCurrent(){
            makeCurrent(mEGLEncodeContext);
        }
        
        public void makeNoCurrent()
        {
            EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            checkEglError("makeNoCurrent");

        }

        /**
         * Makes our EGL context and surface current.
         */
        private void makeCurrent(EGLContext context) {
            EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, context);
            checkEglError("eglMakeCurrent");
        }

        /**
         * Calls eglSwapBuffers.  Use this to "publish" the current frame.
         */
        public boolean swapBuffers() {
            boolean result = EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface);
            checkEglError("eglSwapBuffers");
            return result;
        }

        /**
         * Sends the presentation time stamp to EGL.  Time is expressed in nanoseconds.
         */
        public void setPresentationTime(long nsecs) {
            EGLExt.eglPresentationTimeANDROID(mEGLDisplay, mEGLSurface, nsecs);
            checkEglError("eglPresentationTimeANDROID");
        }

        /**
         * Checks for EGL errors.  Throws an exception if one is found.
         */
        private void checkEglError(String msg) {
            int error;
            if ((error = EGL14.eglGetError()) != EGL14.EGL_SUCCESS) {
                throw new RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error));
            }
        }
    }

    /**
     * Code for rendering a texture onto a surface using OpenGL ES 2.0.
     */
    private static class S2DTextureRender {
        private static final int FLOAT_SIZE_BYTES = 4;
        private static final int DATA_STRIDE_BYTES = 2 * FLOAT_SIZE_BYTES;

        private static final String VERTEX_SHADER =
                "attribute vec4 pos;\n" +
                        "attribute vec2 inputTexCoordinate;\n" +
                        "varying vec2 texCoord;\n" +
                        "void main() {\n" +
                        "  texCoord = inputTexCoordinate;\n" +
                        "  gl_Position = pos;\n"+
                        "}\n";
        private static final String FRAGMENT_SHADER =
                "precision highp float;\n" +
                        "uniform sampler2D yuvTexSampler;\n" +
                        "varying vec2 texCoord;\n" +
                        "void main() {\n" +
                        " gl_FragColor = texture2D(yuvTexSampler, texCoord);\n" +
                        " gl_FragColor.a = 1.0;" +
                        "}";


        private final float[] vertexCoords = {
                -1, 1, -1, -1, 1, 1, 1, -1
        };

        private final float[] texcood = {
                0, 0, 0, 1, 1, 0, 1, 1
        };
//        private final float[] texcood = {
//                0, 1, 0, 0, 1, 1, 1, 0
//        };

        private FloatBuffer mVertexCoords;
        private FloatBuffer mTexcood;
        private int mProgram;
        private int mTextureID = -12345;
        private int maPosLoc;
        private int maTexLoc;

        public S2DTextureRender()
        {
            mVertexCoords = ByteBuffer.allocateDirect(
                    vertexCoords.length * FLOAT_SIZE_BYTES)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            mVertexCoords.put(vertexCoords).position(0);

            mTexcood = ByteBuffer.allocateDirect(
                    texcood.length * FLOAT_SIZE_BYTES)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            mTexcood.put(texcood).position(0);

        }


        public void drawFrame(int srcTex, int width, int height) {
            checkGlError("onDrawFrame start");

            GLES20.glViewport( 0,  0,  width, height);
            checkGlError("glViewport");


            GLES20.glUseProgram(mProgram);
            checkGlError("glUseProgram");
            
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);


            GLES20.glVertexAttribPointer(maPosLoc, 2, GLES20.GL_FLOAT, false,
                    DATA_STRIDE_BYTES, mVertexCoords);
            checkGlError("glVertexAttribPointer maPosition");
            GLES20.glEnableVertexAttribArray(maPosLoc);
            checkGlError("glEnableVertexAttribArray maPositionHandle");

            GLES20.glVertexAttribPointer(maTexLoc, 2, GLES20.GL_FLOAT, false,
                    DATA_STRIDE_BYTES, mTexcood);
            checkGlError("glVertexAttribPointer maTextureHandle");
            GLES20.glEnableVertexAttribArray(maTexLoc);
            checkGlError("glEnableVertexAttribArray maTextureHandle");

            GLES20.glBindTexture( GLES20.GL_TEXTURE_2D, srcTex);
            checkGlError("glBindTexture");
            
            GLES20.glTexParameteri( GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri( GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            checkGlError("glDrawArrays");

            GLES20.glDisableVertexAttribArray(maPosLoc);
            GLES20.glDisableVertexAttribArray(maTexLoc);

            GLES20.glBindTexture( GLES20.GL_TEXTURE_2D, 0);

            GLES20.glFinish();
            GLES20.glUseProgram(0);
        }

        /**
         * Initializes GL state.  Call this after the EGL surface has been created and made current.
         */
        public void surfaceCreated() {
            mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (mProgram == 0) {
                throw new RuntimeException("failed creating program");
            }

            maPosLoc = GLES20.glGetAttribLocation(mProgram, "pos");
            checkGlError("glGetAttribLocation aPosition");
            if (maPosLoc == -1) {
                throw new RuntimeException("Could not get attrib location for aPosition");
            }

            maTexLoc = GLES20.glGetAttribLocation(mProgram, "inputTexCoordinate");
            checkGlError("glGetAttribLocation aTextureCoord");
            if (maTexLoc == -1) {
                throw new RuntimeException("Could not get attrib location for aTextureCoord");
            }


        }

        /**
         * Replaces the fragment shader.  Pass in null to resetWithChunk to default.
         */
        public void changeFragmentShader(String fragmentShader) {
            if (fragmentShader == null) {
                fragmentShader = FRAGMENT_SHADER;
            }
            GLES20.glDeleteProgram(mProgram);
            mProgram = createProgram(VERTEX_SHADER, fragmentShader);
            if (mProgram == 0) {
                throw new RuntimeException("failed creating program");
            }
        }

        private int loadShader(int shaderType, String source) {
            int shader = GLES20.glCreateShader(shaderType);
            checkGlError("glCreateShader type=" + shaderType);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                Log.e(TAG, "Could not compile shader " + shaderType + ":");
                Log.e(TAG, " " + GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                shader = 0;
            }
            return shader;
        }

        private int createProgram(String vertexSource, String fragmentSource) {
            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            if (vertexShader == 0) {
                return 0;
            }
            int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            if (pixelShader == 0) {
                return 0;
            }

            int program = GLES20.glCreateProgram();
            checkGlError("glCreateProgram");
            if (program == 0) {
                Log.e(TAG, "Could not create program");
            }
            GLES20.glAttachShader(program, vertexShader);
            checkGlError("glAttachShader");
            GLES20.glAttachShader(program, pixelShader);
            checkGlError("glAttachShader");
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link program: ");
                Log.e(TAG, GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                program = 0;
            }
            return program;
        }

        public void checkGlError(String op) {
            int error;
            while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
                Log.e(TAG, op + ": glError " + error);
                throw new RuntimeException(op + ": glError " + error);
            }
        }
        
        public void release()
        {
        	if( 0!= mProgram)
        	{
        		GLES20.glDeleteProgram(mProgram);
        		mProgram = 0;
        	}
        }
        
        
    }



    /**
     * Code for rendering a texture onto a surface using OpenGL ES 2.0.
     */
    private static class S3DTextureRender {
        private static final int FLOAT_SIZE_BYTES = 4;
        private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
        private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
        private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;
        private static final String VERTEX_SHADER =
                "uniform mat4 uMVPMatrix;\n" +
                        "uniform mat4 uSTMatrix;\n" +
                        "attribute vec4 aPosition;\n" +
                        "attribute vec4 aTextureCoord;\n" +
                        "varying vec2 vTextureCoord;\n" +
                        "void main() {\n" +
                        "  gl_Position = uMVPMatrix * aPosition;\n" +
                        "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
                        "}\n";
        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                        "precision mediump float;\n" +      // highp here doesn't seem to matter
                        "varying vec2 vTextureCoord;\n" +
                        "uniform samplerExternalOES sTexture;\n" +
                        "void main() {\n" +
                        "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                        "}\n";
        private final float[] mTriangleVerticesData = {
                // X, Y, Z, U, V
                -1.0f, -1.0f, 0, 0.f, 0.f,
                1.0f, -1.0f, 0, 1.f, 0.f,
                -1.0f, 1.0f, 0, 0.f, 1.f,
                1.0f, 1.0f, 0, 1.f, 1.f,
        };
        private FloatBuffer mTriangleVertices;
        private float[] mMVPMatrix = new float[16];
        private float[] mSTMatrix = new float[16];
        private int mProgram;
        private int muMVPMatrixHandle;
        private int muSTMatrixHandle;
        private int maPositionHandle;
        private int maTextureHandle;

        public S3DTextureRender() {
            mTriangleVertices = ByteBuffer.allocateDirect(
                    mTriangleVerticesData.length * FLOAT_SIZE_BYTES)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            mTriangleVertices.put(mTriangleVerticesData).position(0);

            Matrix.setIdentityM(mSTMatrix, 0);
        }

        public void drawFrame( int _texID, int _width, int _height)
        {
            float[] _matrix = {
                    // X, Y, X, U, V
                    0.0f, -1.0f,  0.0f, 0.0f,
                    -1.0f, 0.0f,  0.0f, 0.0f,
                    0.0f,  0.0f,  1.0f, 0.0f,
                    1.0f,  1.0f,  0.0f, 1.0f
            };
            drawFrame( _texID, _matrix);
        }


            public void drawFrame( int _texID, float[] _matrix) {
            checkGlError("onDrawFrame start");

            GLES20.glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(mProgram);
            checkGlError("glUseProgram");

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, _texID);
            checkGlError("glBindTexture");


            mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
            GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
            checkGlError("glVertexAttribPointer maPosition");
            GLES20.glEnableVertexAttribArray(maPositionHandle);
            checkGlError("glEnableVertexAttribArray maPositionHandle");

            mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
            GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
            checkGlError("glVertexAttribPointer maTextureHandle");
            GLES20.glEnableVertexAttribArray(maTextureHandle);
            checkGlError("glEnableVertexAttribArray maTextureHandle");

            Matrix.setIdentityM(mMVPMatrix, 0);
            GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0);
            GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            checkGlError("glDrawArrays");

            GLES20.glFinish();
        }

        /**
         * Initializes GL state.  Call this after the EGL surface has been created and made current.
         */
        public void surfaceCreated() {
            mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (mProgram == 0) {
                throw new RuntimeException("failed creating program");
            }
            maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
            checkGlError("glGetAttribLocation aPosition");
            if (maPositionHandle == -1) {
                throw new RuntimeException("Could not get attrib location for aPosition");
            }
            maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
            checkGlError("glGetAttribLocation aTextureCoord");
            if (maTextureHandle == -1) {
                throw new RuntimeException("Could not get attrib location for aTextureCoord");
            }

            muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
            checkGlError("glGetUniformLocation uMVPMatrix");
            if (muMVPMatrixHandle == -1) {
                throw new RuntimeException("Could not get attrib location for uMVPMatrix");
            }

            muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
            checkGlError("glGetUniformLocation uSTMatrix");
            if (muSTMatrixHandle == -1) {
                throw new RuntimeException("Could not get attrib location for uSTMatrix");
            }

        }

        /**
         * Replaces the fragment shader.  Pass in null to resetWithChunk to default.
         */
        public void changeFragmentShader(String fragmentShader) {
            if (fragmentShader == null) {
                fragmentShader = FRAGMENT_SHADER;
            }
            GLES20.glDeleteProgram(mProgram);
            mProgram = createProgram(VERTEX_SHADER, fragmentShader);
            if (mProgram == 0) {
                throw new RuntimeException("failed creating program");
            }
        }

        private int loadShader(int shaderType, String source) {
            int shader = GLES20.glCreateShader(shaderType);
            checkGlError("glCreateShader type=" + shaderType);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                Log.e(TAG, "Could not compile shader " + shaderType + ":");
                Log.e(TAG, " " + GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                shader = 0;
            }
            return shader;
        }

        private int createProgram(String vertexSource, String fragmentSource) {
            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            if (vertexShader == 0) {
                return 0;
            }
            int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            if (pixelShader == 0) {
                return 0;
            }

            int program = GLES20.glCreateProgram();
            checkGlError("glCreateProgram");
            if (program == 0) {
                Log.e(TAG, "Could not create program");
            }
            GLES20.glAttachShader(program, vertexShader);
            checkGlError("glAttachShader");
            GLES20.glAttachShader(program, pixelShader);
            checkGlError("glAttachShader");
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link program: ");
                Log.e(TAG, GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                program = 0;
            }
            return program;
        }

        public void release()
        {
            if( 0!= mProgram)
            {
                GLES20.glDeleteProgram(mProgram);
                mProgram = 0;
            }
        }


        public void checkGlError(String op) {
            int error;
            while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
                Log.e(TAG, op + ": glError " + error);
                throw new RuntimeException(op + ": glError " + error);
            }
        }
    }


    private static class CodecData
    {
    	public byte[] data = null;
    	public long	pts = 0;
        public int flag;
    }


    public void startEncoderThread()
    {
        encoderThread = new Thread(new Runnable() {

            @SuppressLint("NewApi")
            @Override
            public void run() {
                isRunning = true;
                int[] input = null;
                YXLog("thread running start!!!");

                while (isRunning)
                {
                    // 初始化与重新初始化;
                    if (true == isNeedReconfigure||(configStatus&maskForceRestart)!=0)
                    {
                        if( configStatus==maskBitRate && Build.VERSION.SDK_INT>=19)// SDK_INT >= 19 支持动态码率设置，不需要重新配置编码器
                        {
                            Bundle config = new Bundle();
                            config.putInt( MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitRate);
                            m_mediaCodec.setParameters(config);
                            configStatus = 0;
                        }
                        else
                        {
                            restartEncoder();
                        }

                        isNeedReconfigure = false;
                    }

                    // 把数据导出干净，不然数据堵上后就卡在塞数据的地方;
                    drainOutputBuffer();

                    if( null != mTextureManager && mTextureManager.getCacheSize()>0)
                    {
                        try
                        {
                            mTextureManager.videoMutexLock();
                            if( m_testRuntime)
                                Log.e( TAG, "makeEncodeContextCurrent");
                            mTextureRender.drawFrame(mTextureManager.getVideoFrame(), width, height);
                            mTextureManager.deleteVideoFrame();
                            mTextureManager.videoMutexUnlock();

                            if( m_testRuntime)
                                Log.e( TAG, "drawFrame");
                            if( null != mCodecInputSurface)
                            {
                                mCodecInputSurface.setPresentationTime(computePresentationTime(m_getnerateIndex));
                            }

                            if( m_testRuntime)
                                Log.e( TAG, "setPresentationTime");
                            if( null != mCodecInputSurface)
                            {
                                mCodecInputSurface.swapBuffers();
                            }
                            if( null != mNativeCodecInputSurface)
                            {
                                mNativeCodecInputSurface.swapBuffers();
                            }

                            if( m_testRuntime)
                                Log.e( TAG, "swapBuffers");
                        }
                        catch (Exception e)
                        {
                            // TODO Auto-generated catch block
                            e.printStackTrace();
                        }
                        m_getnerateIndex += 1;

                    }

                    // 这里又导出一遍，也许用不着；
                    drainOutputBuffer();

                    if ( null == mTextureManager || mTextureManager.getCacheSize()<=0)
                    {
                        // 等待罗;
                        try {
                            Thread.sleep(30);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }

                }


                if( null != mTextureManager)
                {
                    mTextureManager.cleanup();
                    mTextureManager.releaseManager();
                    mTextureManager = null;
                }

                closeEncoder();
                YXLog("thread running end!!!");

            }

        });
        encoderThread.start();

    }



    public void startEncoderThread2()
    {
        encoderThread = new Thread(new Runnable() {

            @SuppressLint("NewApi")
            @Override
            public void run() {
                isRunning = true;
                byte[] input;
                int inputBufferIndex = -1;
                YXLog("thread running start!!!");

                while (isRunning)
                {
                    // 初始化与重新初始化;
                    if (true == isNeedReconfigure||(configStatus&maskForceRestart)!=0)
                    {
                        if( configStatus==maskBitRate && Build.VERSION.SDK_INT>=19)// SDK_INT >= 19 支持动态码率设置，不需要重新配置编码器
                        {
                            Bundle config = new Bundle();
                            config.putInt( MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitRate);
                            m_mediaCodec.setParameters(config);
                            configStatus = 0;
                        }
                        else
                        {
                            restartEncoder();
                        }

                        isNeedReconfigure = false;
                    }

                    // 把数据导出干净，不然数据堵上后就卡在塞数据的地方;
                    drainOutputBuffer();
                    input = inputQueue2.poll();

                    inputBufferIndex = m_mediaCodec.dequeueInputBuffer(TIMEOUT_USEC);
                    if (inputBufferIndex >= 0) {
                        long pts = computePresentationTime(m_getnerateIndex);
                        ByteBuffer inputBuffer = getInputBufferByIdx(inputBufferIndex);
                        inputBuffer.clear();
                        inputBuffer.put(input);
                        m_mediaCodec.queueInputBuffer(inputBufferIndex, 0, input.length, pts, 0);
                        ++m_getnerateIndex;
                    }

                    // 这里又导出一遍，也许用不着；
                    drainOutputBuffer();

                    if ( inputQueue2.size()<=0)
                    {
                        // 等待罗;
                        try {
                            Thread.sleep(30);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }

                }

                closeEncoder();
                YXLog("thread running end!!!");

            }

        });
        encoderThread.start();

    }

    public void stopEncoderThread()
    {
        isRunning = false;
        try {
            encoderThread.join();
        } catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }

}
