package com.kuoster.gifanimationdrawable;

import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * LZW Dictionary for gif
 */
class LZWDictionary {
    private static final String TAG = LZWDictionary.class.getSimpleName();
    private static final boolean SHOW_DEBUG_INFO = false;
    static final int MAX_DICTIONARY_SIZE = 4096;
    short[] prefix;
    byte[] pix;// The new added pix for this code
    byte[] headPix;
//    private final DictionaryEvents dictEvents;
    private final int rootSize;
    private final int CLEAR_CODE;
    private final int END_OF_INFORMATION;

    // state
    int curCodeSize;
    int curCodeMask;// Bits mask for one code, changes with curCodeSize
    int curCodeUpperBound;// non-inclusive upperbound
    int nextEmptyEntry;
    final int NO_CODE = -1;
    int prevCode;

    // Bit buffer
    int bitBuffer;
    int bitBufferLength;

    // Output
    final ByteArrayOutputStream outputStream;
    final int imageSize;

    LZWDictionary(int rootSize, ByteArrayOutputStream outputStream, int imageSize) {
        //this.dictEvents = dictEvents;
        this.rootSize = rootSize;
        this.outputStream = outputStream;
        this.imageSize = imageSize;
        this.CLEAR_CODE = (1 << rootSize);
        this.END_OF_INFORMATION = this.CLEAR_CODE + 1;
        this.prefix = new short[MAX_DICTIONARY_SIZE];
        this.pix = new byte[MAX_DICTIONARY_SIZE];
        this.headPix = new byte[MAX_DICTIONARY_SIZE];
        this.bitBuffer = this.bitBufferLength = 0;
        clear();
    }

    private void setCodeSize(int newSize) {
        curCodeSize = newSize;
        curCodeMask = 0xffffffff >>> (32 - newSize);
        curCodeUpperBound = 1 << newSize;
        if(SHOW_DEBUG_INFO) {
            Log.d("LZW", String.format("Code size: %d", curCodeSize));
        }
    }
    private void increaseCodeSizeByOne() {
        setCodeSize(curCodeSize + 1);
    }

    /**
     * Read next code from bitBuffer and removes it from bitBuffer
     * @return Next code
     */
    private int getCode() {
        int code = bitBuffer & curCodeMask;//get
        bitBuffer >>>= curCodeSize;//remove
        bitBufferLength -= curCodeSize;
        return code;
    }

    /**
     * Check if bitBuffer has more code.
     * @return True if bitBuffer contains more code
     */
    private boolean hasCode() {
        return (bitBufferLength >= curCodeSize);
    }

    private void addToBitBuffer(int b) {
        //Log.d("LZW", String.format("Writing:       %8x", b));
        //Log.d("LZW", String.format("bitBuf before: %8x", bitBuffer));
        bitBuffer = bitBuffer | ((b & 0xff) << bitBufferLength);
        //Log.d("LZW", String.format("bitBuf after:  %8x", bitBuffer));
        bitBufferLength += 8;
    }

    private int addEntry(int prefix, byte pix) {
        int code = this.nextEmptyEntry;
        //Log.d("LZW", String.format("Add code %x", code));
        this.nextEmptyEntry++;
        this.prefix[code] = (short) prefix;
        this.pix[code] = pix;
        this.headPix[code] = getHeadPix(code);
//        if(pix == 0) {
//            Log.e("LZW", String.format("Error! addEntry. pix[%d] is -1", code));
//        }
        if(this.nextEmptyEntry == this.curCodeUpperBound) {
            // bump up code size
            increaseCodeSizeByOne();
        }
        return code;
    }
    private byte getHeadPix(int code) {
        while(code > CLEAR_CODE) {
            code = prefix[code];
        }
        return pix[code];
    }


    /**
     * Respond to clear code
     * Bit buffer is retained.
     */
    private void clear() {
        setCodeSize(rootSize + 1);
        nextEmptyEntry = END_OF_INFORMATION + 1;
        prevCode = NO_CODE;
        for(int i = 0; i < CLEAR_CODE; ++i) {
            prefix[i] = NO_CODE;
            pix[i] = (byte)i;
            headPix[i] = (byte)i;
        }
    }


    private byte[] emitPixStrStack = new byte[MAX_DICTIONARY_SIZE];
    private int emitPixStrStackSize = 0;
    private void emitPixStr(int code) {
//        Stack<Byte> str = new Stack<Byte>();
//        while(code != NO_CODE){
//            str.push(pix[code]);
//            code = prefix[code];
//        }
//        while(!str.empty()) {
//            //dictEvents.OnDecodedPix(str.pop());
//            outputStream.write(((int)str.pop()) & 0xff);
//        }

        emitPixStrStackSize = MAX_DICTIONARY_SIZE;
        while(code != NO_CODE) {
            emitPixStrStack[--emitPixStrStackSize] = pix[code];
            code = prefix[code];
        }
        outputStream.write(emitPixStrStack, emitPixStrStackSize, MAX_DICTIONARY_SIZE - emitPixStrStackSize);
    }

    boolean decode(int b) {
        addToBitBuffer(b);
        while(hasCode() && (outputStream.size() < imageSize)) {// Process next code
            int code = getCode();
            if((code > this.nextEmptyEntry) || (code == END_OF_INFORMATION)) {
                Log.d("LZW", String.format("Return false with code = %x", code));
                //dictEvents.OnEndOfInformation();
                return false;
            }
            if(code == CLEAR_CODE) {// Reset dictionary
                if(SHOW_DEBUG_INFO) {
                    Log.d("LZW", "Clear code!");
                }
                clear();
                continue;
            }
            if(prevCode == NO_CODE) {// First code
                emitPixStr(code);
                prevCode = code;
                continue;
            }
            if(code == this.nextEmptyEntry) {// If code is not in dictionary, add [prevCode + first element of prevCode]
                addEntry(prevCode, headPix[prevCode]);
                emitPixStr(code);
            }
            else if(code < this.nextEmptyEntry) {
                emitPixStr(code);
                addEntry(prevCode, headPix[code]);
            }
            else {//ERROR, should never enter here unless file is corrupted.
                Log.e("LZW", "Error decoding");
                break;
            }
            prevCode = code;
        }
        return true;
    }

    void encode(int b) {
        //Not used for now
    }
}
