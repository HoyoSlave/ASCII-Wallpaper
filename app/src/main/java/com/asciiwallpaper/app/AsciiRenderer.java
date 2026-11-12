package com.asciiwallpaper.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.EGL14;
import android.opengl.EGLDisplay;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * High-performance ASCII renderer:
 * - Output resolution: 1024 x auto or auto x 1024 (where auto is the higher resolution side)
 * - ASCII cell width: 16px per character (64 characters per 1024px row), zero line spacing
 * - Layer stack (bottom to top):
 *   1. Solid Black background (#000000)
 *   2. Original media with adjustable opacity (0% - 100%, step 5%)
 *   3. Colored ASCII characters matching the underlying media color
 */
public final class AsciiRenderer {

    public static final int BASE_RESOLUTION = 1024;
    public static final int CHAR_PIXEL_SIZE = 16;
    public static final int CHARS_PER_BASE_LINE = 64; // 64 * 16 = 1024 px

    // Luminance-ordered ASCII character ramp from darkest to brightest
    private static final char[] ASCII_RAMP = " .'`^\",:;Il!i><~+_-?][}{1)(|/tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$".toCharArray();
    private static final int[] LUMINANCE_TO_CHAR_INDEX = new int[256];

    static {
        for (int lum = 0; lum < 256; lum++) {
            LUMINANCE_TO_CHAR_INDEX[lum] = (lum * (ASCII_RAMP.length - 1)) / 255;
        }
    }

    private final int cpuThreads;
    private final ExecutorService rowRenderPool;

    public AsciiRenderer() {
        this.cpuThreads = Math.max(2, Runtime.getRuntime().availableProcessors());
        this.rowRenderPool = Executors.newFixedThreadPool(this.cpuThreads);
    }

    /**
     * Computes target output dimensions:
     * 1024 x auto or auto x 1024, where auto is the higher-resolution side (>= 1024),
     * aligned to multiples of CHAR_PIXEL_SIZE (16px) so characters fit cleanly without line spacing.
     */
    public static int[] computeOutputDimensions(int srcWidth, int srcHeight) {
        if (srcWidth <= 0 || srcHeight <= 0) {
            return new int[]{BASE_RESOLUTION, BASE_RESOLUTION};
        }
        int outWidth;
        int outHeight;
        if (srcHeight >= srcWidth) {
            // Portrait or square: 1024 x auto
            outWidth = BASE_RESOLUTION;
            int rawHeight = Math.round((float) BASE_RESOLUTION * ((float) srcHeight / (float) srcWidth));
            outHeight = Math.max(BASE_RESOLUTION, ((rawHeight + (CHAR_PIXEL_SIZE / 2)) / CHAR_PIXEL_SIZE) * CHAR_PIXEL_SIZE);
        } else {
            // Landscape: auto x 1024
            outHeight = BASE_RESOLUTION;
            int rawWidth = Math.round((float) BASE_RESOLUTION * ((float) srcWidth / (float) srcHeight));
            outWidth = Math.max(BASE_RESOLUTION, ((rawWidth + (CHAR_PIXEL_SIZE / 2)) / CHAR_PIXEL_SIZE) * CHAR_PIXEL_SIZE);
        }
        return new int[]{outWidth, outHeight};
    }

    /**
     * Ensures the bitmap is in software-readable ARGB_8888 format (prevents Config.HARDWARE crash on getPixels).
     */
    public static Bitmap ensureSoftwareBitmap(Bitmap bm) {
        if (bm == null) {
            return Bitmap.createBitmap(BASE_RESOLUTION, BASE_RESOLUTION, Bitmap.Config.ARGB_8888);
        }
        if (bm.getConfig() == Bitmap.Config.ARGB_8888) {
            return bm;
        }
        Bitmap copy = bm.copy(Bitmap.Config.ARGB_8888, false);
        if (copy == null) {
            copy = Bitmap.createBitmap(Math.max(1, bm.getWidth()), Math.max(1, bm.getHeight()), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(copy);
            c.drawBitmap(bm, 0f, 0f, null);
        }
        return copy;
    }

    /**
     * Renders a single static image using multi-threaded row workers.
     */
    public Bitmap renderFrameParallelRows(Bitmap sourceBitmap, int opacityPercent) {
        return renderFrameInternal(sourceBitmap, opacityPercent, true);
    }

    /**
     * Renders a single frame directly on the calling worker thread (used when multiple frames
     * are already being rendered in parallel across CPU cores, preventing thread-pool starvation).
     */
    public Bitmap renderFrameOnCurrentThread(Bitmap sourceBitmap, int opacityPercent) {
        return renderFrameInternal(sourceBitmap, opacityPercent, false);
    }

    private Bitmap renderFrameInternal(Bitmap sourceBitmap, int opacityPercent, boolean useRowPool) {
        Bitmap safeSource = ensureSoftwareBitmap(sourceBitmap);
        int[] dims = computeOutputDimensions(safeSource.getWidth(), safeSource.getHeight());
        final int outWidth = dims[0];
        final int outHeight = dims[1];

        final Bitmap scaledMedia;
        if (safeSource.getWidth() == outWidth && safeSource.getHeight() == outHeight) {
            scaledMedia = safeSource;
        } else {
            scaledMedia = Bitmap.createScaledBitmap(safeSource, outWidth, outHeight, true);
        }

        final int[] pixels = new int[outWidth * outHeight];
        scaledMedia.getPixels(pixels, 0, outWidth, 0, 0, outWidth, outHeight);

        final int cols = outWidth / CHAR_PIXEL_SIZE;   // 64 columns when outWidth == 1024
        final int rows = outHeight / CHAR_PIXEL_SIZE;  // Zero spacing between lines

        final char[] cellChars = new char[cols * rows];
        final int[] cellColors = new int[cols * rows];

        if (useRowPool) {
            List<Callable<Void>> tasks = new ArrayList<>(cpuThreads);
            final int rowsPerThread = Math.max(1, (rows + cpuThreads - 1) / cpuThreads);

            for (int t = 0; t < cpuThreads; t++) {
                final int startRow = t * rowsPerThread;
                final int endRow = Math.min(rows, startRow + rowsPerThread);
                if (startRow >= endRow) break;

                tasks.add(() -> {
                    sampleRowRange(pixels, outWidth, cols, startRow, endRow, cellChars, cellColors);
                    return null;
                });
            }

            try {
                List<Future<Void>> futures = rowRenderPool.invokeAll(tasks);
                for (Future<Void> f : futures) {
                    f.get();
                }
            } catch (Exception e) {
                sampleRowRange(pixels, outWidth, cols, 0, rows, cellChars, cellColors);
            }
        } else {
            sampleRowRange(pixels, outWidth, cols, 0, rows, cellChars, cellColors);
        }

        // Composite layers onto output Bitmap
        Bitmap output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        // Layer 1 (Bottom): Pure Black background (#000000)
        canvas.drawColor(Color.BLACK);

        // Layer 2 (Middle): Original media with configured opacity (0% - 100%, step 5%)
        int clampedOpacity = Math.max(0, Math.min(100, opacityPercent));
        if (clampedOpacity > 0) {
            Paint mediaPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
            mediaPaint.setAlpha(Math.round((clampedOpacity * 255f) / 100f));
            canvas.drawBitmap(scaledMedia, 0f, 0f, mediaPaint);
        }

        if (scaledMedia != sourceBitmap && scaledMedia != safeSource) {
            scaledMedia.recycle();
        }
        if (safeSource != sourceBitmap) {
            safeSource.recycle();
        }

        // Layer 3 (Top): Colored ASCII text with zero line spacing (16px cell width & 16px line height)
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setTextSize(CHAR_PIXEL_SIZE);
        textPaint.setTextAlign(Paint.Align.LEFT);

        float measuredAdvance = textPaint.measureText("M");
        if (measuredAdvance > 0f) {
            textPaint.setTextScaleX((float) CHAR_PIXEL_SIZE / measuredAdvance);
        }

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        final float baselineOffset = -fm.ascent - ((fm.descent - fm.ascent - CHAR_PIXEL_SIZE) / 2f);

        char[] singleCharBuf = new char[1];
        for (int r = 0; r < rows; r++) {
            final float yBaseline = r * CHAR_PIXEL_SIZE + baselineOffset;
            final int rowOffset = r * cols;
            for (int c = 0; c < cols; c++) {
                char ch = cellChars[rowOffset + c];
                if (ch == ' ') continue;
                singleCharBuf[0] = ch;
                textPaint.setColor(cellColors[rowOffset + c]);
                canvas.drawText(singleCharBuf, 0, 1, c * CHAR_PIXEL_SIZE, yBaseline, textPaint);
            }
        }

        return output;
    }

    private static void sampleRowRange(
            int[] pixels,
            int outWidth,
            int cols,
            int startRow,
            int endRow,
            char[] cellChars,
            int[] cellColors
    ) {
        for (int r = startRow; r < endRow; r++) {
            final int yStart = r * CHAR_PIXEL_SIZE;
            final int rowCellOffset = r * cols;

            for (int c = 0; c < cols; c++) {
                final int xStart = c * CHAR_PIXEL_SIZE;
                int sumR = 0;
                int sumG = 0;
                int sumB = 0;

                for (int dy = 0; dy < CHAR_PIXEL_SIZE; dy++) {
                    final int pixelRowOffset = (yStart + dy) * outWidth + xStart;
                    for (int dx = 0; dx < CHAR_PIXEL_SIZE; dx++) {
                        final int argb = pixels[pixelRowOffset + dx];
                        sumR += (argb >> 16) & 0xFF;
                        sumG += (argb >> 8) & 0xFF;
                        sumB += argb & 0xFF;
                    }
                }

                final int avgR = sumR >> 8;
                final int avgG = sumG >> 8;
                final int avgB = sumB >> 8;

                final int lum = (77 * avgR + 150 * avgG + 29 * avgB) >> 8;
                final int clampedLum = Math.max(0, Math.min(255, lum));

                cellChars[rowCellOffset + c] = ASCII_RAMP[LUMINANCE_TO_CHAR_INDEX[clampedLum]];
                cellColors[rowCellOffset + c] = Color.rgb(avgR, avgG, avgB);
            }
        }
    }

    public void warmUpGpu() {
        try {
            EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display != EGL14.EGL_NO_DISPLAY) {
                int[] version = new int[2];
                EGL14.eglInitialize(display, version, 0, version, 1);
            }
        } catch (Throwable ignored) {
        }
    }

    public void shutdown() {
        rowRenderPool.shutdownNow();
    }
}
