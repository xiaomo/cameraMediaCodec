package com.android.testtool;

import java.nio.ByteBuffer;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaCodec.BufferInfo;
import android.util.Log;
import android.view.Surface;




public class AvcDecoder
{
	public static final int R_BUFFER_OK = 0;
	public static final int R_TRY_AGAIN_LATER = -1;
	public static final int R_OUTPUT_UPDATE = -2;
	public static final int R_INVALIDATE_BUFFER_SIZE = -10;
	public static final int R_UNKNOWN = -40;
	
	public static final int STATUS_INVALID = 0;	//
	public static final int STATUS_LOADED = 1;	//component loaded, but not initialized. only accept call of set/getparameter
	public static final int STATUS_IDLE = 2;	//component initialized, ready to start
	public static final int STATUS_EXEC = 3;	//after start, it is processing data
	public static final int STATUS_WAIT = 5;	//waiting for resouces
	
	
	private MediaCodec mMC = null;
	private String MIME_TYPE = "video/avc";
	private MediaFormat mMF = null;
	private ByteBuffer[] mInputBuffers = null;
	private ByteBuffer[] mOutputBuffers = null;
	private BufferInfo mBI = null;
	private int mStatus = STATUS_INVALID;
	private final int BUFFER_TIMEOUT = 0; //microseconds
	
	
	public int Init()
	{
		Log.i("AvcDecoder", "Init");
		mMC = MediaCodec.createDecoderByType(MIME_TYPE);
		mStatus = STATUS_LOADED;
		mBI = new BufferInfo();
		Log.i("AvcDecoder", "Init, createDecoderByType");
		return 0;
	}
	
	public int tryConfig(Surface surface, byte[] sps, byte[] pps)
	{
		int[] width = new int[1];
		int[] height = new int[1];
		
		AvcUtils.parseSPS(sps, width, height);
		
		mMF = MediaFormat.createVideoFormat(MIME_TYPE, width[0], height[0]);
		
		mMF.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
		mMF.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
		mMF.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width[0] * height[0]);
		
		mMC.configure(mMF, surface, null, 0);
		Log.i("AvcDecoder", "Init, configure");
		mStatus = STATUS_IDLE;
		
