package com.kuoster.gifanimationdrawable;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

public class GifAnimationDrawable extends AnimationDrawable {
    private static final String TAG = GifAnimationDrawable.class.getSimpleName();

    private enum DecodeStatus {
        DECODE_STATUS_UNDECODED,
        DECODE_STATUS_DECODING,
        DECODE_STATUS_DECODED
    }

    private String mFilePath;
    private int mResId;
    private Resources mRes;
    private int mWidth = 0;
    private int mHeight = 0;

    private DecodeStatus mDecodeStatus;

    public GifAnimationDrawable() {
        super();
        this.mFilePath = null;
        this.mResId = 0;
        mDecodeStatus = DecodeStatus.DECODE_STATUS_UNDECODED;
    }

    public GifAnimationDrawable(Resources res, int resId) {
        this.setGif(res, resId);
    }

    public GifAnimationDrawable(String filepath) {
        this.mFilePath = filepath;
        this.mResId = 0;
        //TODO implementation
        throw new UnsupportedOperationException("Method not implemented.");
    }


    private void setGif(Resources res, int resId) {
        this.mFilePath = null;
        this.mResId = resId;
        this.mRes = res;
        this.mDecodeStatus = DecodeStatus.DECODE_STATUS_UNDECODED;
        final Bitmap bm = BitmapFactory.decodeResource(res, resId);
        mWidth = bm.getWidth();
        mHeight = bm.getHeight();
        //Log.d(TAG, String.format("Image size (%d, %d)", mWidth, mHeight));
    }

    private void decode() {
        this.mDecodeStatus = DecodeStatus.DECODE_STATUS_DECODING;

        GifDecoder2 dec = new GifDecoder2();
        dec.load(getInputStream());
        final int frameCnt = dec.getFrameCount();
        //Log.d(TAG, String.format("Frame count: %d", frameCnt));
        for(int idx = 0; idx < frameCnt; ++idx) {
            BitmapDrawable bd = new BitmapDrawable(mRes, dec.getFrame(idx));
            //Log.d(TAG, "Add frame " + String.valueOf(idx));
            addFrame(bd, dec.getDelayMS(idx));
        }
        setOneShot(!dec.isLooped());
        //setOneShot(false);

        this.mDecodeStatus = DecodeStatus.DECODE_STATUS_DECODED;
    }

    private InputStream getInputStream() {
        if(this.mFilePath != null) {
            try {
                return new FileInputStream(mFilePath);
            }
            catch (FileNotFoundException e) {
                // No file, no loading, no action, nothing.
            }
        }
        if(this.mResId > 0) {
            return mRes.openRawResource(mResId);
        }
        return null;
    }

    /** AnimationDrawable adapter */
    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);

        if(mDecodeStatus == DecodeStatus.DECODE_STATUS_UNDECODED) {
            Log.d(TAG, "draw(), decode()");
            decode();
            if(!isRunning()) {
                Log.d(TAG, "auto start()");
                start();
            }
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return mWidth;
    }

    @Override
    public int getIntrinsicHeight() {
        return mHeight;
    }

    public DecodeStatus decodeStatus() {
        return mDecodeStatus;
    }


}