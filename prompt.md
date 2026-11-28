Refactor Movie Source Architecture — JavaScript Extension System

Saya ingin mengubah arsitektur repository "HamzahSk/movie-source" menjadi sistem extension yang terstruktur, terinspirasi dari Tachiyomi Extensions, tetapi dengan pendekatan yang lebih sederhana.

1. Analisis terlebih dahulu

Sebelum mengubah kode, lakukan analisis menyeluruh terhadap:

- Struktur repository "movie-source".
- Sistem manifest dan registrasi source saat ini.
- Runtime JavaScript dan mekanisme pemanggilan module.
- Sistem scraping, parsing HTML, extractor, stream, dan dependency.
- Kontrak data antara source dan aplikasi Android.
- Folder "movy" yang berisi script dari Tachiyomi Extension.

Pahami bagaimana setiap bagian saling terhubung. Jangan langsung melakukan refactor sebelum memahami arsitektur yang sudah ada.

2. Arsitektur extension baru

Buat sistem source yang mirip Tachiyomi Extensions dengan ketentuan:

- Setiap website/provider menjadi satu extension.
- Setiap extension berupa file atau modul JavaScript (".js").
- Tidak membutuhkan Kotlin, Gradle, APK, atau proses compile.
- Extension dapat ditambahkan, diperbarui, dan dihapus secara independen.
- Source tidak boleh memiliki ketergantungan langsung terhadap UI Android.
- Semua extension mengikuti satu standar API dan mengembalikan normalized response yang kompatibel dengan aplikasi saat ini.

Buat struktur direktori yang konsisten dan scalable. Pisahkan extension berdasarkan provider, bukan mencampur semua logic dalam satu file.

Contoh gambaran struktur (silakan sesuaikan setelah analisis):

movie-source/
├── manifest.json
├── extensions/
│   ├── movy/
│   │   ├── extension.js
│   │   ├── config.json
│   │   └── README.md
│   └── other-source/
│       ├── extension.js
│       ├── config.json
│       └── README.md
├── runtime/
├── shared/
├── schemas/
└── README.md

Struktur tersebut hanya referensi. Rancang struktur terbaik berdasarkan kebutuhan runtime yang sudah ada.

3. Migrasi source Movy

Saya sudah menyediakan script Tachiyomi Extension di folder "movy".

Tugas kamu:

- Pelajari seluruh struktur dan source code di folder tersebut.
- Identifikasi cara kerja source, request, parsing, pencarian, kategori, detail, chapter/episode, dan stream jika tersedia.
- Migrasikan source tersebut menjadi extension baru bernama "movy".
- Ubah seluruh implementasi yang diperlukan menjadi JavaScript.
- Jangan sekadar mengganti ekstensi file. Adaptasikan API, dependency, HTTP request, HTML parsing, dan seluruh mekanisme khusus Tachiyomi agar berjalan di runtime project ini.
- Pertahankan fitur dan perilaku asli source sejauh memungkinkan.
- Jangan membawa dependency Android/Kotlin yang tidak diperlukan.

Jika ada fitur Tachiyomi yang tidak memiliki padanan di runtime sekarang, cari pendekatan JavaScript yang kompatibel.

4. Standarisasi API

Buat kontrak API yang jelas untuk setiap extension.

Minimal pertimbangkan operasi:

- "home"
- "search"
- "list"
- "detail"
- "episodes"
- "stream"

Setiap extension harus mengikuti kontrak yang sama dan menghasilkan response yang kompatibel dengan format "freepi.*.v1" yang digunakan aplikasi Android.

Buat dokumentasi API, contoh implementasi extension, validasi response, dan mekanisme error handling.

5. Manifest dan registrasi

Perbarui "manifest.json" agar mendukung sistem extension baru.

Pastikan manifest dapat mendeskripsikan:

- ID dan nama extension.
- Versi extension.
- Bahasa dan URL website.
- Path module.
- Runtime/API version.
- Dependency jika diperlukan.
- SHA-256 untuk verifikasi integritas.

Pastikan sistem registrasi tetap kompatibel dengan mekanisme verifikasi dan download module yang digunakan aplikasi.

6. Kompatibilitas Android

Ini sangat penting:

- Jangan mengubah UI aplikasi Android.
- Jangan merusak "RemoteSourceRuntime".
- Jangan mengubah kontrak normalized response secara sembarangan.
- Pertahankan mekanisme hash verification dan cache module.
- Pastikan extension baru dapat dimuat melalui runtime yang sudah tersedia.
- Jika perlu perubahan pada runtime, lakukan hanya jika benar-benar diperlukan dan jelaskan alasannya.

