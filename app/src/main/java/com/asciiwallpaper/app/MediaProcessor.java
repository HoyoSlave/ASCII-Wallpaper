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
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Stable MediaProcessor saving ALL outputs (both PNG/GIF media and TXT/ZIP text files)
 * into the unified Download/ASCIIWallpaper folder with numeric-only timestamp filenames (yyyyMMddHHmmss).
 */
@SuppressWarnings("deprecation")
public final class MediaProcessor {

    public static final int TARGET_FPS = 16;
    public static final int MAX_DURATION_SECONDS = 64;
    private static final int MAX_DECODE_SIDE = 1024;
    public static final String DOWNLOAD_SUBFOLDER = "ASCIIWallpaper";

    public enum MediaType {
        STATIC_IMAGE,
        ANIMATED_GIF,
        VIDEO
    }

    public interface RenderListener {
        void onProgress(int completedFrames, int totalFrames, String statusMessage, Bitmap previewFrame);
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
            return mediaType == MediaType.VIDEO || mediaType == MediaType.ANIMATED_GIF;
        }
    }

    public static final class RenderedOutput {
        public final File galleryFile;     // .png (image) or .gif (video/animated)
        public final String galleryMime;   // image/png or image/gif
        public final File textOrZipFile;   // .txt (image) or .zip containing 1.txt, 2.txt... (video/animated)
        public final String textOrZipMime; // text/plain or application/zip
        public final boolean isAnimated;
        public final Bitmap staticBitmap;
        public final int totalFrames;

        public RenderedOutput(
                File galleryFile,
                String galleryMime,
                File textOrZipFile,
                String textOrZipMime,
                boolean isAnimated,
                Bitmap staticBitmap,
                int totalFrames
        ) {
            this.galleryFile = galleryFile;
            this.galleryMime = galleryMime;
            this.textOrZipFile = textOrZipFile;
            this.textOrZipMime = textOrZipMime;
            this.isAnimated = isAnimated;
            this.staticBitmap = staticBitmap;
            this.totalFrames = totalFrames;
        }
    }

    private final Context context;
    private final AsciiRenderer renderer;

    public MediaProcessor(Context context, AsciiRenderer renderer) {
        this.context = context.getApplicationContext();
        this.renderer = renderer;
    }

    public MediaSourceInfo loadMediaSource(Uri uri) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String mime = resolver.getType(uri);
        if (mime == null) mime = "";

        File cacheDir = new File(context.getCacheDir(), "ascii_media_cache");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        File cachedFile = new File(cacheDir, "active_source.bin");
        copyUriToFile(resolver, uri, cachedFile);

        String pathLower = uri.toString().toLowerCase();
        boolean isGif = mime.equalsIgnoreCase("image/gif") || pathLower.endsWith(".gif") || hasGifHeader(cachedFile);
        boolean isVideo = mime.startsWith("video/")
                || pathLower.endsWith(".mp4")
                || pathLower.endsWith(".mkv")
                || pathLower.endsWith(".webm")
                || pathLower.endsWith(".mov")
                || pathLower.endsWith(".3gp");

        if (isVideo && !isGif) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(cachedFile.getAbsolutePath());
                long durationMs = parseLongSafe(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION), 1000L);
                int w = parseIntSafe(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH), 1024);
                int h = parseIntSafe(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT), 1024);
                int rot = parseIntSafe(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION), 0);
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

        if (isGif) {
            byte[] gifBytes = readFileToBytes(cachedFile);
            Movie movie = Movie.decodeByteArray(gifBytes, 0, gifBytes.length);
            if (movie != null && movie.duration() > 0 && movie.width() > 0 && movie.height() > 0) {
                long cappedDuration = Math.min((long) movie.duration(), MAX_DURATION_SECONDS * 1000L);
                return new MediaSourceInfo(
                        uri,
                        MediaType.ANIMATED_GIF,
                        cachedFile,
                        gifBytes,
                        cappedDuration,
                        movie.width(),
                        movie.height()
                );
            }
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(cachedFile.getAbsolutePath(), opts);
        int w = opts.outWidth > 0 ? opts.outWidth : 1024;
        int h = opts.outHeight > 0 ? opts.outHeight : 1024;
        return new MediaSourceInfo(uri, MediaType.STATIC_IMAGE, cachedFile, null, 0L, w, h);
    }

    /**
     * Renders both the visual file (.png or 16 FPS .gif) and the TXT/ZIP file (.txt or .zip of 1.txt..N.txt)
     * in a single background pass.
     */
    public RenderedOutput renderOutput(
            MediaSourceInfo source,
            int densityCols,
            boolean colored,
            AtomicBoolean cancelFlag,
            RenderListener listener
    ) throws Exception {
        if (!source.isAnimated()) {
            if (listener != null) {
                listener.onProgress(0, 1, "Loading (0/1)", null);
            }
            Bitmap raw = decodeScaledStaticBitmap(source.cachedSourceFile, MAX_DECODE_SIDE);
            AsciiRenderer.FrameRenderResult res = renderer.renderFrame(raw, densityCols, colored);
            if (raw != res.bitmap) {
                raw.recycle();
            }

            File outputPng = new File(context.getCacheDir(), "ascii_rendered_image.png");
            try (FileOutputStream fos = new FileOutputStream(outputPng)) {
                res.bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }

            File outputTxt = new File(context.getCacheDir(), "ascii_rendered_text.txt");
            try (FileOutputStream fos = new FileOutputStream(outputTxt)) {
                fos.write(res.asciiText.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }

            if (listener != null) {
                listener.onProgress(1, 1, "Ready (1/1)", res.bitmap);
            }
            return new RenderedOutput(
                    outputPng,
                    "image/png",
                    outputTxt,
                    "text/plain",
                    false,
                    res.bitmap,
                    1
            );
        }

        final long durationMs = Math.min(source.durationMs, MAX_DURATION_SECONDS * 1000L);
        final int totalFrames = Math.max(1, Math.min(
                MAX_DURATION_SECONDS * TARGET_FPS,
                (int) Math.ceil((durationMs / 1000.0) * TARGET_FPS)
        ));

        if (listener != null) {
            listener.onProgress(0, totalFrames, "Loading (0/" + totalFrames + ")", null);
        }

        File tempGif = new File(context.getCacheDir(), "ascii_rendering_in_progress.gif");
        File finalGif = new File(context.getCacheDir(), "ascii_rendered_16fps.gif");
        File tempZip = new File(context.getCacheDir(), "ascii_frames_in_progress.zip");
        File finalZip = new File(context.getCacheDir(), "ascii_frames_output.zip");
        if (tempGif.exists()) tempGif.delete();
        if (tempZip.exists()) tempZip.delete();

        MediaMetadataRetriever videoRetriever = null;
        Movie gifMovie = null;
        Bitmap gifCanvasBitmap = null;
        Canvas gifCanvas = null;
        FastGifEncoder gifEncoder = null;

        try {
            if (source.mediaType == MediaType.VIDEO) {
                videoRetriever = new MediaMetadataRetriever();
                videoRetriever.setDataSource(source.cachedSourceFile.getAbsolutePath());
            } else if (source.mediaType == MediaType.ANIMATED_GIF) {
                byte[] bytes = source.gifBytes != null ? source.gifBytes : readFileToBytes(source.cachedSourceFile);
                gifMovie = Movie.decodeByteArray(bytes, 0, bytes.length);
                if (gifMovie != null) {
                    gifCanvasBitmap = Bitmap.createBitmap(
                            Math.max(1, gifMovie.width()),
                            Math.max(1, gifMovie.height()),
                            Bitmap.Config.ARGB_8888
                    );
                    gifCanvas = new Canvas(gifCanvasBitmap);
                }
            }

            try (ZipOutputStream zos = new ZipOutputStream(
                    new BufferedOutputStream(new FileOutputStream(tempZip), 128 * 1024))) {

                for (int frameNum = 1; frameNum <= totalFrames; frameNum++) {
                    if (cancelFlag != null && cancelFlag.get()) {
                        throw new InterruptedException("Render cancelled");
                    }

                    long timestampMs = Math.round(((frameNum - 1) * 1000.0) / TARGET_FPS);
                    Bitmap rawFrame = extractFrameBitmap(
                            source,
                            videoRetriever,
                            gifMovie,
                            gifCanvasBitmap,
                            gifCanvas,
                            timestampMs,
                            Math.max(64, densityCols * 2)
                    );
                    if (rawFrame == null) {
                        rawFrame = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
                    }

                    AsciiRenderer.FrameRenderResult res = renderer.renderFrame(rawFrame, densityCols, colored);
                    if (rawFrame != res.bitmap && rawFrame != gifCanvasBitmap) {
                        rawFrame.recycle();
                    }

                    if (gifEncoder == null) {
                        gifEncoder = new FastGifEncoder(
                                tempGif,
                                res.bitmap.getWidth(),
                                res.bitmap.getHeight(),
                                TARGET_FPS
                        );
                    }
                    gifEncoder.addFrame(res.bitmap);

                    // Write <frameNum>.txt (1.txt, 2.txt, 3.txt, ...) into the ZIP archive
                    ZipEntry entry = new ZipEntry(frameNum + ".txt");
                    zos.putNextEntry(entry);
                    zos.write(res.asciiText.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();

                    if (listener != null) {
                        Bitmap previewCopy = res.bitmap.copy(Bitmap.Config.ARGB_8888, false);
                        listener.onProgress(
                                frameNum,
                                totalFrames,
                                "Loading (" + frameNum + "/" + totalFrames + ")",
                                previewCopy
                        );
                    }
                    res.bitmap.recycle();
                }
                zos.finish();
            }

            if (gifEncoder != null) {
                gifEncoder.finish();
                gifEncoder = null;
            }
        } finally {
            if (gifEncoder != null) {
                try {
                    gifEncoder.close();
                } catch (Throwable ignored) {
                }
            }
            releaseSilently(videoRetriever);
            if (gifCanvasBitmap != null && !gifCanvasBitmap.isRecycled()) {
                gifCanvasBitmap.recycle();
            }
        }

        if (finalGif.exists()) finalGif.delete();
        tempGif.renameTo(finalGif);

        if (finalZip.exists()) finalZip.delete();
        tempZip.renameTo(finalZip);

        if (listener != null) {
            listener.onProgress(totalFrames, totalFrames, "Ready (" + totalFrames + "/" + totalFrames + ")", null);
        }

        return new RenderedOutput(
                finalGif,
                "image/gif",
                finalZip,
                "application/zip",
                true,
                null,
                totalFrames
        );
    }

    /**
     * Generates a filename stem containing ONLY digits: year month day hour minute second (yyyyMMddHHmmss).
     * Zero symbols and zero alphabet characters in the filename stem.
     */
    private static String generateNumericTimestampName() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        return sdf.format(new Date());
    }

    /**
     * Saves the rendered visual file (.png or 16 FPS .gif) into Download/ASCIIWallpaper
     * named strictly as <yyyyMMddHHmmss>.png or <yyyyMMddHHmmss>.gif.
     */
    public String saveToGallery(RenderedOutput output) throws IOException {
        if (output == null || output.galleryFile == null || !output.galleryFile.exists()) {
            throw new IOException("No rendered visual output available");
        }
        String ext = output.isAnimated ? ".gif" : ".png";
        String displayName = generateNumericTimestampName() + ext;
        return saveFileToDownloads(output.galleryFile, displayName, output.galleryMime);
    }

    /**
     * Saves the rendered text output (.txt for image, or .zip containing 1.txt, 2.txt... for video)
     * into Download/ASCIIWallpaper named strictly as <yyyyMMddHHmmss>.txt or <yyyyMMddHHmmss>.zip.
     */
    public String saveTextOrZip(RenderedOutput output) throws IOException {
        if (output == null || output.textOrZipFile == null || !output.textOrZipFile.exists()) {
            throw new IOException("No rendered text/ZIP output available");
        }
        String ext = output.isAnimated ? ".zip" : ".txt";
        String displayName = generateNumericTimestampName() + ext;
        return saveFileToDownloads(output.textOrZipFile, displayName, output.textOrZipMime);
    }

    private Bitmap extractFrameBitmap(
            MediaSourceInfo source,
            MediaMetadataRetriever videoRetriever,
            Movie gifMovie,
            Bitmap gifCanvasBitmap,
            Canvas gifCanvas,
            long timestampMs,
            int sampleTargetDim
    ) {
        if (source.mediaType == MediaType.VIDEO && videoRetriever != null) {
            long timeUs = timestampMs * 1000L;
            int targetW = Math.max(32, Math.min(MAX_DECODE_SIDE, sampleTargetDim));
            int targetH = Math.max(32, Math.round((float) targetW * source.height / Math.max(1, source.width)));
            Bitmap frame = null;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    frame = videoRetriever.getScaledFrameAtTime(
                            timeUs, MediaMetadataRetriever.OPTION_CLOSEST, targetW, targetH);
                    if (frame == null) {
                        frame = videoRetriever.getScaledFrameAtTime(
                                timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetW, targetH);
                    }
                }
                if (frame == null) {
                    frame = videoRetriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
                }
                if (frame == null) {
                    frame = videoRetriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                }
                if (frame == null) {
                    frame = videoRetriever.getFrameAtTime();
                }
            } catch (Exception ignored) {
            }
            if (frame != null) {
                Bitmap sw = AsciiRenderer.ensureSoftwareBitmap(frame);
                if (sw != frame) frame.recycle();
                return sw;
            }
            return null;
        } else if (source.mediaType == MediaType.ANIMATED_GIF && gifMovie != null && gifCanvas != null) {
            int dur = Math.max(1, gifMovie.duration());
            int relTime = (int) (timestampMs % dur);
            gifCanvas.drawColor(Color.BLACK);
            gifMovie.setTime(relTime);
            gifMovie.draw(gifCanvas, 0f, 0f);
            return gifCanvasBitmap;
        } else {
            return decodeScaledStaticBitmap(source.cachedSourceFile, MAX_DECODE_SIDE);
        }
    }

    private static Bitmap decodeScaledStaticBitmap(File file, int maxSide) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);

        int inSample = 1;
        int w = Math.max(1, bounds.outWidth);
        int h = Math.max(1, bounds.outHeight);
        while (w / inSample > maxSide * 2 || h / inSample > maxSide * 2) {
            inSample *= 2;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = inSample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bm = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        if (bm == null) {
            return Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        }
        Bitmap sw = AsciiRenderer.ensureSoftwareBitmap(bm);
        if (sw != bm) bm.recycle();
        return sw;
    }

    /**
     * Unified saver: saves all files (.png, .gif, .txt, .zip) into Download/ASCIIWallpaper.
     */
    private String saveFileToDownloads(File srcFile, String displayName, String mimeType) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_SUBFOLDER);
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri itemUri = resolver.insert(collection, values);
            if (itemUri == null) {
                throw new IOException("Failed to create Downloads entry");
            }

            try (InputStream in = new FileInputStream(srcFile);
                 OutputStream out = resolver.openOutputStream(itemUri)) {
                if (out == null) throw new IOException("Failed to open Downloads stream");
                byte[] buf = new byte[64 * 1024];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
                out.flush();
            }

            ContentValues update = new ContentValues();
            update.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(itemUri, update, null, null);
            return "Download/" + DOWNLOAD_SUBFOLDER + "/" + displayName;
        } else {
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File targetDir = new File(downloadsDir, DOWNLOAD_SUBFOLDER);
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
            File outFile = new File(targetDir, displayName);
            try (InputStream in = new FileInputStream(srcFile);
                 OutputStream out = new FileOutputStream(outFile)) {
                byte[] buf = new byte[64 * 1024];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
                out.flush();
            }
            try {
                MediaScannerConnection.scanFile(
                        context,
                        new String[]{outFile.getAbsolutePath()},
                        new String[]{mimeType},
                        null
                );
            } catch (Throwable ignored) {
            }
            return "Download/" + DOWNLOAD_SUBFOLDER + "/" + displayName;
        }
    }

    private static void copyUriToFile(ContentResolver resolver, Uri uri, File destFile) throws IOException {
        try (InputStream in = resolver.openInputStream(uri);
             OutputStream out = new FileOutputStream(destFile)) {
            if (in == null) throw new IOException("Unable to open URI input stream");
            byte[] buf = new byte[64 * 1024];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            out.flush();
        }
    }

    private static byte[] readFileToBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(file.length(), 16 * 1024 * 1024L))) {
            byte[] buf = new byte[64 * 1024];
            int len;
            while ((len = fis.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            return bos.toByteArray();
        }
    }

    private static boolean hasGifHeader(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] hdr = new byte[6];
            if (fis.read(hdr) == 6) {
                String sig = new String(hdr, StandardCharsets.US_ASCII);
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
        if (retriever != null) {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }
}
