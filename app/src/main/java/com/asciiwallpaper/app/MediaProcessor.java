package com.asciiwallpaper.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Decodes static images, animated GIFs, and videos, utilizing a dedicated frame worker pool
 * (separate from AsciiRenderer's row pool to prevent any thread-pool deadlock),
 * hardware-safe ARGB_8888 bitmaps, and real-time (completed/total) frame progress callbacks.
 */
@SuppressWarnings("deprecation")
public final class MediaProcessor {

    public static final int TARGET_FPS = 16;
    public static final int MAX_DURATION_SECONDS = 64;

    public enum MediaType {
        STATIC_IMAGE,
        ANIMATED_GIF,
        VIDEO
    }

    public interface RenderListener {
        void onProgress(int completedFrames, int totalFrames, String message, Bitmap latestPreviewFrame);
    }

    public static final class MediaSourceInfo {
        public final Uri uri;
        public final MediaType mediaType;
        public final File cachedSourceFile;
        public final byte[] gifBytes;
        public final long durationMs;
        public final int width;
        public final int height;

        public MediaSourceInfo(
                Uri uri,
                MediaType mediaType,
                File cachedSourceFile,
                byte[] gifBytes,
                long durationMs,
                int width,
                int height
        ) {
            this.uri = uri;
            this.mediaType = mediaType;
            this.cachedSourceFile = cachedSourceFile;
            this.gifBytes = gifBytes;
            this.durationMs = durationMs;
            this.width = width;
            this.height = height;
        }

        public boolean isAnimated() {
            return mediaType == MediaType.ANIMATED_GIF || mediaType == MediaType.VIDEO;
        }
    }

    public static final class RenderedOutput {
        public final File outputFile;
        public final String mimeType;
        public final boolean isAnimated;
        public final Bitmap staticBitmap;
        public final int width;
        public final int height;
        public final int totalFrames;

        public RenderedOutput(
                File outputFile,
                String mimeType,
                boolean isAnimated,
                Bitmap staticBitmap,
                int width,
                int height,
                int totalFrames
        ) {
            this.outputFile = outputFile;
            this.mimeType = mimeType;
            this.isAnimated = isAnimated;
            this.staticBitmap = staticBitmap;
            this.width = width;
            this.height = height;
            this.totalFrames = totalFrames;
        }
    }

    private final Context context;
    private final AsciiRenderer renderer;
    private final int workerCount;
    private final ExecutorService frameWorkerPool;

    public MediaProcessor(Context context, AsciiRenderer renderer) {
        this.context = context.getApplicationContext();
        this.renderer = renderer;
        this.workerCount = Math.max(2, Runtime.getRuntime().availableProcessors());
        this.frameWorkerPool = Executors.newFixedThreadPool(this.workerCount);
    }

    /**
     * Inspects the URI and caches the media to disk for fast random frame access.
     */
    public MediaSourceInfo loadMediaSource(Uri uri) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String mime = resolver.getType(uri);
        if (mime == null) mime = "";

        File cacheDir = new File(context.getCacheDir(), "ascii_media_cache");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        File cachedFile = new File(cacheDir, "active_source_media.bin");
        copyUriToFile(resolver, uri, cachedFile);

        String pathLower = uri.toString().toLowerCase();
        boolean looksLikeGif = mime.equalsIgnoreCase("image/gif") || pathLower.endsWith(".gif") || isGifFile(cachedFile);
        boolean looksLikeVideo = mime.startsWith("video/") || pathLower.endsWith(".mp4") || pathLower.endsWith(".webm") || pathLower.endsWith(".mkv") || pathLower.endsWith(".mov");

        if (looksLikeVideo && !looksLikeGif) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(cachedFile.getAbsolutePath());
                String durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                String wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                String rotStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);

                long durationMs = parseLongSafe(durStr, 1000L);
                int w = parseIntSafe(wStr, 1024);
                int h = parseIntSafe(hStr, 1024);
                int rot = parseIntSafe(rotStr, 0);
                if (rot == 90 || rot == 270) {
                    int tmp = w;
                    w = h;
                    h = tmp;
                }
                long cappedDuration = Math.min(Math.max(100L, durationMs), MAX_DURATION_SECONDS * 1000L);
                return new MediaSourceInfo(uri, MediaType.VIDEO, cachedFile, null, cappedDuration, w, h);
            } finally {
                releaseSilently(retriever);
            }
        }

        if (looksLikeGif) {
            byte[] gifBytes = readFileToBytes(cachedFile);
            int[] gifInfo = inspectGifHeaderAndDuration(gifBytes);
            int w = gifInfo[0];
            int h = gifInfo[1];
            int totalDelayMs = gifInfo[2];
            int frameCount = gifInfo[3];

            Movie movie = Movie.decodeByteArray(gifBytes, 0, gifBytes.length);
            if (movie != null) {
                if (w <= 0) w = movie.width();
                if (h <= 0) h = movie.height();
                if (totalDelayMs <= 0 && movie.duration() > 0) {
                    totalDelayMs = movie.duration();
                }
            }

            if (frameCount > 1 || totalDelayMs > 0) {
                if (totalDelayMs <= 0) {
                    totalDelayMs = Math.max(500, frameCount * 62);
                }
                long cappedDuration = Math.min((long) totalDelayMs, MAX_DURATION_SECONDS * 1000L);
                return new MediaSourceInfo(
                        uri,
                        MediaType.ANIMATED_GIF,
                        cachedFile,
                        gifBytes,
                        cappedDuration,
                        Math.max(1, w),
                        Math.max(1, h)
                );
            }
        }

        // Fallback: Static Image
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(cachedFile.getAbsolutePath(), opts);
        int w = opts.outWidth > 0 ? opts.outWidth : 1024;
        int h = opts.outHeight > 0 ? opts.outHeight : 1024;
        return new MediaSourceInfo(uri, MediaType.STATIC_IMAGE, cachedFile, null, 0L, w, h);
    }

    /**
     * Renders the media at 16 FPS (up to 64s max = 1,024 frames for video/GIF, or 1 frame for static image)
     * and reports exact (completed/total) frame progress after every completed frame.
     */
    public RenderedOutput renderFullOutput(
            final MediaSourceInfo source,
            final int opacityPercent,
            final AtomicBoolean cancelFlag,
            final RenderListener listener
    ) throws Exception {
        renderer.warmUpGpu();
        final int[] outDims = AsciiRenderer.computeOutputDimensions(source.width, source.height);

        if (!source.isAnimated()) {
            if (listener != null) {
                listener.onProgress(0, 1, "Loading (0/1)", null);
            }
            Bitmap raw = decodeStaticBitmap(source.cachedSourceFile);
            Bitmap ascii = renderer.renderFrameParallelRows(raw, opacityPercent);
            if (raw != ascii) raw.recycle();

            File outputPng = new File(context.getCacheDir(), "ascii_rendered_output.png");
            try (FileOutputStream fos = new FileOutputStream(outputPng)) {
                ascii.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            if (listener != null) {
                listener.onProgress(1, 1, "Ready (1/1)", ascii);
            }
            return new RenderedOutput(outputPng, "image/png", false, ascii, outDims[0], outDims[1], 1);
        }

        // Animated GIF / Video: Render directly at 16 FPS (max 64 seconds = up to 1,024 frames)
        final long targetDurationMs = Math.min(source.durationMs, MAX_DURATION_SECONDS * 1000L);
        final int totalFrames = Math.max(1, Math.min(MAX_DURATION_SECONDS * TARGET_FPS,
                (int) Math.ceil((targetDurationMs / 1000.0) * TARGET_FPS)));

        // Immediately notify (0/totalFrames) so UI never stays stuck on "Loading media..."
        if (listener != null) {
            listener.onProgress(0, totalFrames, "Loading (0/" + totalFrames + ")", null);
        }

        File tempGifFile = new File(context.getCacheDir(), "ascii_rendering_in_progress.gif");
        File finalGifFile = new File(context.getCacheDir(), "ascii_rendered_16fps.gif");
        final int batchSize = workerCount;
        final AtomicInteger completedCounter = new AtomicInteger(0);

        final class FrameData {
            final byte[] quantizedIndices;
            final Bitmap previewSample;

            FrameData(byte[] quantizedIndices, Bitmap previewSample) {
                this.quantizedIndices = quantizedIndices;
                this.previewSample = previewSample;
            }
        }

        try (FastGifEncoder gifEncoder = new FastGifEncoder(tempGifFile, outDims[0], outDims[1], TARGET_FPS)) {
            for (int batchStart = 0; batchStart < totalFrames; batchStart += batchSize) {
                if (cancelFlag != null && cancelFlag.get()) {
                    throw new InterruptedException("Render cancelled");
                }
                final int batchEnd = Math.min(totalFrames, batchStart + batchSize);
                final int count = batchEnd - batchStart;

                List<Callable<FrameData>> batchTasks = new ArrayList<>(count);
                for (int frameIdx = batchStart; frameIdx < batchEnd; frameIdx++) {
                    final int currentFrame = frameIdx;
                    final boolean keepSampleForLivePreview = (currentFrame == batchEnd - 1) || (currentFrame == 0);
                    final long timestampMs = Math.round((currentFrame * 1000.0) / TARGET_FPS);

                    batchTasks.add(() -> {
                        if (cancelFlag != null && cancelFlag.get()) {
                            return null;
                        }
                        Bitmap rawFrame = extractRawFrameAt(source, timestampMs, outDims[0], outDims[1]);
                        if (rawFrame == null) {
                            rawFrame = Bitmap.createBitmap(outDims[0], outDims[1], Bitmap.Config.ARGB_8888);
                        }
                        // Use renderFrameOnCurrentThread to avoid nested thread-pool deadlock!
                        Bitmap asciiFrame = renderer.renderFrameOnCurrentThread(rawFrame, opacityPercent);
                        if (rawFrame != asciiFrame) {
                            rawFrame.recycle();
                        }
                        byte[] quantized = FastGifEncoder.quantizeBitmapToIndices(asciiFrame);

                        int doneNow = completedCounter.incrementAndGet();
                        if (listener != null && (cancelFlag == null || !cancelFlag.get())) {
                            Bitmap previewBmp = keepSampleForLivePreview ? asciiFrame : null;
                            listener.onProgress(
                                    doneNow,
                                    totalFrames,
                                    "Loading (" + doneNow + "/" + totalFrames + ")",
                                    previewBmp
                            );
                        }

                        if (!keepSampleForLivePreview) {
                            asciiFrame.recycle();
                            return new FrameData(quantized, null);
                        }
                        return new FrameData(quantized, asciiFrame);
                    });
                }

                List<Future<FrameData>> results = frameWorkerPool.invokeAll(batchTasks);
                for (int i = 0; i < results.size(); i++) {
                    if (cancelFlag != null && cancelFlag.get()) {
                        throw new InterruptedException("Render cancelled");
                    }
                    FrameData data = results.get(i).get();
                    if (data != null && data.quantizedIndices != null) {
                        gifEncoder.addQuantizedFrame(data.quantizedIndices);
                    }
                }
            }
            gifEncoder.finish();
        }

        if (finalGifFile.exists()) {
            finalGifFile.delete();
        }
        tempGifFile.renameTo(finalGifFile);

        if (listener != null) {
            listener.onProgress(
                    totalFrames,
                    totalFrames,
                    "Ready (" + totalFrames + "/" + totalFrames + ") - 16 FPS",
                    null
            );
        }
        return new RenderedOutput(finalGifFile, "image/gif", true, null, outDims[0], outDims[1], totalFrames);
    }

    /**
     * Copies the already-rendered preview file directly into the Android Gallery without re-rendering.
     */
    public Uri saveRenderedOutputToGallery(RenderedOutput renderedOutput) throws IOException {
        if (renderedOutput == null || renderedOutput.outputFile == null || !renderedOutput.outputFile.exists()) {
            throw new IOException("No rendered output available to save.");
        }
        String ext = renderedOutput.isAnimated ? ".gif" : ".png";
        String prefix = renderedOutput.isAnimated ? "ASCII_Wallpaper_16FPS_" : "ASCII_Wallpaper_";
        String displayName = prefix + System.currentTimeMillis() + ext;
        return saveFileToGallery(renderedOutput.outputFile, displayName, renderedOutput.mimeType);
    }

    private Bitmap extractRawFrameAt(MediaSourceInfo source, long timestampMs, int targetW, int targetH) {
        if (source.mediaType == MediaType.VIDEO) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(source.cachedSourceFile.getAbsolutePath());
                Bitmap frame = null;
                long timeUs = timestampMs * 1000L;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    frame = retriever.getScaledFrameAtTime(
                            timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST,
                            targetW,
                            targetH
                    );
                    if (frame == null) {
                        frame = retriever.getScaledFrameAtTime(
                                timeUs,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                targetW,
                                targetH
                        );
                    }
                }
                if (frame == null) {
                    frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
                }
                if (frame == null) {
                    frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                }
                if (frame == null) {
                    frame = retriever.getFrameAtTime();
                }
                if (frame != null) {
                    Bitmap sw = AsciiRenderer.ensureSoftwareBitmap(frame);
                    if (sw != frame) {
                        frame.recycle();
                    }
                    return sw;
                }
                return null;
            } catch (Exception e) {
                return null;
            } finally {
                releaseSilently(retriever);
            }
        } else if (source.mediaType == MediaType.ANIMATED_GIF) {
            byte[] bytes = source.gifBytes;
            if (bytes == null) {
                try {
                    bytes = readFileToBytes(source.cachedSourceFile);
                } catch (IOException e) {
                    return null;
                }
            }
            Movie movie = Movie.decodeByteArray(bytes, 0, bytes.length);
            if (movie == null) {
                return decodeStaticBitmap(source.cachedSourceFile);
            }
            int dur = movie.duration();
            int relTime = dur > 0 ? (int) (timestampMs % dur) : (int) timestampMs;
            movie.setTime(relTime);
            Bitmap bm = Bitmap.createBitmap(
                    Math.max(1, movie.width()),
                    Math.max(1, movie.height()),
                    Bitmap.Config.ARGB_8888
            );
            Canvas c = new Canvas(bm);
            c.drawColor(Color.BLACK);
            movie.draw(c, 0f, 0f);
            return bm;
        } else {
            return decodeStaticBitmap(source.cachedSourceFile);
        }
    }

    private Bitmap decodeStaticBitmap(File file) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bm = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (bm == null) {
            return Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888);
        }
        Bitmap sw = AsciiRenderer.ensureSoftwareBitmap(bm);
        if (sw != bm) {
            bm.recycle();
        }
        return sw;
    }

    /**
     * Parses GIF binary header & Graphic Control Extensions to determine exact dimensions,
     * total duration in ms, and total frame count even when Android's Movie.duration() returns 0.
     */
    private static int[] inspectGifHeaderAndDuration(byte[] data) {
        if (data == null || data.length < 13) {
            return new int[]{1024, 1024, 0, 1};
        }
        int width = (data[6] & 0xFF) | ((data[7] & 0xFF) << 8);
        int height = (data[8] & 0xFF) | ((data[9] & 0xFF) << 8);
        int packed = data[10] & 0xFF;
        int pos = 13;
        if ((packed & 0x80) != 0) {
            int gctSize = 3 * (1 << ((packed & 0x07) + 1));
            pos += gctSize;
        }

        int totalDelayMs = 0;
        int frameCount = 0;
        int lastGraphicControlDelayMs = 62;

        while (pos < data.length) {
            int blockId = data[pos++] & 0xFF;
            if (blockId == 0x3B) {
                break; // Trailer
            } else if (blockId == 0x21) { // Extension
                if (pos >= data.length) break;
                int extLabel = data[pos++] & 0xFF;
                if (extLabel == 0xF9 && pos + 5 < data.length) { // Graphic Control Extension
                    int blockSize = data[pos++] & 0xFF;
                    if (blockSize == 4) {
                        int delayCs = (data[pos + 1] & 0xFF) | ((data[pos + 2] & 0xFF) << 8);
                        lastGraphicControlDelayMs = Math.max(20, delayCs * 10);
                    }
                    pos += blockSize;
                    while (pos < data.length && (data[pos] & 0xFF) != 0) {
                        pos += 1 + (data[pos] & 0xFF);
                    }
                    pos++; // Block terminator
                } else {
                    while (pos < data.length) {
                        int subSize = data[pos++] & 0xFF;
                        if (subSize == 0) break;
                        pos += subSize;
                    }
                }
            } else if (blockId == 0x2C) { // Image Descriptor
                if (pos + 9 > data.length) break;
                int imgPacked = data[pos + 8] & 0xFF;
                pos += 9;
                if ((imgPacked & 0x80) != 0) {
                    int lctSize = 3 * (1 << ((imgPacked & 0x07) + 1));
                    pos += lctSize;
                }
                if (pos >= data.length) break;
                pos++; // LZW minimum code size
                while (pos < data.length) {
                    int subSize = data[pos++] & 0xFF;
                    if (subSize == 0) break;
                    pos += subSize;
                }
                frameCount++;
                totalDelayMs += lastGraphicControlDelayMs;
            } else {
                break;
            }
        }

        return new int[]{width, height, totalDelayMs, Math.max(1, frameCount)};
    }

    private Uri saveFileToGallery(File srcFile, String displayName, String mimeType) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ASCIIWallpaper");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        Uri collection;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        } else {
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        }

        Uri itemUri = resolver.insert(collection, values);
        if (itemUri == null) {
            throw new IOException("Failed to create MediaStore entry in Gallery");
        }

        try (InputStream in = new FileInputStream(srcFile);
             OutputStream out = resolver.openOutputStream(itemUri)) {
            if (out == null) throw new IOException("Failed to open Gallery OutputStream");
            byte[] buf = new byte[64 * 1024];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            out.flush();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues update = new ContentValues();
            update.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(itemUri, update, null, null);
        }
        return itemUri;
    }

    private static void copyUriToFile(ContentResolver resolver, Uri uri, File destFile) throws IOException {
        try (InputStream in = resolver.openInputStream(uri);
             OutputStream out = new FileOutputStream(destFile)) {
            if (in == null) throw new IOException("Unable to open input stream for URI");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }

    private static byte[] readFileToBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(file.length(), 16 * 1024 * 1024L))) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = fis.read(buf)) != -1) {
                bos.write(buf, 0, r);
            }
            return bos.toByteArray();
        }
    }

    private static boolean isGifFile(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[6];
            if (fis.read(header) == 6) {
                String sig = new String(header, "US-ASCII");
                return sig.startsWith("GIF87a") || sig.startsWith("GIF89a");
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static long parseLongSafe(String s, long fallback) {
        if (s == null) return fallback;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseIntSafe(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void releaseSilently(MediaMetadataRetriever retriever) {
        try {
            retriever.release();
        } catch (Throwable ignored) {
        }
    }

    public void shutdown() {
        frameWorkerPool.shutdownNow();
    }
}
