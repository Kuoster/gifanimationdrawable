package com.kuoster.gifanimationdrawable;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Vector;

public class GifDecoder2 {
    private static final String TAG = GifDecoder2.class.getSimpleName();
    private static final boolean SHOW_GIF_INFO = false;

    private static final int SENTINEL_IMAGE = 0x2c;
    private static final int SENTINEL_EXTENSION_BLOCK = 0x21;
    private static final int SENTINEL_TRAILER = 0x3b;

    /**
     * A decoded gif frame, with decoded Bitmap and frame duration.
     */
    private static class Frame {
        Bitmap bitmap;
        int duration;

        Frame(Bitmap bitmap, int duration) {
            this.bitmap = bitmap;
            this.duration = duration;
        }
    }

    private static class LogicalScreenDescriptor {
        int width;
        int height;
        /** Global Color Table Size */
        int gctSize;
        boolean gctExists;
        boolean gctSorted;
        int backgroundIndex;
        int defaultPixelAspectRatio;// w/h // NOTE Currently not used in this class (assume 1)

        int colorResolution;// Not used

        LogicalScreenDescriptor(InputStream is) {
            this.width = readUShort(is);
            this.height = readUShort(is);
            int flags = readByte(is);
            this.backgroundIndex = readByte(is);
            this.defaultPixelAspectRatio = readByte(is);

            this.gctExists = ((flags & 0x80) == 0x80);
            this.colorResolution = (flags & 0x70) >> 4;
            this.gctSorted = ((flags & 0x08) == 0x08);
            this.gctSize = (int)Math.pow(2, ((flags & 0x07)  + 1));// GCT Size

            if(SHOW_GIF_INFO) {
                Log.i(TAG, "GIF Logical Screen Descriptor");
                Log.i(TAG, "Width:  " + String.valueOf(this.width));
                Log.i(TAG, "Height: " + String.valueOf(this.height));
                Log.i(TAG, "GCT Exists: " + String.valueOf(this.gctExists));
                Log.i(TAG, "Color res:  " + String.valueOf(this.colorResolution));
                Log.i(TAG, "GCT Sorted: " + String.valueOf(this.gctSorted));
                Log.i(TAG, "GCT Size:   " + String.valueOf(this.gctSize));
                Log.i(TAG, "Background Color Index: " + String.valueOf(this.backgroundIndex));
            }
        }
    }

    private static class ColorTable {
        private static final int NO_TRANSPARENCY_INDEX = -1;
        int transparencyIndex = NO_TRANSPARENCY_INDEX;
        int[] colors;
        public ColorTable(InputStream is, int ctSize) {
            if(ctSize > 0) {
                this.colors = new int[ctSize];
                for(int i = 0; i < ctSize; ++i) {
                    this.colors[i] = Color.rgb(readByte(is), readByte(is), readByte(is));
                    if(SHOW_GIF_INFO) {
                        Log.d(TAG, String.format("Color %d: %x", i, this.colors[i]));
                    }
                }
            }
            else {
                this.colors = null;
            }
        }

        public void setTransparentColor(int transparencyIndex) {
            if(this.transparencyIndex != NO_TRANSPARENCY_INDEX) {
                resetTransparentColor();
            }
            this.transparencyIndex = transparencyIndex;
            this.colors[transparencyIndex] = this.colors[transparencyIndex] & 0xffffff;// 0rgb
        }

        public void resetTransparentColor() {
            if(transparencyIndex != NO_TRANSPARENCY_INDEX) {
                colors[transparencyIndex] = 0xff000000 | colors[transparencyIndex];
            }
            transparencyIndex = NO_TRANSPARENCY_INDEX;
        }
    }

    private static class GraphicControlExtension {
        public static final int NO_ACTION = 0;
        public static final int DO_NOT_DISPOSE = 1;
        public static final int RESTORE_BG_COLOR = 2;
        public static final int RESTORE_TO_PREVIOUS = 3;

        int disposalMethod;
        boolean userInput;
        boolean transparencyFlag;
        int delayMS;//millisecond
        int transparencyIndex;

