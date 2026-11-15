# ASCII Wallpaper (Android)

Aplikasi Android minimalis yang mengonversi gambar, GIF, dan video menjadi seni ASCII menggunakan font `monospace` bawaan perangkat.

## Fitur & Spesifikasi
- **Pilihan Density Karakter**: Slider *Density* dengan nilai presisi `2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256`.
- **Mode Teks Berwarna atau Monokrom**: Opsi `Colored text` untuk beralih antara karakter ASCII berwarna (RGB) atau putih monokrom di atas latar hitam.
- **Font Monospace Bawaan HP**: Menggunakan `Typeface.MONOSPACE` bawaan sistem Android dengan proporsi karakter asli.
- **2 Tombol Utama**:
  - `Import media`: Memilih gambar, GIF, atau video.
  - `Save to gallery`: Menyimpan gambar hasil render (`.png`) ke Galeri (`Pictures/ASCIIWallpaper`), atau jika input berupa video/GIF, mengekspor arsip `.zip` yang berisi `1.txt`, `2.txt`, dan seterusnya sesuai urutan frame ke `Downloads/ASCIIWallpaper`.