7. Testing

Setelah implementasi:

- Jalankan simulasi setiap operasi extension.
- Uji request dan parsing secara nyata.
- Uji pencarian dengan beberapa keyword.
- Uji detail, episode, dan stream jika tersedia.
- Validasi semua response terhadap schema.
- Pastikan tidak ada dependency Tachiyomi yang tertinggal.
- Pastikan source dapat berjalan secara independen.

Jangan hanya membuat mock test. Gunakan pengujian nyata terhadap website Movy jika dapat diakses.

8. Dokumentasi

Perbarui README dengan panduan:

- Cara kerja sistem extension.
- Struktur direktori.
- Standar API.
- Cara membuat source baru.
- Cara menambahkan extension ke manifest.
- Cara melakukan update extension.
- Contoh extension JavaScript sederhana.

Target akhir: "movie-source" menjadi repository extension JavaScript yang modular, seperti ekosistem Tachiyomi Extensions, tetapi tanpa build aplikasi. Menambahkan provider baru cukup dengan membuat folder extension dan file JavaScript, kemudian mendaftarkannya di manifest.

Kerjakan secara bertahap. Prioritaskan analisis arsitektur, lalu desain sistem, migrasi Movy, integrasi runtime, dan pengujian. Jangan menghapus fitur lama sebelum memastikan penggantinya berjalan.

9. Shared Extractor System (Penting)

Saya ingin memisahkan sistem extractor dari source extension.

Semua parser dan stream resolver yang bersifat umum harus disimpan di folder "extractors/" pada root repository, bukan di dalam folder masing-masing extension.

Tujuannya agar beberapa source bisa menggunakan extractor yang sama tanpa duplikasi kode.

Struktur yang diharapkan

movie-source/
├── manifest.json
├── extensions/
│   ├── movy/
│   │   ├── extension.js
│   │   └── config.json
│   ├── source-b/
│   │   ├── extension.js
│   │   └── filter.js
│   └── source-c/
│       └── extension.js
│
├── extractors/
│   ├── okru.js
│   ├── dailymotion.js
│   ├── p2p.js
│   └── index.js
│
├── shared/
├── runtime/
├── schemas/
└── README.md

Nama dan pembagian file dapat disesuaikan berdasarkan hasil analisis source yang tersedia.

Ketentuan Extractor

- Setiap extractor menangani satu jenis provider atau format stream.
- Extractor ditulis menggunakan JavaScript.
- Extractor harus independen dari extension tertentu.
- Satu extractor dapat digunakan oleh banyak extension.
- Mendukung konfigurasi dan parameter yang diperlukan.
- Menghasilkan format stream normalized yang konsisten.
- Memiliki error handling dan logging yang jelas.
- Hindari duplikasi parser atau resolver.

Prioritaskan identifikasi dan migrasi parser yang sudah tersedia di project. Contohnya OK.ru, Dailymotion, P2P, dan provider lainnya jika memang ditemukan.

Integrasi Extension dengan Extractor

Extension tidak perlu mengimplementasikan ulang parser stream.

Buat mekanisme agar extension bisa memanggil shared extractor melalui API atau registry.

Contoh konseptual:

export async function getStream(url) {
  return extractors.resolve(url);
}

Registry harus bisa mengenali extractor yang sesuai berdasarkan provider atau URL.

Jika sebuah source memiliki stream custom yang tidak didukung shared extractor, extension tetap boleh memiliki implementasi khusus.

Dependency dan Runtime

Analisis bagaimana aplikasi Android saat ini memuat module JavaScript.

Pastikan shared extractor juga dapat digunakan oleh extension melalui mekanisme dependency/import yang kompatibel dengan "RemoteSourceRuntime".

Jangan mengasumsikan bahwa native JavaScript import atau Node.js module system otomatis tersedia di WebView.

Jika diperlukan, rancang dependency bundling atau module loading yang sesuai dengan runtime saat ini.

Pastikan seluruh module yang dibutuhkan tetap terdaftar, diverifikasi SHA-256, dan dapat di-cache dengan aman.

Pengujian

- Uji setiap extractor secara independen.
- Uji pemanggilan extractor dari beberapa extension.
- Pastikan satu extractor dapat digunakan oleh lebih dari satu source.
- Uji URL provider yang valid dan tidak valid.
- Pastikan kegagalan extractor tidak merusak extension lainnya.

Prinsip utama: Extension bertanggung jawab atas katalog dan navigasi website, sedangkan Extractor bertanggung jawab atas resolusi stream dari provider eksternal. Keduanya merupakan komponen terpisah yang bisa digunakan kembali.
