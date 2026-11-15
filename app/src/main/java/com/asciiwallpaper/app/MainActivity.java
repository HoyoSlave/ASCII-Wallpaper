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
import android.widget.CheckBox;
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
 * MainActivity supporting both Gallery saving (.png / 16 FPS .gif) AND TXT/ZIP saving
 * (.txt for static image, .zip with 1.txt, 2.txt, ... for video/GIF):
 * - Density slider restricted strictly to: 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256
 * - Colored text toggle (Colored vs Monochrome white text)
 * - Built-in device monospace font (Typeface.MONOSPACE)
 * - Buttons: "Import media", "Save to gallery", and "Save TXT" / "Save ZIP"
 */
@SuppressWarnings("deprecation")
public class MainActivity extends Activity {

    private static final int REQUEST_CODE_IMPORT_MEDIA = 1001;
    private static final long FRAME_INTERVAL_16FPS_MS = 62L;

    private ImageView ivPreview;
    private TextView tvStatus;
    private CheckBox cbColored;
    private TextView tvDensityLabel;
    private SeekBar sbDensity;
    private Button btnImportMedia;
    private Button btnSaveToGallery;
    private Button btnSaveTxt;

    private AsciiRenderer asciiRenderer;
    private MediaProcessor mediaProcessor;

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private Future<?> activeRenderTask;
    private AtomicBoolean activeCancelFlag;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaProcessor.MediaSourceInfo currentMediaSource;
    private MediaProcessor.RenderedOutput currentRenderedOutput;
    private Bitmap displayedBitmap;

    // Fallback 16 FPS Movie player for API < 28
    private Movie legacyGifMovie;
    private Bitmap legacyMovieBuffer;
    private Canvas legacyMovieCanvas;
    private long legacyMovieStartMs;

    private int currentDensity = AsciiRenderer.DENSITY_VALUES[AsciiRenderer.DEFAULT_DENSITY_INDEX]; // 64
    private boolean isColored = true;

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
        cbColored = findViewById(R.id.cbColored);
        tvDensityLabel = findViewById(R.id.tvDensityLabel);
        sbDensity = findViewById(R.id.sbDensity);
        btnImportMedia = findViewById(R.id.btnImportMedia);
        btnSaveToGallery = findViewById(R.id.btnSaveToGallery);
        btnSaveTxt = findViewById(R.id.btnSaveTxt);

        sbDensity.setMax(AsciiRenderer.DENSITY_VALUES.length - 1);
        sbDensity.setProgress(AsciiRenderer.DEFAULT_DENSITY_INDEX);
        updateDensityLabel(currentDensity);

