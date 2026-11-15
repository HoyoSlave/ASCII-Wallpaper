package com.asciiwallpaper.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MainActivity recreated for maximum stability:
 * - Density slider restricted strictly to: 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256
 * - Colored text toggle (Colored vs Monochrome white text)
 * - Built-in device monospace font (Typeface.MONOSPACE)
 * - 2 Buttons: "Import media" and "Save to gallery"
 *   (Images save as PNG to Gallery; Videos save as ZIP containing 1.txt, 2.txt, ... per frame)
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

    private AsciiRenderer asciiRenderer;
    private MediaProcessor mediaProcessor;

    private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService playbackExecutor = Executors.newSingleThreadExecutor();
    private Future<?> activeRenderTask;
    private AtomicBoolean activeCancelFlag;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaProcessor.MediaSourceInfo currentMediaSource;
    private MediaProcessor.RenderedOutput currentRenderedOutput;
    private Bitmap displayedBitmap;

    private int currentDensity = AsciiRenderer.DENSITY_VALUES[AsciiRenderer.DEFAULT_DENSITY_INDEX]; // 64
    private boolean isColored = true;

    private boolean isPlayingPreview = false;
    private int currentPlaybackFrame = 1;

    private final Runnable playbackTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPlayingPreview || currentRenderedOutput == null || !currentRenderedOutput.isAnimated) {
                return;
            }
            final MediaProcessor.RenderedOutput output = currentRenderedOutput;
            final int frameToLoad = currentPlaybackFrame;
            currentPlaybackFrame = (currentPlaybackFrame % Math.max(1, output.totalFrames)) + 1;

            playbackExecutor.submit(() -> {
                if (!isPlayingPreview) return;
                final Bitmap bmp = mediaProcessor.loadPreviewFrame(output, frameToLoad);
                if (bmp != null) {
                    mainHandler.post(() -> {
                        if (!isPlayingPreview) {
                            bmp.recycle();
                            return;
                        }
                        updateDisplayedBitmap(bmp);
                        mainHandler.postDelayed(playbackTickRunnable, FRAME_INTERVAL_16FPS_MS);
                    });
                } else {
                    mainHandler.postDelayed(playbackTickRunnable, FRAME_INTERVAL_16FPS_MS);
                }
            });
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
        btnSaveToGallery.setOnClickListener(v -> saveCurrentOutput());
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
        tvStatus.setText("Loading (0/...)");

        renderExecutor.submit(() -> {
            try {
                final MediaProcessor.MediaSourceInfo info = mediaProcessor.loadMediaSource(uri);
                mainHandler.post(() -> {
                    currentMediaSource = info;
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
        currentRenderedOutput = null;

        final MediaProcessor.MediaSourceInfo source = currentMediaSource;
        final int density = currentDensity;
        final boolean colored = isColored;
        final AtomicBoolean cancelFlag = new AtomicBoolean(false);
        activeCancelFlag = cancelFlag;

        activeRenderTask = renderExecutor.submit(() -> {
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
                    startPreviewDisplay(output);
                });
            } catch (InterruptedException ignored) {
            } catch (final Exception e) {
                if (!cancelFlag.get()) {
                    mainHandler.post(() -> tvStatus.setText("Render error: " + e.getMessage()));
                }
            }
        });
    }

    private void saveCurrentOutput() {
        final MediaProcessor.RenderedOutput output = currentRenderedOutput;
        if (output == null || output.outputFile == null || !output.outputFile.exists()) {
            Toast.makeText(this, "Please wait for rendering to complete.", Toast.LENGTH_SHORT).show();
            return;
        }

        btnSaveToGallery.setEnabled(false);
        final int total = output.totalFrames;
        tvStatus.setText("Saving (" + total + "/" + total + ")...");

        renderExecutor.submit(() -> {
            try {
                final String savedLocation = mediaProcessor.saveRenderedOutput(output);
                mainHandler.post(() -> {
                    btnSaveToGallery.setEnabled(true);
                    tvStatus.setText("Saved (" + total + "/" + total + "): " + savedLocation);
                    Toast.makeText(
                            MainActivity.this,
                            output.isAnimated ? "Saved ZIP to " + savedLocation : "Saved to gallery!",
                            Toast.LENGTH_SHORT
                    ).show();
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

    private void startPreviewDisplay(MediaProcessor.RenderedOutput output) {
        stopPreviewPlayback();
        if (!output.isAnimated) {
            if (output.staticBitmap != null && !output.staticBitmap.isRecycled()) {
                updateDisplayedBitmap(output.staticBitmap);
            }
            return;
        }
        isPlayingPreview = true;
        currentPlaybackFrame = 1;
        mainHandler.post(playbackTickRunnable);
    }

    private void stopPreviewPlayback() {
        isPlayingPreview = false;
        mainHandler.removeCallbacks(playbackTickRunnable);
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
        renderExecutor.shutdownNow();
        playbackExecutor.shutdownNow();
        if (displayedBitmap != null && !displayedBitmap.isRecycled()) {
            displayedBitmap.recycle();
            displayedBitmap = null;
        }
    }
}
