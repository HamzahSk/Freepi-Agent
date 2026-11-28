Synchronize Android App with New Movie Source Architecture

Saya sudah melakukan refactor besar pada repository "HamzahSk/movie-source". Sekarang arsitekturnya menggunakan sistem JavaScript Extension modular seperti Tachiyomi Extensions, dengan shared extractor registry.

Tugas kamu adalah menyinkronkan repository aplikasi Android ini agar sepenuhnya kompatibel dengan struktur dan sistem terbaru tersebut.

Repository source:
"https://github.com/HamzahSk/movie-source"

1. Audit kedua repository

Sebelum mengubah kode, pelajari terlebih dahulu:

- Struktur terbaru "movie-source".
- "manifest.json" dan schema terbarunya.
- Sistem extension dan export masing-masing source.
- Sistem shared dependencies dan module loading.
- "extractors/index.js" dan seluruh shared extractor.
- Build output "extension.js".
- Hash verification dan module caching.
- Implementasi Android saat ini: "SourceModuleStore", "RemoteSourceRuntime", "ScrapeScriptLoader", "ScrapeValidator", dan "ScrapeMapper".

Jangan hanya mengandalkan dokumentasi atau laporan refactor sebelumnya. Periksa implementasi sebenarnya di kedua repository.

2. Sinkronisasi Manifest dan Module Loader

Pastikan aplikasi Android dapat membaca manifest terbaru dan menangani:

- Multiple independent extensions.
- Export masing-masing extension.
- Entry point dan runtime configuration.
- Dependencies antar-module.
- Shared extractor registry.
- SHA-256 setiap module.
- Versioning dan update extension.

Pastikan dependency dimuat dalam urutan yang benar sebelum extension dijalankan.

Perhatikan bahwa setiap extension sekarang memiliki module dan export masing-masing, bukan lagi satu global catalog runtime.

3. Integrasi Shared Extractors

Ini bagian yang sangat penting.

Aplikasi harus dapat menggunakan shared extractor dari repository "movie-source".

Pastikan:

- Extractor registry dapat dimuat dengan benar.
- Extension dapat memanggil extractor bersama.
- Dependency extractor tidak terduplikasi.
- Extractor tetap tersedia untuk semua extension yang membutuhkannya.
- Kegagalan satu extractor tidak merusak source lainnya.

Dukung extractor yang tersedia saat ini, termasuk Videasy, P2P, Hydrax, OK.ru, Dailymotion, dan Direct.

Jangan mengimplementasikan ulang parser extractor di Android jika sudah tersedia dalam JavaScript.

4. Pertahankan Runtime Compatibility

Pastikan "RemoteSourceRuntime" dapat menjalankan semua extension terbaru di isolated WebView.

Periksa:

- JavaScript execution environment.
- HTTP bridge melalui "MovieSourceHttp".
- Module initialization dan dependency loading.
- Export resolution.
- Pemanggilan operasi "home", "search", "list", "detail", "episodes", dan "stream".
- Error propagation.
- Timeout dan cancellation.
- Logging dan debugging.

Pastikan tidak ada asumsi bahwa Node.js atau native ESM tersedia di WebView.

5. Sinkronisasi Seluruh Source

Pastikan semua source yang terdaftar di manifest dapat digunakan melalui aplikasi:

- LK21
- MovieBox
- Movy

Setiap source harus memiliki fitur yang sesuai dengan kemampuan extension masing-masing.

Pastikan pencarian, katalog, kategori/filter, detail film, detail series, episode, dan playback berjalan dengan benar.

Jangan mengasumsikan semua source memiliki fitur yang sama. Gunakan operasi yang benar-benar didukung masing-masing extension.

6. UI dan Source Management

Periksa apakah halaman Settings dan mekanisme pemilihan source sudah sesuai.

Pastikan:

- Source yang tersedia berasal dari manifest remote.
- Source dapat dipilih dan diganti.
- Informasi nama, versi, bahasa, dan website ditampilkan dengan benar.
- Refresh manifest dan module berjalan.
- Update source tidak menghapus Favorites, History, atau Watch Progress.
- Cache catalog dibersihkan secara tepat ketika source diganti.
- Source yang gagal diperbarui tidak menghilangkan snapshot terakhir yang masih valid.

Jangan melakukan redesign UI besar-besaran. Fokus pada sinkronisasi dan perbaikan yang diperlukan.

7. Testing Wajib

Lakukan pengujian integrasi menggunakan repository "movie-source" yang sebenarnya.

Minimal lakukan:

1. Download dan validasi manifest.
2. Download seluruh dependency module.
3. Verifikasi SHA-256.
4. Load extension LK21, MovieBox, dan Movy.
5. Uji seluruh operasi yang tersedia.
6. Uji shared extractor.
7. Uji stream resolution.
8. Uji perpindahan source.
9. Uji refresh dan update module.
10. Uji kondisi jaringan gagal dan module tidak valid.

Gunakan fixture untuk pengujian deterministik dan live testing jika memungkinkan.

Jika website memblokir runner atau request gagal karena keterbatasan jaringan, dokumentasikan hasilnya. Jangan menganggap pengujian berhasil hanya karena mock test lolos.

8. Build dan Final Verification

Setelah selesai:

- Jalankan "./gradlew testDebugUnitTest".
- Jalankan "./gradlew assembleDebug".
- Perbaiki seluruh error yang berkaitan dengan perubahan.
- Pastikan tidak ada regresi pada fitur aplikasi yang sudah berjalan.

Aturan Penting

- Jangan mengubah kontrak "freepi.*.v1" tanpa alasan yang kuat.
- Jangan merusak kompatibilitas dengan manifest dan runtime terbaru.
- Jangan menduplikasi extractor di aplikasi Android.
- Jangan menghapus fallback scraping lama sebelum penggantinya terbukti berfungsi.
- Jangan mengubah fitur yang tidak berkaitan dengan pekerjaan ini.
- Jika perlu mengubah "movie-source", jelaskan alasannya dan pastikan perubahan tetap kompatibel dengan desain extension terbaru.

Target akhir: Aplikasi Android menjadi generic extension client yang sepenuhnya mengikuti arsitektur terbaru "movie-source". Semua source, dependency, dan shared extractor dikelola dari repository "movie-source", sedangkan aplikasi Android hanya bertanggung jawab atas runtime execution, UI, penyimpanan lokal, dan playback.

Di akhir pekerjaan, berikan laporan file yang diubah, masalah yang ditemukan, hasil testing, dan fitur yang berhasil diverifikasi.