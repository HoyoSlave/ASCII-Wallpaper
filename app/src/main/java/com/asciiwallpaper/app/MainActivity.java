package com.asciiwallpaper.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Movie;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main Activity implementing stock Android UI with:
 * - Direct 16 FPS preview rendering (up to 64s for video/GIF)
 * - Real-time (completed/total) frame progress counter during rendering
 * - 1 Slider: "Media opacity" from 0% to 100% with 5% steps
 * - 2 Buttons: "Import media" (image/video/gif) and "Save to gallery"
 *   ("Save to gallery" saves the already-rendered preview file directly without re-rendering)
 */
@SuppressWarnings("deprecation")
public class MainActivity extends Activity {

    private static final int REQUEST_CODE_IMPORT_MEDIA = 1001;
    private static final long FRAME_INTERVAL_16FPS_MS = 62L; // ~16 FPS (62.5ms)

    private ImageView ivPreview;
    private TextView tvStatus;
    private TextView tvOpacityLabel;
    private SeekBar sbMediaOpacity;
    private Button btnImportMedia;
    private Button btnSaveToGallery;

    private AsciiRenderer asciiRenderer;
    private MediaProcessor mediaProcessor;
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private Future<?> activeRenderTask;
    private AtomicBoolean activeCancelFlag;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MediaProcessor.MediaSourceInfo currentMediaSource;
    private MediaProcessor.RenderedOutput currentRenderedOutput;
    private Bitmap liveProgressBitmap;

    // Fallback 16 FPS Movie player for API < 28
    private Movie legacyGifMovie;
    private Bitmap legacyMovieBuffer;
    private Canvas legacyMovieCanvas;
    private long legacyMovieStartMs;

    private int currentOpacityPercent = 50; // Default 50% (step 10 * 5%)

    private final Runnable legacyGifPlaybackRunnable = new Runnable() {
        @Override
        public void run() {
            if (legacyGifMovie != null && legacyMovieBuffer != null && !legacyMovieBuffer.isRecycled()) {
                long now = SystemClock.uptimeMillis();
                if (legacyMovieStartMs == 0L) {
                    legacyMovieStartMs = now;
                }
                int dur = legacyGifMovie.duration();
                if (dur <= 0) dur = 1000;
                int relTime = (int) ((now - legacyMovieStartMs) % dur);
                legacyGifMovie.setTime(relTime);
                legacyGifMovie.draw(legacyMovieCanvas, 0f, 0f);
                ivPreview.setImageBitmap(legacyMovieBuffer);
                ivPreview.invalidate();
                mainHandler.postDelayed(this, FRAME_INTERVAL_16FPS_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        asciiRenderer = new AsciiRenderer();
        mediaProcessor = new MediaProcessor(this, asciiRenderer);

        ivPreview = findViewById(R.id.ivPreview);
        tvStatus = findViewById(R.id.tvStatus);
        tvOpacityLabel = findViewById(R.id.tvOpacityLabel);
        sbMediaOpacity = findViewById(R.id.sbMediaOpacity);
        btnImportMedia = findViewById(R.id.btnImportMedia);
        btnSaveToGallery = findViewById(R.id.btnSaveToGallery);

        updateOpacityLabel(currentOpacityPercent);

        // Slider: "Media opacity" 0% - 100%, step 5% (SeekBar max = 20 -> each unit = 5%)
        sbMediaOpacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentOpacityPercent = progress * 5;
                updateOpacityLabel(currentOpacityPercent);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                currentOpacityPercent = seekBar.getProgress() * 5;
                updateOpacityLabel(currentOpacityPercent);
                if (currentMediaSource != null) {
                    triggerFull16FpsRender();
                }
            }
        });

        // Button 1: "Import media"
        btnImportMedia.setOnClickListener(v -> openMediaPicker());

        // Button 2: "Save to gallery" -> Directly saves the already-rendered preview output file
        btnSaveToGallery.setOnClickListener(v -> savePreviewOutputToGallery());
    }

    private void updateOpacityLabel(int opacityPercent) {
        tvOpacityLabel.setText(String.format(Locale.US, getString(R.string.media_opacity_format), opacityPercent));
    }

