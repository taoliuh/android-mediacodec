package com.yixia.codec.codec;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Created by yangjie on 2017/6/20.
 */
@SuppressWarnings("unused")
@SuppressLint("NewApi")
public class YXAvcDecoder  implements SurfaceTexture.OnFrameAvailableListener
{
    private static final String TAG = "MediaCodecDecoder";
    private static final boolean m_verbose = false;
    private static final String VIDEO_MIME_TYPE = "video/avc";

    private static final int ERROR_OK = 0;
    private static final int ERROR_EOF = -1;
    private static final int ERROR_FAIL = -2;
    private static final int ERROR_UNUSUAL = -3;

    private int m_iWidth    = 0;
    private int m_iHeight   = 0;
    private ByteBuffer m_extraDataBuf = null;
    private ByteBuffer m_spsBuf = null;
    private ByteBuffer m_ppsBuf = null;
    private boolean m_bIsNeedReconfigure = false;

    private MediaFormat m_format = null;

    private int[] m_surfaceTexID = new int[1];
    private SurfaceTexture m_surfaceTexture = null;
    private Surface m_surface = null;

    private MediaCodec.BufferInfo m_bufferInfo = new MediaCodec.BufferInfo();

    private MediaCodec m_decoder = null;
    private boolean m_decoderStarted = false;

    private final Object m_frameSyncObject = new Object(); // guards m_frameAvailable
    private boolean m_frameAvailable = false;

    private long m_timestampOfLastDecodedFrame = Long.MIN_VALUE;
    private long m_timestampOfCurTexFrame = Long.MIN_VALUE;
    private boolean m_inputBufferQueued = false;
    private int m_pendingInputFrameCount = 0;
    private boolean m_sawInputEOS = false;
    private boolean m_sawOutputEOS = false;

    private SOES2DTextureRender m_textureRender = null;
    private EglStateSaver m_eglStateSaver = null;

    /**
     * 用延迟前几帧的输出，否则解码太快会花屏；
     */
    private final long MAX_SLEEP_MS = 0;
    private final int MAX_DELAY_COUNT = 10;
    private int m_iCurCount = 0;

    public YXAvcDecoder()
    {
    }

    /**
     * 初始化解码器
     * @param _width        宽度
     * @param _height       高度
     * @param sps           sps数据
     * @param spsSize       sps数据长度
     * @param pps           pps数据
     * @param ppsSize       pps数据长度
     * @return              返回状态值
     *
     * 若sps!=null,pps==null,则为使用extraData;
     */
    public int initDecoder( int _width, int _height,  byte[] sps, int spsSize, byte[] pps, int ppsSize)
    {
        int ret;

        Log.e( TAG, "width = " + _width + " height = " + _height);
        ret = setEncoder( _width, _height, sps, spsSize, pps, ppsSize);

        if ( m_bIsNeedReconfigure)
        {
            ret = reconfigureMediaFormat();
        }


        if ( null == m_eglStateSaver)
        {
            m_eglStateSaver = new EglStateSaver();
            m_eglStateSaver.saveEGLState();
        }

        if (m_bIsNeedReconfigure)
        {
            restartDecoder();
            m_bIsNeedReconfigure = false;
            m_eglStateSaver.saveEGLState();
        }

        return ret;
    }


    /**
     * 设置解码参数，用于动态重启解码器
     * @param _width        宽度
     * @param _height       高度
     * @param sps           sps数据
     * @param spsSize       sps数据长度
     * @param pps           pps数据
     * @param ppsSize       pps数据长度
     * @return              返回状态值
     */
    public int setEncoder( int _width, int _height,  byte[] sps, int spsSize, byte[] pps, int ppsSize)
    {
        m_iWidth = _width;
        m_iHeight = _height;
        m_spsBuf = null;
        m_ppsBuf = null;
        if (spsSize > 0)
        {
            m_spsBuf = ByteBuffer.wrap( sps, 0, spsSize);
        }

        if ( ppsSize > 0)
        {
            m_ppsBuf = ByteBuffer.wrap( pps, 0, ppsSize);
        }

        m_bIsNeedReconfigure = true;
        return 0;
    }