        GraphicControlExtension(InputStream is) {
            int blockSize = readByte(is);
            int flags = readByte(is);

            this.disposalMethod = ((flags & 0x1c) >> 2);
            this.userInput = ((flags & 0x02) == 0x02);
            this.transparencyFlag = ((flags & 0x01) == 0x01);
            this.delayMS = readUShort(is) * 10;// Convert 1/100s to ms
            this.transparencyIndex = readByte(is);

            // Skip rest of subblocks in GCE, they shouldn't be here
            try {
                skipBlock(is);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private Vector<Frame> mFrames;
    private Bitmap mFrameBase;/** The background for the frame. It is constructed from last frame's data and disposal method */
    private LogicalScreenDescriptor mLSD;
    private ColorTable mGCT;
    private GraphicControlExtension mGCE;
    private boolean mIsLooped;

    public void load(InputStream is) {
        init();

        if(is != null) {
            if(readHeader(is)) {
                if(mGCT != null) {// Setup FrameBase for first frame as background color
                    mFrameBase = Bitmap.createBitmap(mLSD.width, mLSD.height, Bitmap.Config.ARGB_8888);
                    mFrameBase.eraseColor(Color.TRANSPARENT);
                }
                readBody(is);
                if(getFrameCount() <= 0) {
                    //Error, there must be at least one frame.
                    Log.e(TAG, "load() frame count <= 0");
                }
                else {
                    Log.d(TAG, String.format("load() frame count = %d", getFrameCount()));
                }
            }
            else {
                Log.d(TAG, "load() readHeader() failed");
            }
        }
        else {
            throw new NullPointerException("No data to load.");
        }

        try {
            is.close();
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "Error closing gif stream");
        }
    }

    int getFrameCount() {
        return mFrames.size();
    }

    boolean isLooped() {
        return mIsLooped;
    }

    Bitmap getFrame(int index) {
        if(index < mFrames.size()) {
            return mFrames.get(index).bitmap;
        }
        return null;
    }

    int getDelayMS(int index) {
        if(index < mFrames.size()) {
            return mFrames.get(index).duration;
        }
        return 0;
    }

    private void readBody(InputStream is) {
        int sentinel;
        do {
            sentinel = readByte(is);
            switch (sentinel) {
                case SENTINEL_IMAGE:
                    readImageBlock(is);
                    mGCE = null;// GCE is used for one frame then removed.
                    break;
                case SENTINEL_EXTENSION_BLOCK:
                    readExtensionBlock(is);
                    break;
                case SENTINEL_TRAILER://End of file
                    break;
                default:// Unknown block

            }
        } while(sentinel != SENTINEL_TRAILER);
    }

    private static final int EXTENSION_GRAPHIC_CONTROL = 0xf9;
    private static final int EXTENSION_COMMENT = 0xfe;
    private static final int EXTENSION_PLAIN_TEXT = 0x01;
    private static final int EXTENSION_APPLICATION = 0xff;

    private void readExtensionBlock(InputStream is) {
        int extType = readByte(is);

        switch(extType) {
            case EXTENSION_GRAPHIC_CONTROL:
                mGCE = new GraphicControlExtension(is);
                break;
            case EXTENSION_APPLICATION:
                //TODO need to read loop count from Netscape extension
            case EXTENSION_COMMENT:
            case EXTENSION_PLAIN_TEXT:
            default:
                try {
                    skipBlock(is);
                } catch (IOException e) {
                    e.printStackTrace();
                }
        }
    }

    private static void skipBlock(InputStream is) throws IOException {
        int subBlockSize;
        while((subBlockSize = readByte(is)) != 0x0) {
            is.skip(subBlockSize);
        }
    }

    private static class SubblockByteStream {
        final InputStream is;
        boolean isEnd;
        boolean isErr;
        byte[] buffer;
        int bytesInBuffer;
        int bytesInBufferRead;
        SubblockByteStream(InputStream is) {
            assert is != null;
            this.is = is;
            this.isEnd = false;
            this.isErr = false;
            this.buffer = new byte[255];// Subblock has at most 255 bytes
            this.bytesInBuffer = 0;
            this.bytesInBufferRead = 0;
        }

        boolean isEnd() {
            return this.isEnd;
        }

        boolean isError() {
            return this.isErr;
        }

        int readByte() {
            if(bytesInBufferRead < bytesInBuffer) {
                return ((int)buffer[bytesInBufferRead++]) & 0xff;
            } else {
                try {
                    this.bytesInBuffer = is.read();
                    if(this.bytesInBuffer == 0) {
                        this.isEnd = true;
                        return -1;
                    }
                    //Log.d(TAG, String.format("Read %d bytes", bytesInBuffer));
                    int bytesRead = 0;
                    while(bytesRead < bytesInBuffer) {
                        bytesRead += is.read(this.buffer, bytesRead, this.bytesInBuffer - bytesRead);
                    }
                    this.bytesInBufferRead = 0;
                    return ((int)this.buffer[this.bytesInBufferRead++] & 0xff);
                } catch (IOException e) {
                    e.printStackTrace();
                    this.isEnd = true;
                    this.isErr = true;
                    return -1;
                }
            }
        }
    }


    private void readImageBlock(InputStream is) {
        if(SHOW_GIF_INFO) {
            Log.i(TAG, "Begin ImageBlock -----------------------------------");
        }

        // Begin image descriptor
        int x = readUShort(is);
        int y = readUShort(is);
        int w = readUShort(is);
        int h = readUShort(is);
        int lctSize;
        int flags = readByte(is);// Local color table size
        ColorTable lct = null;

        boolean interlaced = ((flags & 0x40) == 0x40);
        boolean lctSorted = ((flags & 0x20) == 0x20);
        if((flags & 0x80) == 0x80) {// Has LCT
            lctSize = (int)Math.pow(2, ((flags & 0x07)  + 1));
            if(lctSize > 0) {
                lct = new ColorTable(is, lctSize);
            }
        }
        if(lct == null) {// No LCT, use GCT
            Log.i(TAG, "No LCT, use GCT");
            lct = mGCT;
        }
        // End image descriptor

        if(SHOW_GIF_INFO) {
            Log.i(TAG, "Image origin: " + String.format("(%d, %d)", x, y));
            Log.i(TAG, "Image size:   " + String.format("(%d, %d)", w, h));
            Log.i(TAG, "LCT size:     " + String.format("%d", lct.colors.length));
        }

        /**
         * Terminology:
         * pix - An uncompressed pixel
         * pixStr - A series of pixels
         * code - A compressed unit. A code is key to a pixStr
         * codeStr - A series of codes
         * entry - {code, pixStr} pair
         * prefix - The prefix code of a code. Because a pixStr is made of either "a single pix", or "a pixStr from another entry + a single pix"
         *
         */


        // Begin image data
        final int MAX_DICTIONARY_SIZE = 4096;//12bits
        //Bitmap bm = Bitmap.createBitmap(mLSD.width, mLSD.height, Bitmap.Config.ARGB_8888);// Output frame
        final int rootSize = readByte(is);// Decoded data element size (Value is at least 2)
        //final int compressionCodeSize = rootSize + 1;// GIF89a appendix F: This code size value also implies that the compression codes must start out one bit longer.
        final int CLEAR_CODE = (1 << rootSize);

        if(SHOW_GIF_INFO) {
            Log.d(TAG, String.format("RootSize = %d", rootSize));
            Log.d(TAG, String.format("ClearCode = %x", CLEAR_CODE));
        }
        /**
         * prefix stores which code is prepended for this code. It is used to look up the entire code string without using more memory space.
         */
        // Moved to LZWDictionary
        //short prefix[] = new short[MAX_DICTIONARY_SIZE];// prefixes with index smaller than END_OF_INFORMATION is not used (because they don't have prefixes)


        // Process blocks
        final int imageSize = w * h;
        //byte[] pixels = new byte[imageSize];
        ByteArrayOutputStream pixelsStream = new ByteArrayOutputStream(imageSize);

        SubblockByteStream codeStream = new SubblockByteStream(is);
        LZWDictionary codeBook = new LZWDictionary(rootSize, pixelsStream, imageSize);
        for(int pixelCnt = 0; pixelCnt < imageSize;) {// Decode until image is filled
            int code = codeStream.readByte();
            if(code < 0) {
                // check if it's error or end of block
                if(codeStream.isError()) {
                    Log.e(TAG, String.format("Error when reading image data (%d)", code));
                }
                break;
            }
            if(!codeBook.decode(code)) {
                //Log.d(TAG, "<EOI>");
                break;//<EOI> reached
            }
        }

        byte[] pixels = pixelsStream.toByteArray();
        if(SHOW_GIF_INFO) {
            Log.d(TAG, String.format("image block size %d, actual read size %d", imageSize, pixels.length));
        }

        // Set transparent pixel in color table
        if(mGCE != null && mGCE.transparencyFlag) {
            lct.setTransparentColor(mGCE.transparencyIndex);
        }

        // Construct frame
        int[] rgbPixels = new int[imageSize];
        if(!interlaced) {
            for(int i = 0; i < pixels.length; ++i) {
                if(pixels[i] >= lct.colors.length) {
                    Log.e(TAG, String.format("Pixel index invalid: %d", pixels[i]));
                }
                rgbPixels[i] = lct.colors[((int)pixels[i]) & 0xff];
            }
        }
        else {//interlaced
            final int[] startRows = {0, 4, 2, 1};
            final int[] stepRows = {8, 8, 4, 2};
            int outIdx = 0;
            for(int pass = 0; pass < 4; ++pass) {
                int curRow = startRows[pass];
                while(curRow < h) {// For each row
                    int curPixelIdx = curRow * w;
                    final int curPixelIdxEnd = curRow * w + w;
                    while(curPixelIdx < curPixelIdxEnd) {// For each pixel
                        rgbPixels[curPixelIdx] = lct.colors[((int)pixels[outIdx++]) & 0xff];
                        curPixelIdx++;
                    }
                    curRow += stepRows[pass];
                }
            }
        }

        // Draw frame
        Bitmap frame = Bitmap.createBitmap(mFrameBase);
        Canvas canvas = new Canvas(frame);
        canvas.drawBitmap(rgbPixels, 0, w, x, y, w, h, true, null);

        mFrames.add(new Frame(frame, (mGCE == null) ? 0 : mGCE.delayMS));// No duration if GCE doesn't exist
        //Log.d(TAG, String.format("FrameCount: %d", mFrames.size()));

        // Construct next frame's background based on disposal method
        if(mGCE != null) {
            switch (mGCE.disposalMethod) {
                case GraphicControlExtension.DO_NOT_DISPOSE:
                    mFrameBase = Bitmap.createBitmap(frame);
                    break;
                case GraphicControlExtension.RESTORE_BG_COLOR:
                    canvas.setBitmap(mFrameBase);
                    canvas.clipRect(x, y, x + w, y + h);
                    canvas.drawColor(lct.colors[mLSD.backgroundIndex], PorterDuff.Mode.SRC);
                    //Log.d(TAG, String.format("Restore to color %d, transparent color is %d", mLSD.backgroundIndex, mGCE.transparencyIndex));
                    break;
                // Do nothing with NO_ACTION and RESTORE_TO_PREVIOUS
            }
        }

        // Reset transparent pixel in color table
        if(mGCE != null && mGCE.transparencyFlag) {
            lct.resetTransparentColor();
        }

        mGCE = null;// GCE block is used for a single frame

        if(SHOW_GIF_INFO) {
            Log.i(TAG, "End ImageBlock -------------------------------------");
        }
    }

    private static int readByte(InputStream is) {
        int ret = 0;
        try {
            ret = is.read();
        } catch (IOException e) {
            e.printStackTrace();
            //TODO handle exception?
        }
        return ret;
    }

    private static int readUShort(InputStream is) {
        int ret = 0;
        try {
            // Little-endian
            ret = is.read();
            ret += is.read() * 0x100;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return ret;
    }

    private boolean readHeader(InputStream is) {
        String signature = "";
        // "GIFxxx"
        for(int i = 0; i < 6; ++i) {
            signature += (char)readByte(is);
        }
        if(!signature.startsWith("GIF")) {
            throw new RuntimeException("Not a valid gif file.");
        }
        mLSD = new LogicalScreenDescriptor(is);//Read LSD
        mGCT = new ColorTable(is, mLSD.gctSize);

        return true;
    }

    private void init() {
        mFrames = new Vector<Frame>();
        mLSD = null;
        mGCT = null;
        mIsLooped = true;
    }


}