		return 0;
	}
	
	public void Uninit()
	{
		Log.i("AvcDecoder", "Uninit");
		stop();
		mMC.release();
		mMC = null;
		mBI = null;
	}
	
	public void start()
	{
		Log.i("AvcDecoder", "start");
		if (mStatus == STATUS_EXEC)
		{
			Log.d("AvcDecoder", "wrong status:"+mStatus);
			return;
		}
		
		if (mMC != null)
		{
			mMC.start();
			mInputBuffers = mMC.getInputBuffers();
			mOutputBuffers = mMC.getOutputBuffers();
			mStatus = STATUS_EXEC;
		}
	}
	
	public void stop()
	{
		Log.i("AvcDecoder", "stop");
		if (mStatus != STATUS_EXEC)
		{
			Log.d("AvcDecoder", "wrong status:"+mStatus);
			return;
		}
		
		if (mMC != null)
		{
			mMC.flush();
			mMC.stop();
			mStatus = STATUS_IDLE;
		}
	}
	
	public void flush()
	{
		Log.i("AvcDecoder", "flush");
		if (mStatus != STATUS_EXEC)
		{
			Log.d("AvcDecoder", "wrong status:"+mStatus);
			return;
		}
		
		if (mMC != null)
		{
			mMC.flush();
		}
	}
	
	public int InputAvcBuffer(/*in*/byte[] bytes, /*in*/int len, /*in*/long timestamp, /*in*/int flag)
	{
		//Log.i("AvcDecoder", "InputAvcBuffer ++");
		if (mStatus != STATUS_EXEC)
		{
			//Log.d("AvcDecoder", "wrong status:"+mStatus);
			return R_TRY_AGAIN_LATER;
		}
		
		int inputbufferindex = mMC.dequeueInputBuffer(BUFFER_TIMEOUT);
		if (inputbufferindex >= 0)
		{
			ByteBuffer inputBuffer = mInputBuffers[inputbufferindex];
			inputBuffer.clear();
			int capacity = inputBuffer.capacity();
			
			if (capacity < len)
			{
				mMC.queueInputBuffer(inputbufferindex, 0, 0, timestamp, flag); 	//return the buffer to OMX quickly
				Log.e("AvcDecoder", "InputAvcBuffer, input size invalidate, capacity="+capacity+",len="+len);
				return R_INVALIDATE_BUFFER_SIZE;
			}
			
			inputBuffer.put(bytes, 0, len);
			
			mMC.queueInputBuffer(inputbufferindex, 0, len, timestamp, flag);
			
			//Log.i("AvcDecoder", "InputAvcBuffer -- OK, capacity="+capacity);
		}
		else if (inputbufferindex == MediaCodec.INFO_TRY_AGAIN_LATER)
		{
			//Log.i("AvcDecoder", "InputAvcBuffer -- INFO_TRY_AGAIN_LATER");
//			try {
//				Thread.sleep(1);
//			} catch (InterruptedException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
			
			return R_TRY_AGAIN_LATER;
		}
		else
		{
			//unexpected return value, not specified in Android doc
			return R_UNKNOWN;
		}
		return R_BUFFER_OK;
	}
	
	//usage: int[] len = new int[1];
	//long[] ts = new long[1];
	public int OutputRawBuffer(/*out*/byte[] bytes, /*in, out*/int[] len, /*out*/long[] timestamp)
	{
		//Log.i("AvcDecoder", "OutputRawBuffer ++");
		if (mStatus != STATUS_EXEC)
		{
			//Log.d("AvcDecoder", "wrong status:"+mStatus);
			return R_TRY_AGAIN_LATER;
		}
		
		int outputbufferindex = mMC.dequeueOutputBuffer(mBI, BUFFER_TIMEOUT);
		if (outputbufferindex >= 0)
		{
			if (mOutputBuffers[outputbufferindex] != null)
			{
				mOutputBuffers[outputbufferindex].position(mBI.offset);
				mOutputBuffers[outputbufferindex].limit(mBI.offset + mBI.size);
				
				if (bytes != null)
					mOutputBuffers[outputbufferindex].get(bytes, 0, mBI.size);
				len[0] = mBI.size;
				timestamp[0] = mBI.presentationTimeUs;
			}
			mMC.releaseOutputBuffer(outputbufferindex, true);
			
			//Log.i("AvcDecoder", "OutputRawBuffer -- OK at "+ outputbufferindex+", size="+len[0]);
		}
		else if (outputbufferindex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED)
		{
			mOutputBuffers = mMC.getOutputBuffers();
			Log.i("AvcDecoder", "OutputRawBuffer -- INFO_OUTPUT_BUFFERS_CHANGED");
			return R_OUTPUT_UPDATE;
		}
		else if (outputbufferindex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)
		{
			mMF = mMC.getOutputFormat();
			//it is so wired, the "MediaFormat" is little usage. They says "There is no way to query the encoder for the MediaFormat"
			
			
			//int new_width = mMF.getInteger(MediaFormat.KEY_WIDTH);
			//int new_height = mMF.getInteger(MediaFormat.KEY_HEIGHT);
			//int new_bps = mMF.getInteger(MediaFormat.KEY_BIT_RATE);
			//int new_cf = mMF.getInteger(MediaFormat.KEY_COLOR_FORMAT);
			//int new_fps = mMF.getInteger(MediaFormat.KEY_FRAME_RATE);
			
			Log.i("AvcDecoder", "OutputRawBuffer -- INFO_OUTPUT_FORMAT_CHANGED");
			//Log.i("AvcDecoder", "OutputRawBuffer -- INFO_OUTPUT_FORMAT_CHANGED: "+new_width+"x"+new_height+"@"+new_fps+/*",in "+new_bps+"bps"+*/", cf="+new_cf);
			return R_OUTPUT_UPDATE;
		}
		else if (outputbufferindex == MediaCodec.INFO_TRY_AGAIN_LATER)
		{
			//Log.i("AvcDecoder", "OutputRawBuffer -- INFO_TRY_AGAIN_LATER");
//			try {
//				Thread.sleep(10);
//			} catch (InterruptedException e) {
//				// TODO Auto-generated catch block
//				e.printStackTrace();
//			}
			
			return R_TRY_AGAIN_LATER;
		}
		else
		{
			//unexpected return value, not specified in Android doc
			return R_UNKNOWN;
		}
		
		
		return R_BUFFER_OK;
	}
}