    private void openMediaPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*", "image/gif"});
        startActivityForResult(intent, REQUEST_CODE_IMPORT_MEDIA);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_IMPORT_MEDIA && resultCode == Activity.RESULT_OK && data != null) {
            Uri selectedUri = data.getData();
            if (selectedUri != null) {
                handleImportedUri(selectedUri);
            }
        }
    }

    private void handleImportedUri(final Uri uri) {
        cancelActiveRender();
        stopPreviewPlayback();
        btnSaveToGallery.setEnabled(false);
        tvStatus.setText("Loading (0/...)");

        backgroundExecutor.submit(() -> {
            try {
                final MediaProcessor.MediaSourceInfo info = mediaProcessor.loadMediaSource(uri);
                mainHandler.post(() -> {
                    currentMediaSource = info;
                    triggerFull16FpsRender();
                });
            } catch (final Exception e) {
                mainHandler.post(() -> {
                    tvStatus.setText("Error: " + e.getMessage());
                    Toast.makeText(MainActivity.this, "Error loading media", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void triggerFull16FpsRender() {
        if (currentMediaSource == null) return;
        cancelActiveRender();
        stopPreviewPlayback();
        btnSaveToGallery.setEnabled(false);
        currentRenderedOutput = null;

        final MediaProcessor.MediaSourceInfo source = currentMediaSource;
        final int opacity = currentOpacityPercent;
        final AtomicBoolean cancelFlag = new AtomicBoolean(false);
        activeCancelFlag = cancelFlag;

        activeRenderTask = backgroundExecutor.submit(() -> {
            try {
                final MediaProcessor.RenderedOutput output = mediaProcessor.renderFullOutput(
                        source,
                        opacity,
                        cancelFlag,
                        (completed, total, message, latestPreviewFrame) -> mainHandler.post(() -> {
                            if (cancelFlag.get()) return;
                            tvStatus.setText(message);
                            if (latestPreviewFrame != null && !latestPreviewFrame.isRecycled()) {
                                updateLiveProgressBitmap(latestPreviewFrame);
                            }
                        })
                );
                if (cancelFlag.get()) return;
                mainHandler.post(() -> {
                    if (cancelFlag.get()) return;
                    currentRenderedOutput = output;
                    btnSaveToGallery.setEnabled(true);
                    displayRenderedOutputInPreview(output);
                });
            } catch (InterruptedException ignored) {
            } catch (final Exception e) {
                if (!cancelFlag.get()) {
                    mainHandler.post(() -> tvStatus.setText("Render error: " + e.getMessage()));
                }
            }
        });
    }

    /**
     * Immediately saves the already-rendered preview file (16 FPS GIF or PNG) to the Gallery
     * without re-rendering any frames.
     */
    private void savePreviewOutputToGallery() {
        final MediaProcessor.RenderedOutput output = currentRenderedOutput;
        if (output == null || output.outputFile == null || !output.outputFile.exists()) {
            Toast.makeText(this, "Please wait for rendering to complete.", Toast.LENGTH_SHORT).show();
            return;
        }

        btnSaveToGallery.setEnabled(false);
        final int total = output.totalFrames;
        tvStatus.setText("Saving (" + total + "/" + total + ")...");

        backgroundExecutor.submit(() -> {
            try {
                final Uri savedUri = mediaProcessor.saveRenderedOutputToGallery(output);
                mainHandler.post(() -> {
                    btnSaveToGallery.setEnabled(true);
                    tvStatus.setText("Saved (" + total + "/" + total + "): " + savedUri.toString());
                    Toast.makeText(MainActivity.this, "Saved to gallery!", Toast.LENGTH_SHORT).show();
                });
            } catch (final Exception e) {
                mainHandler.post(() -> {
                    btnSaveToGallery.setEnabled(true);
                    tvStatus.setText("Save failed: " + e.getMessage());
                    Toast.makeText(MainActivity.this, "Failed to save: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void updateLiveProgressBitmap(Bitmap frame) {
        ivPreview.setImageBitmap(frame);
        if (liveProgressBitmap != null && liveProgressBitmap != frame && !liveProgressBitmap.isRecycled()) {
            liveProgressBitmap.recycle();
        }
        liveProgressBitmap = frame;
    }

    private void displayRenderedOutputInPreview(MediaProcessor.RenderedOutput output) {
        stopPreviewPlayback();
        if (!output.isAnimated) {
            if (output.staticBitmap != null && !output.staticBitmap.isRecycled()) {
                updateLiveProgressBitmap(output.staticBitmap);
            }
            return;
        }

        File gifFile = output.outputFile;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(gifFile);
                Drawable drawable = ImageDecoder.decodeDrawable(source);
                ivPreview.setImageDrawable(drawable);
                if (drawable instanceof AnimatedImageDrawable) {
                    ((AnimatedImageDrawable) drawable).start();
                }
                return;
            } catch (Exception ignored) {
            }
        }

        // Fallback 16 FPS GIF player
        legacyGifMovie = Movie.decodeFile(gifFile.getAbsolutePath());
        if (legacyGifMovie != null && legacyGifMovie.width() > 0 && legacyGifMovie.height() > 0) {
            legacyMovieBuffer = Bitmap.createBitmap(
                    legacyGifMovie.width(),
                    legacyGifMovie.height(),
                    Bitmap.Config.ARGB_8888
            );
            legacyMovieCanvas = new Canvas(legacyMovieBuffer);
            legacyMovieStartMs = 0L;
            mainHandler.post(legacyGifPlaybackRunnable);
        }
    }

    private void cancelActiveRender() {
        if (activeCancelFlag != null) {
            activeCancelFlag.set(true);
        }
        if (activeRenderTask != null && !activeRenderTask.isDone()) {
            activeRenderTask.cancel(true);
        }
    }

    private void stopPreviewPlayback() {
        mainHandler.removeCallbacks(legacyGifPlaybackRunnable);
        Drawable d = ivPreview.getDrawable();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && d instanceof AnimatedImageDrawable) {
            ((AnimatedImageDrawable) d).stop();
        }
        legacyGifMovie = null;
        legacyMovieCanvas = null;
        if (legacyMovieBuffer != null && !legacyMovieBuffer.isRecycled()) {
            legacyMovieBuffer.recycle();
        }
        legacyMovieBuffer = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelActiveRender();
        stopPreviewPlayback();
        backgroundExecutor.shutdownNow();
        if (mediaProcessor != null) {
            mediaProcessor.shutdown();
        }
        if (asciiRenderer != null) {
            asciiRenderer.shutdown();
        }
        if (liveProgressBitmap != null && !liveProgressBitmap.isRecycled()) {
            liveProgressBitmap.recycle();
            liveProgressBitmap = null;
        }
    }
}
