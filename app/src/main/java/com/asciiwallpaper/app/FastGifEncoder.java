package com.asciiwallpaper.app;

import android.graphics.Bitmap;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * High-speed GIF89a animated encoder using fixed 6-7-6 RGB palette (256 colors)
 * with precomputed O(1) color lookup and standard LZW compression streamed directly to disk.
 * Configured for 16 FPS rendering (alternating 6 cs / 7 cs = 62.5 ms average per frame).
 */
public final class FastGifEncoder implements Closeable {

    private static final int PALETTE_SIZE = 256;
    private static final int R_LEVELS = 6;
    private static final int G_LEVELS = 7;
    private static final int B_LEVELS = 6;

    private static final byte[] GLOBAL_PALETTE = new byte[PALETTE_SIZE * 3];

    static {
        int idx = 0;
        for (int r = 0; r < R_LEVELS; r++) {
            int rv = (r * 255) / (R_LEVELS - 1);
            for (int g = 0; g < G_LEVELS; g++) {
                int gv = (g * 255) / (G_LEVELS - 1);
                for (int b = 0; b < B_LEVELS; b++) {
                    int bv = (b * 255) / (B_LEVELS - 1);
                    GLOBAL_PALETTE[idx * 3] = (byte) rv;
                    GLOBAL_PALETTE[idx * 3 + 1] = (byte) gv;
                    GLOBAL_PALETTE[idx * 3 + 2] = (byte) bv;
                    idx++;
                }
            }
        }
        while (idx < PALETTE_SIZE) {
            GLOBAL_PALETTE[idx * 3] = 0;
            GLOBAL_PALETTE[idx * 3 + 1] = 0;
            GLOBAL_PALETTE[idx * 3 + 2] = 0;
            idx++;
        }
    }

    private final OutputStream out;
    private final int width;
    private final int height;
    private final int fps;
    private int frameIndex = 0;
    private boolean finished = false;

    public FastGifEncoder(File outputFile, int width, int height, int fps) throws IOException {
        this(new BufferedOutputStream(new FileOutputStream(outputFile), 256 * 1024), width, height, fps);
    }

    public FastGifEncoder(OutputStream outputStream, int width, int height, int fps) throws IOException {
        this.out = outputStream;
        this.width = width;
        this.height = height;
        this.fps = Math.max(1, fps);
        writeHeader();
        writeLogicalScreenDescriptor();
        writeGlobalPalette();
        writeNetscapeLoopExtension();
    }

    public static byte[] quantizeBitmapToIndices(Bitmap bitmap) {
        final int w = bitmap.getWidth();
        final int h = bitmap.getHeight();
        final int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);

