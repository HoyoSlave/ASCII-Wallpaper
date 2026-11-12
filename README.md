# ASCII Wallpaper (Android)

Minimalist, stock-Android application that converts images, animated GIFs, and videos into high-resolution colored ASCII wallpapers and 16 FPS animated GIFs.

## Features & Specifications

### 1. User Interface (Stock Android)
- **Stock Android Theme**: Uses native `android:Theme.DeviceDefault` without custom UI libraries or skins.
- **Direct 16 FPS Preview & `(completed/total)` Counter**: Renders images and video/GIF inputs directly at **16 FPS** (up to 64 seconds), showing real-time `Loading (completed/total)` frame progress and playing the rendered result directly in the preview container.
- **1 Slider (`Media opacity`)**: Adjusts original underlying media opacity from `0%` to `100%` in exact `5%` increments (`SeekBar` with 20 steps).
- **2 Buttons**:
  - `Import media`: Opens the system picker for images (`image/*`), animated GIFs (`image/gif`), and videos (`video/*`).
  - `Save to gallery`: Saves the already-rendered preview output file (PNG or 16 FPS GIF) directly to the device gallery (`Pictures/ASCIIWallpaper`) instantaneously without re-rendering.
