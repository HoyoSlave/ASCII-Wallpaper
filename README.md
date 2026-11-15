# ASCII Wallpaper (Android)

Aplikasi Android minimalis yang mengonversi gambar, GIF, dan video menjadi seni ASCII menggunakan font `monospace` bawaan perangkat.

## Fitur & Spesifikasi
- **Pilihan Density Karakter**: Slider *Density* dengan nilai `2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192, 256`.
- **Resolusi Output (`Density × 10`)**: Resolusi lebar gambar/GIF ASCII hasil render adalah perkalian `10` dari nilai *density* (`20px` hingga `2560px`, atau `10px` per kolom karakter) dengan tinggi mengikuti rasio aspek asli media.
- **Mode Teks Berwarna atau Monokrom**: Opsi `Colored text` untuk beralih antara karakter ASCII berwarna (RGB) atau putih monokrom di atas latar hitam dengan posisi karakter yang identik di semua tingkat *density*.
- **Font Monospace Bawaan HP**: Menggunakan `Typeface.MONOSPACE` bawaan sistem Android.
- **Lokasi Penyimpanan Terpadu (`Download/ASCIIWallpaper`) & Penamaan Angka (`yyyyMMddHHmmss`)**:
  - Seluruh file disimpan di `Download/ASCIIWallpaper` dengan nama file hanya berupa angka tahun, bulan, tanggal, jam, menit, dan detik (`yyyyMMddHHmmss`, tanpa simbol dan tanpa huruf pada nama file).
  - `Save to gallery`: Menyimpan `<yyyyMMddHHmmss>.png` (gambar) atau `<yyyyMMddHHmmss>.gif` (animasi 16 FPS).
  - `Save TXT` / `Save ZIP`: Menyimpan `<yyyyMMddHHmmss>.txt` (gambar) atau `<yyyyMMddHHmmss>.zip` berisi `1.txt`, `2.txt`, dan seterusnya sesuai frame (video/GIF).
