package com.asciiwallpaper.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

/**
 * Stable, memory-safe ASCII renderer built from scratch:
 * - Discrete character densities: 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256
 * - Renders using the device's built-in system monospace font (Typeface.MONOSPACE)
 * - Supports colored or monochrome (white on black) ASCII text
 * - Generates both rendered Bitmap frames (for preview & gallery save) and plain ASCII text
 *   (for 1.txt, 2.txt, ... video ZIP export)
 */
public final class AsciiRenderer {

    public static final int[] DENSITY_VALUES = new int[]{
            2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256
    };
    public static final int DEFAULT_DENSITY_INDEX = 10; // 64
    public static final int BASE_IMAGE_SIZE = 1024;

    private static final char[] ASCII_RAMP =
            " .'`^\",:;Il!i><~+_-?][}{1)(|/tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$".toCharArray();
    private static final int[] LUM_TO_CHAR_IDX = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            LUM_TO_CHAR_IDX[i] = (i * (ASCII_RAMP.length - 1) + 127) / 255;
        }
    }

    public static final class FrameRenderResult {
        public final Bitmap bitmap;
        public final String asciiText;
        public final int cols;
        public final int rows;

        public FrameRenderResult(Bitmap bitmap, String asciiText, int cols, int rows) {
            this.bitmap = bitmap;
            this.asciiText = asciiText;
            this.cols = cols;
            this.rows = rows;
        }
    }

    private final float charAspectRatio;
    private final float unitAdvanceAt100;
    private final float unitLineHeightAt100;
    private final float unitAscentAt100;

    public AsciiRenderer() {
        Paint measurePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        measurePaint.setTypeface(Typeface.MONOSPACE);
        measurePaint.setTextSize(100f);
        float adv = measurePaint.measureText("M");
        Paint.FontMetrics fm = measurePaint.getFontMetrics();
        float lh = fm.descent - fm.ascent;

        this.unitAdvanceAt100 = (adv > 1f) ? adv : 60f;
        this.unitLineHeightAt100 = (lh > 1f) ? lh : 110f;
        this.unitAscentAt100 = -fm.ascent;
        this.charAspectRatio = this.unitAdvanceAt100 / this.unitLineHeightAt100;
    }

    /**
     * Ensures a Bitmap is in software-readable ARGB_8888 config.
     */
    public static Bitmap ensureSoftwareBitmap(Bitmap bm) {
        if (bm == null) {
            return Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        }
        if (bm.getConfig() == Bitmap.Config.ARGB_8888) {
            return bm;
        }
        Bitmap copy = bm.copy(Bitmap.Config.ARGB_8888, false);
        if (copy == null) {
            copy = Bitmap.createBitmap(
                    Math.max(1, bm.getWidth()),
                    Math.max(1, bm.getHeight()),
                    Bitmap.Config.ARGB_8888
            );
            Canvas c = new Canvas(copy);
            c.drawBitmap(bm, 0f, 0f, null);
        }
        return copy;
    }

    /**
     * Renders a source Bitmap into both an ASCII Bitmap (using built-in Typeface.MONOSPACE)
     * and an ASCII plain-text string for the given density (2..256) and color setting.
     */
    public FrameRenderResult renderFrame(Bitmap source, int densityCols, boolean colored) {
        Bitmap safeSource = ensureSoftwareBitmap(source);
        int srcW = Math.max(1, safeSource.getWidth());
        int srcH = Math.max(1, safeSource.getHeight());

        final int cols = Math.max(2, Math.min(256, densityCols));
        final int rows = Math.max(1, Math.round(cols * ((float) srcH / (float) srcW) * charAspectRatio));

        // Sample source bitmap down to (cols x rows) to get average cell RGB colors cleanly
        Bitmap sampled = (srcW == cols && srcH == rows)
                ? safeSource
                : Bitmap.createScaledBitmap(safeSource, cols, rows, true);

        final int totalCells = cols * rows;
        final int[] pixels = new int[totalCells];
        sampled.getPixels(pixels, 0, cols, 0, 0, cols, rows);

        if (sampled != safeSource && sampled != source) {
            sampled.recycle();
        }
        if (safeSource != source) {
            safeSource.recycle();
        }

        final char[] cellChars = new char[totalCells];
        final int[] cellColors = new int[totalCells];
        final char[] textBuf = new char[rows * cols + Math.max(0, rows - 1)];
        int textPos = 0;

        for (int r = 0; r < rows; r++) {
            int rowOffset = r * cols;
            for (int c = 0; c < cols; c++) {
                int argb = pixels[rowOffset + c];
                int alpha = (argb >>> 24) & 0xFF;
                int red = ((argb >> 16) & 0xFF) * alpha / 255;
                int green = ((argb >> 8) & 0xFF) * alpha / 255;
                int blue = (argb & 0xFF) * alpha / 255;

                int lum = (77 * red + 150 * green + 29 * blue) >> 8;
                if (lum < 0) lum = 0;
                else if (lum > 255) lum = 255;

                char ch = ASCII_RAMP[LUM_TO_CHAR_IDX[lum]];
                cellChars[rowOffset + c] = ch;
                cellColors[rowOffset + c] = colored ? Color.rgb(red, green, blue) : Color.WHITE;
                textBuf[textPos++] = ch;
            }
            if (r < rows - 1) {
                textBuf[textPos++] = '\n';
            }
        }

        final String asciiText = new String(textBuf);

        // Compute output Bitmap dimensions preserving source aspect ratio
        final int outWidth;
        if (srcH >= srcW) {
            outWidth = BASE_IMAGE_SIZE;
        } else {
            outWidth = Math.min(2048, Math.max(BASE_IMAGE_SIZE, Math.round((float) BASE_IMAGE_SIZE * srcW / srcH)));
        }

        final float cellW = (float) outWidth / (float) cols;
        final float textSize = (cellW * 100f) / unitAdvanceAt100;
        final float cellH = (textSize * unitLineHeightAt100) / 100f;
        final float baselineOffset = (textSize * unitAscentAt100) / 100f;
        final int outHeight = Math.max(1, Math.round(rows * cellH));

        Bitmap outBitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(outBitmap);
        canvas.drawColor(Color.BLACK);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setTextSize(textSize);
        textPaint.setTextAlign(Paint.Align.LEFT);

        if (!colored) {
            // Fast monochrome drawing path: draw entire row or character by character in solid WHITE
            textPaint.setColor(Color.WHITE);
            for (int r = 0; r < rows; r++) {
                float y = r * cellH + baselineOffset;
                int rowOffset = r * cols;
                canvas.drawText(cellChars, rowOffset, cols, 0f, y, textPaint);
            }
        } else {
            // Colored drawing path using built-in monospace font
            char[] singleChar = new char[1];
            for (int r = 0; r < rows; r++) {
                float y = r * cellH + baselineOffset;
                int rowOffset = r * cols;
                for (int c = 0; c < cols; c++) {
                    char ch = cellChars[rowOffset + c];
                    if (ch == ' ') continue;
                    singleChar[0] = ch;
                    textPaint.setColor(cellColors[rowOffset + c]);
                    canvas.drawText(singleChar, 0, 1, c * cellW, y, textPaint);
                }
            }
        }

        return new FrameRenderResult(outBitmap, asciiText, cols, rows);
    }
}