        cbColored.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isColored = isChecked;
            if (currentMediaSource != null) {
                triggerRender();
            }
        });

        sbDensity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int idx = Math.max(0, Math.min(AsciiRenderer.DENSITY_VALUES.length - 1, progress));
                currentDensity = AsciiRenderer.DENSITY_VALUES[idx];
                updateDensityLabel(currentDensity);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int idx = Math.max(0, Math.min(AsciiRenderer.DENSITY_VALUES.length - 1, seekBar.getProgress()));
                currentDensity = AsciiRenderer.DENSITY_VALUES[idx];
                updateDensityLabel(currentDensity);
                if (currentMediaSource != null) {
                    triggerRender();
                }
            }
        });

        btnImportMedia.setOnClickListener(v -> openMediaPicker());
        btnSaveToGallery.setOnClickListener(v -> saveGalleryOutput());
        btnSaveTxt.setOnClickListener(v -> saveTxtOrZipOutput());
    }

    private void updateDensityLabel(int densityValue) {
        tvDensityLabel.setText(String.format(Locale.US, getString(R.string.density_format), densityValue));
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
        btnSaveTxt.setEnabled(false);
        tvStatus.setText("Loading (0/...)");

        backgroundExecutor.submit(() -> {
            try {
                final MediaProcessor.MediaSourceInfo info = mediaProcessor.loadMediaSource(uri);
                mainHandler.post(() -> {
                    currentMediaSource = info;
                    btnSaveTxt.setText(info.isAnimated() ? R.string.save_zip : R.string.save_txt);
                    triggerRender();
                });
            } catch (final Exception e) {
                mainHandler.post(() -> {
                    tvStatus.setText("Error: " + e.getMessage());
                    Toast.makeText(MainActivity.this, "Error loading media", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void triggerRender() {
        if (currentMediaSource == null) return;
        cancelActiveRender();
        stopPreviewPlayback();
        btnSaveToGallery.setEnabled(false);
        btnSaveTxt.setEnabled(false);
        currentRenderedOutput = null;

        final MediaProcessor.MediaSourceInfo source = currentMediaSource;
        final int density = currentDensity;
        final boolean colored = isColored;
        final AtomicBoolean cancelFlag = new AtomicBoolean(false);
        activeCancelFlag = cancelFlag;

        activeRenderTask = backgroundExecutor.submit(() -> {
            try {
                final MediaProcessor.RenderedOutput output = mediaProcessor.renderOutput(
                        source,
                        density,
                        colored,
                        cancelFlag,
                        (completed, total, message, previewFrame) -> mainHandler.post(() -> {
                            if (cancelFlag.get()) {
                                if (previewFrame != null && !previewFrame.isRecycled()) {
                                    previewFrame.recycle();
                                }
                                return;
                            }
                            tvStatus.setText(message);
                            if (previewFrame != null && !previewFrame.isRecycled()) {
                                updateDisplayedBitmap(previewFrame);
                            }
                        })
                );
                if (cancelFlag.get()) return;
                mainHandler.post(() -> {
                    if (cancelFlag.get()) return;
                    currentRenderedOutput = output;
                    btnSaveToGallery.setEnabled(true);
                    btnSaveTxt.setEnabled(true);
                    btnSaveTxt.setText(output.isAnimated ? R.string.save_zip : R.string.save_txt);
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

    private void saveGalleryOutput() {
        final MediaProcessor.RenderedOutput output = currentRenderedOutput;
        if (output == null || output.galleryFile == null || !output.galleryFile.exists()) {
            Toast.makeText(this, "Please wait for rendering to complete.", Toast.LENGTH_SHORT).show();
            return;
        }

        btnSaveToGallery.setEnabled(false);
        final int total = output.totalFrames;
        tvStatus.setText("Saving to gallery (" + total + "/" + total + ")...");

        backgroundExecutor.submit(() -> {
            try {
                final Uri savedUri = mediaProcessor.saveToGallery(output);
                mainHandler.post(() -> {
                    btnSaveToGallery.setEnabled(true);
                    tvStatus.setText("Saved to gallery (" + total + "/" + total + "): " + savedUri.toString());
                    Toast.makeText(MainActivity.this, "Saved to gallery!", Toast.LENGTH_SHORT).show();
                });
            } catch (final Exception e) {
                mainHandler.post(() -> {
                    btnSaveToGallery.setEnabled(true);
                    tvStatus.setText("Gallery save failed: " + e.getMessage());
                    Toast.makeText(MainActivity.this, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void saveTxtOrZipOutput() {
        final MediaProcessor.RenderedOutput output = currentRenderedOutput;
        if (output == null || output.textOrZipFile == null || !output.textOrZipFile.exists()) {
            Toast.makeText(this, "Please wait for rendering to complete.", Toast.LENGTH_SHORT).show();
            return;
        }

        btnSaveTxt.setEnabled(false);
        final int total = output.totalFrames;
        tvStatus.setText("Saving " + (output.isAnimated ? "ZIP" : "TXT") + " (" + total + "/" + total + ")...");

        backgroundExecutor.submit(() -> {
            try {
                final String savedPath = mediaProcessor.saveTextOrZip(output);
                mainHandler.post(() -> {
                    btnSaveTxt.setEnabled(true);
                    tvStatus.setText("Saved (" + total + "/" + total + "): " + savedPath);
                    Toast.makeText(MainActivity.this, "Saved: " + savedPath, Toast.LENGTH_SHORT).show();
                });
            } catch (final Exception e) {
                mainHandler.post(() -> {
                    btnSaveTxt.setEnabled(true);
                    tvStatus.setText("Save failed: " + e.getMessage());
                    Toast.makeText(MainActivity.this, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void displayRenderedOutputInPreview(MediaProcessor.RenderedOutput output) {
        stopPreviewPlayback();
        if (!output.isAnimated) {
            if (output.staticBitmap != null && !output.staticBitmap.isRecycled()) {
                updateDisplayedBitmap(output.staticBitmap);
            }
            return;
        }

        File gifFile = output.galleryFile;
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

    private void updateDisplayedBitmap(Bitmap newBitmap) {
        ivPreview.setImageBitmap(newBitmap);
        if (displayedBitmap != null
                && displayedBitmap != newBitmap
                && (currentRenderedOutput == null || displayedBitmap != currentRenderedOutput.staticBitmap)
                && !displayedBitmap.isRecycled()) {
            displayedBitmap.recycle();
        }
        displayedBitmap = newBitmap;
    }

    private void cancelActiveRender() {
        if (activeCancelFlag != null) {
            activeCancelFlag.set(true);
        }
        if (activeRenderTask != null && !activeRenderTask.isDone()) {
            activeRenderTask.cancel(true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelActiveRender();
        stopPreviewPlayback();
        backgroundExecutor.shutdownNow();
        if (displayedBitmap != null && !displayedBitmap.isRecycled()) {
            displayedBitmap.recycle();
            displayedBitmap = null;
        }
    }
}