        final byte[] indexed = new byte[w * h];
        for (int i = 0; i < pixels.length; i++) {
            final int c = pixels[i];
            final int r = ((c >> 16) & 0xFF);
            final int g = ((c >> 8) & 0xFF);
            final int b = (c & 0xFF);

            final int ri = (r * (R_LEVELS - 1) + 127) / 255;
            final int gi = (g * (G_LEVELS - 1) + 127) / 255;
            final int bi = (b * (B_LEVELS - 1) + 127) / 255;

            final int paletteIndex = (ri * G_LEVELS + gi) * B_LEVELS + bi;
            indexed[i] = (byte) paletteIndex;
        }
        return indexed;
    }

    public synchronized void addFrame(Bitmap bitmap) throws IOException {
        byte[] indexedPixels = quantizeBitmapToIndices(bitmap);
        addQuantizedFrame(indexedPixels);
    }

    public synchronized void addQuantizedFrame(byte[] indexedPixels) throws IOException {
        if (finished) {
            throw new IllegalStateException("GIF encoder already finished");
        }
        int delayCentiseconds = computeDelayCentiseconds(frameIndex, fps);
        frameIndex++;

        writeGraphicControlExtension(delayCentiseconds);
        writeImageDescriptor();
        writeLzwCompressedPixels(indexedPixels);
    }

    private static int computeDelayCentiseconds(int index, int fps) {
        if (fps == 16) {
            return (index % 2 == 0) ? 6 : 7;
        }
        return Math.max(2, Math.round(100f / fps));
    }

    public synchronized void finish() throws IOException {
        if (!finished) {
            finished = true;
            out.write(0x3B);
            out.flush();
            out.close();
        }
    }

    @Override
    public void close() throws IOException {
        finish();
    }

    private void writeHeader() throws IOException {
        out.write("GIF89a".getBytes("US-ASCII"));
    }

    private void writeLogicalScreenDescriptor() throws IOException {
        writeShort(width);
        writeShort(height);
        out.write(0xF7);
        out.write(0);
        out.write(0);
    }

    private void writeGlobalPalette() throws IOException {
        out.write(GLOBAL_PALETTE);
    }

    private void writeNetscapeLoopExtension() throws IOException {
        out.write(0x21);
        out.write(0xFF);
        out.write(11);
        out.write("NETSCAPE2.0".getBytes("US-ASCII"));
        out.write(3);
        out.write(1);
        writeShort(0);
        out.write(0);
    }

    private void writeGraphicControlExtension(int delayCentiseconds) throws IOException {
        out.write(0x21);
        out.write(0xF9);
        out.write(4);
        out.write(0x04);
        writeShort(delayCentiseconds);
        out.write(0);
        out.write(0);
    }

    private void writeImageDescriptor() throws IOException {
        out.write(0x2C);
        writeShort(0);
        writeShort(0);
        writeShort(width);
        writeShort(height);
        out.write(0);
    }

    private void writeLzwCompressedPixels(byte[] pixels) throws IOException {
        LzwCompressor compressor = new LzwCompressor(8, pixels);
        compressor.encode(out);
    }

    private void writeShort(int val) throws IOException {
        out.write(val & 0xFF);
        out.write((val >> 8) & 0xFF);
    }

    private static final class LzwCompressor {
        private static final int BITS = 12;
        private static final int HSIZE = 5003;

        private final int initCodeSize;
        private final byte[] pixAry;

        private int nBits;
        private int maxCode;
        private final int maxMaxCode = 1 << BITS;
        private final int[] htab = new int[HSIZE];
        private final int[] codetab = new int[HSIZE];

        private int freeEnt = 0;
        private boolean clearFlg = false;
        private int gInitBits;
        private int clearCode;
        private int eofCode;

        private int curAccum = 0;
        private int curBits = 0;
        private int aCount = 0;
        private final byte[] accum = new byte[256];

        private static final int[] MASKS = {
            0x0000, 0x0001, 0x0003, 0x0007, 0x000F, 0x001F, 0x003F, 0x007F,
            0x00FF, 0x01FF, 0x03FF, 0x07FF, 0x0FFF, 0x1FFF, 0x3FFF, 0x7FFF, 0xFFFF
        };

        LzwCompressor(int initCodeSize, byte[] pixels) {
            this.initCodeSize = Math.max(2, initCodeSize);
            this.pixAry = pixels;
        }

        void encode(OutputStream os) throws IOException {
            os.write(initCodeSize);
            compress(initCodeSize + 1, os);
            os.write(0);
        }

        private void compress(int initBits, OutputStream outs) throws IOException {
            int fcode;
            int i;
            int c;
            int ent;
            int disp;
            int hsizeReg = HSIZE;
            int hshift = 0;

            gInitBits = initBits;
            clearFlg = false;
            nBits = gInitBits;
            maxCode = (1 << nBits) - 1;

            clearCode = 1 << (initBits - 1);
            eofCode = clearCode + 1;
            freeEnt = clearCode + 2;

            aCount = 0;

            for (fcode = HSIZE; fcode < 65536; fcode *= 2) {
                ++hshift;
            }
            hshift = 8 - hshift;

            clHash(hsizeReg);
            output(clearCode, outs);

            int curPixel = 0;
            final int totalPixels = pixAry.length;
            if (totalPixels == 0) {
                output(eofCode, outs);
                return;
            }

            ent = pixAry[curPixel++] & 0xFF;

            outerLoop:
            while (curPixel < totalPixels) {
                c = pixAry[curPixel++] & 0xFF;
                fcode = (c << BITS) + ent;
                i = (c << hshift) ^ ent;

                if (htab[i] == fcode) {
                    ent = codetab[i];
                    continue;
                } else if (htab[i] >= 0) {
                    disp = hsizeReg - i;
                    if (i == 0) disp = 1;
                    do {
                        if ((i -= disp) < 0) i += hsizeReg;
                        if (htab[i] == fcode) {
                            ent = codetab[i];
                            continue outerLoop;
                        }
                    } while (htab[i] >= 0);
                }
                output(ent, outs);
                ent = c;
                if (freeEnt < maxMaxCode) {
                    codetab[i] = freeEnt++;
                    htab[i] = fcode;
                } else {
                    clBlock(outs);
                }
            }
            output(ent, outs);
            output(eofCode, outs);
        }

        private void clBlock(OutputStream outs) throws IOException {
            clHash(HSIZE);
            freeEnt = clearCode + 2;
            clearFlg = true;
            output(clearCode, outs);
        }

        private void clHash(int hsize) {
            for (int i = 0; i < hsize; ++i) {
                htab[i] = -1;
            }
        }

        private void output(int code, OutputStream outs) throws IOException {
            curAccum &= MASKS[curBits];
            if (curBits > 0) {
                curAccum |= (code << curBits);
            } else {
                curAccum = code;
            }
            curBits += nBits;

            while (curBits >= 8) {
                charOut((byte) (curAccum & 0xFF), outs);
                curAccum >>= 8;
                curBits -= 8;
            }

            if (freeEnt > maxCode || clearFlg) {
                if (clearFlg) {
                    nBits = gInitBits;
                    maxCode = (1 << nBits) - 1;
                    clearFlg = false;
                } else {
                    ++nBits;
                    if (nBits == BITS) {
                        maxCode = maxMaxCode;
                    } else {
                        maxCode = (1 << nBits) - 1;
                    }
                }
            }

            if (code == eofCode) {
                while (curBits > 0) {
                    charOut((byte) (curAccum & 0xFF), outs);
                    curAccum >>= 8;
                    curBits -= 8;
                }
                flushChar(outs);
            }
        }

        private void charOut(byte c, OutputStream outs) throws IOException {
            accum[aCount++] = c;
            if (aCount >= 254) {
                flushChar(outs);
            }
        }

        private void flushChar(OutputStream outs) throws IOException {
            if (aCount > 0) {
                outs.write(aCount);
                outs.write(accum, 0, aCount);
                aCount = 0;
            }
        }
    }
}