    /**
     * 关闭解码器
     * @return     0
     */
    public int closeEncoder()
    {
        EglStateSaver curState = null;
        if ( !EGL14.eglGetCurrentContext().equals(m_eglStateSaver.getSavedEGLContext()))
        {
            curState = new EglStateSaver();
            curState.saveEGLState();
            Log.e( TAG, "eglGetCurrentContext = " + EGL14.eglGetCurrentContext() + " getSavedEGLContext = " + m_eglStateSaver.getSavedEGLContext());
            m_eglStateSaver.makeSavedStateCurrent();
        }

        stopDecoder();
        deleteTexture();

        if (null != curState)
        {
            curState.makeSavedStateCurrent();
        }

         return 0;
    }

    /**
     * 解码
     * @param _frameData        待解码数据
     * @param _inputSize        数据长度
     * @param _timeStamp        时间戳
     * @param _outputTex        输出纹理ID
     * @return                  状态标记
     */
    public int decodeFrame( byte[] _frameData, int _inputSize, long _timeStamp, int _outputTex) {
        /**
         * 防止解码调用线程换环境；
         */
        if ( null == m_eglStateSaver)
        {
            m_eglStateSaver = new EglStateSaver();
            m_eglStateSaver.saveEGLState();
        }


        if ( !EGL14.eglGetCurrentContext().equals(m_eglStateSaver.getSavedEGLContext()))
        {
            Log.e( TAG, "eglGetCurrentContext = " + EGL14.eglGetCurrentContext() + " getSavedEGLContext = " + m_eglStateSaver.getSavedEGLContext());
            m_bIsNeedReconfigure = true;
        }

        if (m_bIsNeedReconfigure)
        {
            restartDecoder();
            m_bIsNeedReconfigure = false;
            m_eglStateSaver.saveEGLState();
        }

        if ( m_decoder == null)
        {
            return ERROR_FAIL;
        }

        long start = System.currentTimeMillis();
        int ret = DecodeFrame2Surface( _frameData, _inputSize, _timeStamp);
        long end = System.currentTimeMillis();
        long sleep = MAX_SLEEP_MS - (end - start);
        if ( m_iCurCount < MAX_DELAY_COUNT && sleep > 0)
        {
            try {
                Thread.sleep(sleep, 0);
                Log.e( TAG, "Sleep " + sleep + "ms for delay output!!!");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            ++m_iCurCount;
        }

        if ( ret == ERROR_OK)
        {
            m_surfaceTexture.updateTexImage();
            if ( null != m_textureRender && _outputTex > 0)
            {
                m_textureRender.drawFrame( m_iWidth, m_iHeight, m_surfaceTexID[0], _outputTex);
            }
        }


        return ret;
    }

    /**
     * 获取信息
     * @param _outBuf   输出数据的buf
     * @param _inFlag   标记
     * @return          数据长度
     */
    public int getInfoByFlag( int[] _outBuf, int _inFlag)
    {
        int status = 0;
        /**
         * 假装用1代表获取最近解码帧时间戳;
         */
        if( _inFlag==1)
        {
            _outBuf[0] = (int)(m_timestampOfLastDecodedFrame&0xFFFFFFFF);
            _outBuf[1] = (int)((m_timestampOfLastDecodedFrame>>32)&0xFFFFFFFF);
            status = 2;
        }
        else if ( _inFlag == 2)
        {
            _outBuf[0] = (int)(m_timestampOfCurTexFrame&0xFFFFFFFF);
            _outBuf[1] = (int)((m_timestampOfCurTexFrame>>32)&0xFFFFFFFF);
            status = 2;
        }

        return status;
    }

    public int flushDecoder()
    {
        int ret = ERROR_UNUSUAL;
        Log.e( TAG, "flushDecoder m_decoder = " + m_decoder);
        if ( m_decoder != null)
        {
            try
            {
                if ( m_sawInputEOS || m_sawOutputEOS)
                {
                    CleanupDecoder();
                    if (!SetupDecoder(VIDEO_MIME_TYPE))
                    {
                        ret = ERROR_FAIL;
                    }
                    else
                    {
                        if (m_verbose)
                            Log.e(TAG, "Decoder has been recreated.");
                        ret = ERROR_OK;
                    }

                }
                else
                {
                    if (m_inputBufferQueued) {
                        // NOTE: it seems that MediaCodec in some android devices (such as Xiaomi 2014011)
                        // will run into trouble if we call MediaCodec.flush() without queued any buffer before
                        m_decoder.flush();
                        m_inputBufferQueued = false;
                        m_pendingInputFrameCount = 0;
                        if (m_verbose)
                            Log.e(TAG, "Video decoder has been flushed.");

                        ret = ERROR_OK;
                    }
                }

            }
            catch ( Exception e)
            {
                e.printStackTrace();
            }
        }
        return ret;
    }

    private int reconfigureMediaFormat()
    {
        int ret = -1;
        try
        {
            m_format = MediaFormat.createVideoFormat(VIDEO_MIME_TYPE, m_iWidth, m_iHeight);

            if (null != m_spsBuf)
            {
                m_format.setByteBuffer("csd-0", m_spsBuf);
            }

            if ( null != m_ppsBuf)
            {
                m_format.setByteBuffer("csd-1", m_ppsBuf);
            }

            if (Build.VERSION.SDK_INT == 16) {
                // NOTE: some android 4.1 devices (such as samsung GT-I8552) will crash in MediaCodec.configure
                // if we don't set MediaFormat.KEY_MAX_INPUT_SIZE.
                // Please refer to http://stackoverflow.com/questions/22457623/surfacetextures-onframeavailable-method-always-called-too-late
                m_format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0);
            }

            ret = 0;

        }catch ( Exception e)
        {
            e.printStackTrace();
        }

        return ret;
    }

    private int createTexture(){
        GLES20.glGenTextures(1, m_surfaceTexID, 0);

        if(m_surfaceTexID[0] <= 0) {
            Log.e( TAG , "createTexture failed");
            return 0;
        }

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, m_surfaceTexID[0]);

        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);


        return m_surfaceTexID[0];
    }

    private int deleteTexture()
    {
        if ( m_surfaceTexID[0] != 0)
        {
            GLES20.glDeleteTextures( 1, m_surfaceTexID, 0);
            m_surfaceTexID[0] = 0;
        }

        return 0;
    }


    private int startDecoder()
    {
        if (IsValid()) {
            Log.e(TAG, "You can't call startDecoder() twice!");
            return -1;
        }

        int texId = m_surfaceTexID[0];
        if ( texId  == 0)
        {
            texId = createTexture();
        }

        if (texId == 0)
        {
            return -1;
        }

        //
        // Create SurfaceTexture and its wrapper Surface object
        //
        try
        {
            m_surfaceTexture = new SurfaceTexture(texId);
            Log.e(TAG, "Surface texture with texture (id=" + texId + ") has been created.");
            m_surfaceTexture.setOnFrameAvailableListener(this);
            m_surface = new Surface(m_surfaceTexture);
        }
        catch (Exception e) {
            Log.e(TAG, "" + e.getMessage());
            e.printStackTrace();
            stopDecoder();
            return -1;
        }

        m_textureRender = new SOES2DTextureRender( m_surfaceTexture);
        m_textureRender.surfaceCreated();


        if (!SetupDecoder(VIDEO_MIME_TYPE))
        {
            stopDecoder();
            return -1;
        }


        return 0;
    }

    private int stopDecoder()
    {

        CleanupDecoder();

        if ( m_textureRender != null)
        {
            m_textureRender.release();
            m_textureRender = null;
        }

        if (m_surface != null) {
            m_surface.release();
            m_surface = null;
        }

        if (m_surfaceTexture != null) {
            // this causes a bunch of warnings that appear harmless but might confuse someone:
            //  W BufferQueue: [unnamed-3997-2] cancelBuffer: BufferQueue has been abandoned!
            m_surfaceTexture.setOnFrameAvailableListener(null);
            m_surfaceTexture.release();
            m_surfaceTexture = null;
        }

        return 0;

    }

    private int restartDecoder()
    {
        stopDecoder();
        return startDecoder();
    }

    // SurfaceTexture callback
    @Override
    public void onFrameAvailable(SurfaceTexture st)
    {
        synchronized (m_frameSyncObject) {
            if (m_frameAvailable)
                Log.e(TAG, "m_frameAvailable already set, frame could be dropped!");

            m_frameAvailable = true;
            m_frameSyncObject.notifyAll();
        }
    }

    private boolean IsValid()
    {
        return m_decoder != null;
    }



    private boolean SetupDecoder(String mime)
    {
        // Create a MediaCodec decoder, and configure it with the MediaFormat from the
        // extractor.  It's very important to use the format from the extractor because
        // it contains a copy of the CSD-0/CSD-1 codec-specific data chunks.
        try {
            m_decoder = MediaCodec.createDecoderByType(mime);
            m_decoder.configure(m_format, m_surface, null, 0);
            m_decoder.start();
            m_decoderStarted = true;
            m_iCurCount = 0;

        } catch (Exception e) {
            Log.e(TAG, "" + e.getMessage());
            e.printStackTrace();
            CleanupDecoder();
            return false;
        }

        return true;
    }

    public void CleanupDecoder()
    {
        if (m_decoder != null) {
            if (m_decoderStarted) {
                try {
                    if (m_inputBufferQueued) {
                        m_decoder.flush();
                        m_inputBufferQueued = false;
                    }

                    m_decoder.stop();
                } catch (Exception e) {
                    Log.e(TAG, "" + e.getMessage());
                    e.printStackTrace();
                }
                m_decoderStarted = false;
            }
            m_decoder.release();
            m_decoder = null;
        }

        m_timestampOfLastDecodedFrame = Long.MIN_VALUE;
        m_timestampOfCurTexFrame = Long.MIN_VALUE;
        m_pendingInputFrameCount = 0;
        m_sawInputEOS = false;
        m_sawOutputEOS = false;

        if (m_verbose)
            Log.e(TAG, "CleanupDecoder called");
    }

    @SuppressWarnings("deprecation")
    private ByteBuffer getInputBufferByIdx(int _idx)
    {
        if( Build.VERSION.SDK_INT>=21)
        {
            return m_decoder.getInputBuffer(_idx);
        }
        else
        {
            ByteBuffer[] inputBuffers = m_decoder.getInputBuffers();
            return inputBuffers[_idx];
        }
    }


    private boolean AwaitNewImage()
    {
        final int TIMEOUT_MS = 500;

        synchronized (m_frameSyncObject) {
            while (!m_frameAvailable) {
                try {
                    // Wait for onFrameAvailable() to signal us.  Use a timeout to avoid
                    // stalling the test if it doesn't arrive.
                    m_frameSyncObject.wait(TIMEOUT_MS);
                    if (!m_frameAvailable) {
                        // TODO: if "spurious wakeup", continue while loop
                        Log.e(TAG, "Frame wait timed out!");
                        return false;
                    }
                } catch (InterruptedException ie) {
                    // Shouldn't happen
                    Log.e(TAG, "" + ie.getMessage());
                    ie.printStackTrace();
                    return false;
                }
            }

            m_frameAvailable = false;
        }

        if (m_verbose) {
            final int glErr = GLES20.glGetError();
            if (glErr != GLES20.GL_NO_ERROR)
                Log.e(TAG, "Before updateTexImage(): glError " + glErr);
        }

        if (m_verbose)
            Log.e(TAG, "frame is available, need updateTexImage");

        // Latch the data
//        m_surfaceTexture.updateTexImage();


        return true;
    }


    private int DecodeFrame2Surface(byte[] frameData, int inputSize, long timeStamp) {
        final int pendingInputBufferThreshold = 2;//Math.max(inputBufferCount / 3, 2);
        final int TIMEOUT_USEC = 30000;
        final int TIMEOUT_RETRY_COUNT = 20;
        boolean bUnusual = false;

        if (!m_sawInputEOS) {
            // Feed more data to the decoder
            int inputBufIndex = m_decoder.dequeueInputBuffer(TIMEOUT_USEC);
            int tryCount = 0;
            while ( inputBufIndex < 0)
            {
                try {
                    Thread.sleep( 5, 0);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                inputBufIndex = m_decoder.dequeueInputBuffer(TIMEOUT_USEC);
                ++tryCount;
                if ( tryCount >= TIMEOUT_RETRY_COUNT)
                {
                    Log.e( TAG, "try dequeueInputBuffer timeout -- " + tryCount);
                    break;
                }
            }
//            Log.e( TAG, "inputBufIndex = " + inputBufIndex);

            if (inputBufIndex >= 0) {
                ByteBuffer inputBuf = getInputBufferByIdx(inputBufIndex);

                if (inputSize == 0) {	// input EOF
                    // End of stream -- send empty frame with EOS flag set.
                    m_decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    m_sawInputEOS = true;
                    if (m_verbose)
                        Log.e(TAG, "Input EOS");
                } else {
                    inputBuf.clear();
                    inputBuf.put(frameData, 0, inputSize);

                    m_decoder.queueInputBuffer(inputBufIndex, 0, inputSize, timeStamp, 0);

                    if (m_verbose)
                        Log.e(TAG, "Submitted frame to decoder input buffer " + inputBufIndex + ", size=" + inputSize);

                    m_inputBufferQueued = true;
                    ++m_pendingInputFrameCount;
                    if (m_verbose)
                        Log.e(TAG, "Pending input frame count increased: " + m_pendingInputFrameCount);
                }
            } else {
                bUnusual = true;
                Log.e(TAG, "Input buffer not available");
//                if (m_verbose)
//                    Log.e(TAG, "Input buffer not available");
            }
        }

        // Determine the expiration time when dequeue output buffer
        int dequeueTimeoutUs;
        if ( m_sawOutputEOS)
        {
            dequeueTimeoutUs = TIMEOUT_USEC*20;
        }
        else if (m_pendingInputFrameCount > pendingInputBufferThreshold)
        {
            dequeueTimeoutUs = TIMEOUT_USEC;
        } else {
            // NOTE: Too few input frames has been queued and the decoder has not yet seen input EOS
            // wait dequeue for too long in this case is simply wasting time.
            dequeueTimeoutUs = 0;
        }

        // Dequeue output buffer
        int decoderStatus = -1;
        do {

            decoderStatus = m_decoder.dequeueOutputBuffer(m_bufferInfo, dequeueTimeoutUs);
            if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // Not important for us, since we're using Surface
                if (m_verbose)
                    Log.e(TAG, "Decoder output buffers changed");
            } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newFormat = m_decoder.getOutputFormat();
                if (m_verbose)
                    Log.e(TAG, "Decoder output format changed: " + newFormat);
            }
            else
            {
                break;
            }

        }while (true);

        if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
            // No output available yet
            if (m_verbose)
                Log.e(TAG, "No output from decoder available");
        } else if (decoderStatus < 0) {
            Log.e(TAG, "Unexpected result from decoder.dequeueOutputBuffer: " + decoderStatus);
            return ERROR_FAIL;
        } else { // decoderStatus >= 0
            if (m_verbose) {
                Log.e(TAG, "Surface decoder given buffer " + decoderStatus +
                        " (size=" + m_bufferInfo.size + ") " + " (pts=" + m_bufferInfo.presentationTimeUs + ") ");
            }

            if ((m_bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                if (m_verbose)
                    Log.e(TAG, "Output EOS");
                m_sawOutputEOS = true;
            }

            // Render to texture?
            boolean doRender = false;

            // NOTE: We don't use m_bufferInfo.size != 0 to determine whether we can render the decoded frame,
            // since some stupid android devices such as XIAOMI 2S, Xiaomi 2014011 ... will always report zero-sized buffer
            // if we have configured the video decoder with a surface.
            // Now we will render the frame if the m_bufferInfo didn't carry MediaCodec.BUFFER_FLAG_END_OF_STREAM flag.
            // NOTE: this method is a hack and we may lose the last video frame
            // if the last video frame carry the MediaCodec.BUFFER_FLAG_END_OF_STREAM flag.
            if (!m_sawOutputEOS) {
                // Update timestamp of last decoded video frame
                m_timestampOfLastDecodedFrame = m_bufferInfo.presentationTimeUs;
                --m_pendingInputFrameCount;
                if (m_verbose)
                    Log.e(TAG, "Pending input frame count decreased: " + m_pendingInputFrameCount);

                doRender = true;
            }

            // As soon as we call releaseOutputBuffer, the buffer will be forwarded
            // to SurfaceTexture to convert to a texture.  The API doesn't guarantee
            // that the texture will be available before the call returns, so we
            // need to wait for the onFrameAvailable callback to fire.
            m_decoder.releaseOutputBuffer(decoderStatus, doRender);
            if (doRender) {
                if (m_verbose)
                    Log.e(TAG, "Rendering decoded frame to surface texture.");

                if (AwaitNewImage()) {
                    // NOTE: don't use m_surfaceTexture.getTimestamp() here, it is incorrect!
                    m_timestampOfCurTexFrame = m_bufferInfo.presentationTimeUs;
                    if (m_verbose)
                        Log.e(TAG, "Surface texture updated, pts=" + m_timestampOfCurTexFrame);

                    return ERROR_OK;
                } else {
                    Log.e(TAG, "Render decoded frame to surface texture failed!");
                    return ERROR_FAIL;
                }
            }

            else
                return ERROR_EOF;
        }

        if (bUnusual)
            return ERROR_UNUSUAL;
        else
            return ERROR_FAIL;
    }




    public static boolean IsInAndriodHardwareBlacklist() {
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;

        // too slow
        if ((manufacturer.compareTo("Meizu") == 0) && (model.compareTo("m2") == 0))
            return true;

        if ((manufacturer.compareTo("Xiaomi") == 0) && (model.compareTo("MI 4W") == 0))
            return true;

        return false;
    }

    public static boolean IsInAndriodHardwareWhitelist() {
        String manufacturer = Build.MANUFACTURER;
        String model = Build.MODEL;

        if ((manufacturer.compareTo("samsung") == 0) && (model.compareTo("GT-I9152") == 0))
            return true;

        if ((manufacturer.compareTo("HUAWEI") == 0) && (model.compareTo("HUAWEI P6-C00") == 0))
            return true;

        return false;
    }



    /**
     * Code for rendering a texture onto a texture using OpenGL ES 2.0.
     * copy
     */
    private static class SOES2DTextureRender {
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

        public SOES2DTextureRender( SurfaceTexture surfaceTexture) {
            mTriangleVertices = ByteBuffer.allocateDirect(
                    mTriangleVerticesData.length * FLOAT_SIZE_BYTES)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            mTriangleVertices.put(mTriangleVerticesData).position(0);

            if ( null != surfaceTexture)
            {
                surfaceTexture.getTransformMatrix( mSTMatrix);
            }
            else
            {
                Matrix.setIdentityM( mSTMatrix, 0);
            }
        }


        public void drawFrame( int _width, int _height, int _inputTexID, int _outputTexID) {

            GLES20.glViewport(0, 0, _width, _height);

            GLES20.glBindTexture( GLES20.GL_TEXTURE_2D, _outputTexID);
            checkGlError("glBindTexture");

            GLES20.glFramebufferTexture2D( GLES20.GL_FRAMEBUFFER,  GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, _outputTexID, 0);
            checkGlError("glFramebufferTexture2D");



            checkGlError("onDrawFrame start");
            GLES20.glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(mProgram);
            checkGlError("glUseProgram");

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, _inputTexID);
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

            GLES20.glDisableVertexAttribArray(maPositionHandle);
            GLES20.glDisableVertexAttribArray(maTextureHandle);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
            GLES20.glBindTexture( GLES20.GL_TEXTURE_2D, 0);
            GLES20.glFramebufferTexture2D( GLES20.GL_FRAMEBUFFER,  GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, 0, 0);
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


}
