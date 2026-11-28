# Sinkronisasi movie-source — 28 November 2026

## Status

Loader, routing, pemilihan source dan shared extractor disinkronkan dengan implementasi
`HamzahSk/movie-source` commit `0db097c80d64ac0218a236073cadff3e8384eb8f`.
Manifest: schema/runtime v1, registry v2.0.0, 3 source, 11 descriptor extractor/helper,
14 module. Tidak ada perubahan pada repository upstream atau kontrak `freepi.*.v1`.

Verifikasi penuh playback live **belum tercapai**: LK21 dan Videasy diblokir HTTP 403;
pencarian MovieBox mengembalikan HTTP 400 (`PARAMS_ERROR: invalid token`), dan playback
judul MovieBox yang diuji memerlukan persetujuan/interaksi. Test perangkat Android
belum dieksekusi karena tidak tersedia device/emulator. Hasil fixture tidak dianggap
sebagai bukti playback live.

## Hasil audit implementasi

- Bundle `extensions/<id>/extension.js` adalah IIFE hasil esbuild; entry `index.js`
  memakai ESM hanya pada build. WebView menjalankan export `MovieSources[runtime.export]`.
  Tidak ada satu catalog export untuk semua source.
- `shared/runtime.js` menyediakan HTTP Promise di atas bridge sinkron `MovieSourceHttp`.
  Registry berada di `FreepiExtractors`, dengan helper shared, playlist dan video-node.
  Videasy menjalankan seed/sources/decryption/playlist dan cache JavaScript sendiri.
  P2P/Hydrax menerima payload; helper video-node memvalidasi handshake. OK.ru dan
  Dailymotion melakukan request metadata; Direct menormalisasi URL media.
- LK21: home/search/list/detail/stream, tanpa operasi episodes. Detail memuat seasons.
  Implementasi stream sengaja melempar `ACCESS_BLOCKED` dan meminta pipeline browser host.
- MovieBox: keenam operasi, API katalog dan Nuxt/devalue untuk detail, cache detailPath
  di Map/localStorage. Filter dan pagination memakai `moviebox://filter`/`search`.
  Operasi list membutuhkan parameter terurai, bukan sekadar `{url: ...}`.
- Movy: keenam operasi, slug `movy:movie:<id>` atau `movy:tv:<id>:s<n>:e<n>`,
  kategori berupa URL sintetis. Stream memakai shared Videasy. TV hub tidak memiliki
  sumber playback; episode memiliki server.
- Masalah upstream tambahan: `moviebox.episodes` menghasilkan season metadata
  (`season`, `episode_count`, `episode_numbers`), sementara schema episodes upstream
  mensyaratkan `number` dan `episodes`. Detail MovieBox sudah menghasilkan bentuk
  seasons canonical dan digunakan UI Android; raw operasi episodes tetap dieksekusi
  tanpa mengklaim bahwa hasilnya lolos schema canonical.
- Manifest terbaru tidak lagi menyediakan `sources[].scripts` atau browser stream
  observer lama. `ScrapeScriptLoader` sekarang mempertahankan registry/script legacy
  yang sudah di-cache, bukan menimpanya dengan manifest module-only. Pada instalasi
  bersih, observer legacy tidak dapat diunduh dari manifest ini. HTTP/API pipeline
  LK21 dan shared extractor tetap dipertahankan. Dukungan penuh provider dinamis
  membutuhkan observer host yang kompatibel dari upstream, bukan parser baru Android.

## Perubahan aplikasi

- Store mengunduh dan memverifikasi semua module, menggunakan manifest baseUrl,
  menolak dependency hilang/siklus/path invalid, memeriksa kecocokan entry/module,
  serta mengurutkan dan menghapus duplikasi dependency. Update aktif dikomit setelah
  seluruh hash valid. Update gagal memakai snapshot terakhir yang diverifikasi;
  status kegagalan tersedia untuk Settings. Cache berdasarkan hash dipakai ulang.
- Identitas runtime memakai source dan SHA-256 bundle, sehingga pergantian extension
  memuat dokumen baru. Initialization termasuk dalam Promise error handling.
  Timeout/cancellation membatalkan HTTP aktif dan membuang dokumen yang belum selesai.
  Timeout internal menjadi ScrapeTimeoutException; cancellation pemanggil diteruskan.
  Network WebView langsung diblokir; HTTP extension melalui native bridge, dengan
  batas body, method dan content-type. Logging mencatat source/operasi/status tanpa
  teks console atau kredensial remote.
- Shared extractor dieksekusi dari JavaScript upstream. LK21 mencoba registry bersama
  sebelum fallback pipeline yang sudah ada. MovieBox/Movy memakai stream export sendiri.
  Tidak ada implementasi parser extractor baru di kode produksi Android.
- Source ID berasal dari manifest, termasuk Movy; nama/versi/bahasa/website tampil di
  Settings. Initial sync dan refresh tersedia. Source yang dihapus dari registry
  diganti dengan source tersedia. Cache katalog dibersihkan saat pemilihan/refresh;
  Room Favorites, History dan Watch Progress tidak dihapus atau dimigrasi.
- Navigasi extension dibedakan dari URL network/media. Parameter MovieBox filter dan
  search diteruskan sesuai export. Mapper mempertahankan slug/backdrop detail dan
  sourceId pada server. Key detail single-flight mencakup source. Request katalog
  terikat ke source yang dipilih saat request dimulai. Shortcut LK21 tidak ditampilkan
  untuk extension lain; kategori remote tetap ditampilkan sesuai data source.

## File yang diubah/ditambahkan

Path Kotlin di bawah relatif terhadap `app/src/main/java/rofil/movie/app/`.

| File | Perubahan |
| --- | --- |
| `data/scrape/SourceModuleStore.kt` | Validasi graph/entry/baseUrl, semua hash, update atomic dan snapshot fallback |
| `data/scrape/RemoteSourceRuntime.kt` | Independent bundle/export, registry execution, bridge dan cleanup |
| `data/scrape/RemoteCatalogDataSource.kt` | Extension routes, identitas detail/server, stream/shared adapter |
| `data/scrape/ExtensionRoutes.kt` (baru) | Token navigasi katalog dan query arguments |
| `data/scrape/ExtensionStreamProvider.kt` (baru) | Routing extension/shared/legacy playback |
| `data/scrape/ScrapeScriptLoader.kt` | Shared verified module store dan pelestarian cache legacy |
| `data/scrape/ScrapeValidator.kt` | Navigasi katalog dan error NETWORK |
| `data/scrape/ScrapeMapper.kt` | Pagination token, slug dan backdrop |
| `data/SourceSelection.kt` | Hapus whitelist source lokal |
| `data/MovieRepository.kt` | Source-bound adapters, namespace cache, fallback khusus LK21 |
| `MovieApp.kt` | Wiring store/runtime/catalog/stream |
| `ui/AppRoot.kt` | Initial/forced sync, daftar manifest, status snapshot dan metadata |
| `ui/screens/SettingsScreen.kt` | Metadata source |
| `ui/screens/HomeScreen.kt`, `BrowseScreen.kt` | Kategori yang tersedia dan shortcut sesuai source |
| `app/build.gradle.kts` | Assets snapshot untuk instrumentation tests saja |
| `gradlew` | Permission executable agar perintah wajib dapat dijalankan |
| `app/src/test/java/.../scrape/SourceModuleStoreTest.kt` (baru) | Integration store dengan snapshot upstream asli |
| `app/src/test/java/.../scrape/ExtensionCompatibilityTest.kt` (baru) | Routes, identitas series dan error mapping |
| `app/src/androidTest/java/.../RemoteExtensionRuntimeTest.kt` (baru) | Native WebView + bridge, source switching dan registry |
| `app/src/test/resources/extensions/**` (baru) | Snapshot upstream berisi manifest, 14 module, fixture dan revision |
| `tools/extension-integration/**` (baru) | Harness Chromium, package/lockfile dan ignore node_modules |
| `docs/MOVIE_SOURCE_SYNC.md` (baru) | Laporan ini |

## Pengujian

| Pengujian | Hasil |
| --- | --- |
| `./gradlew testDebugUnitTest` | **102 test: 96 lolos, 6 gagal pre-existing** |
| `./gradlew testDebugUnitTest --tests 'rofil.movie.app.data.scrape.*'` | **33 test lolos**, termasuk semua test store/compatibility baru |
| `./gradlew assembleDebug` | **Berhasil** |
| `./gradlew assembleDebugAndroidTest` | **Berhasil**, APK test dikompilasi; belum dieksekusi pada perangkat |
| `npm test --prefix tools/extension-integration` | **Lolos**: Chromium, 78 request fixture via bridge sinkron |
| `npm run test:live --prefix tools/extension-integration` | Download manifest + semua 14 module dan SHA-256 **lolos**; fixture lolos; operasi live dicatat terpisah di bawah |
| Upstream `npm test` pada clone asli | **Lolos**, manifest/schema/bundle/shared registry |
| Upstream `npm run verify:live` | Movy catalog/search/movie/TV/episode lolos, MovieBox home/list/detail lolos; stream Movy dilaporkan diblokir |
| `git diff --check` | **Lolos** |

Enam kegagalan suite penuh direproduksi pada checkout HEAD awal tanpa perubahan,
dengan `testDebugUnitTest --tests '*PlayerExtractionTest' --tests '*RecursivePlayerTest'`:
`capturedPopupExtractsActualPostAndDynamicValues`, `malformedApiFailsAtApiStage`,
`stagesPreserveRequestAncestryAndValidateManifest`, `providerFetchFailureRetainsProviderStage`,
`actualAccessibleSnapshotsDoNotInventHls`, dan
`capturedPlaycdnApiAndInitializationResourcesAreNotMedia`.
Penyebabnya adalah capture eksternal `lk21-request/[84]`, `[148]`, `[150]` yang tidak
ada di checkout. Test tidak dihapus, dilewati, atau diganti dengan capture buatan.

Store tests menggunakan MockWebServer untuk menyajikan **manifest dan module asli**:
verifikasi semua SHA-256, urutan/dedup dependency, reuse cache, upgrade version/hash,
restart, network 503, hash salah, graph siklik/dependency hilang, entry/path/schema
invalid, snapshot fallback dan penolakan cache rusak.

Chromium menjalankan seluruh operasi yang dideklarasikan ketiga export, detail movie/
series/episode, shared Videasy resolution, P2P/Hydrax payload, OK.ru, Dailymotion,
Direct, error extractor diikuti request sukses, serta urutan pergantian
LK21 → MovieBox → Movy → LK21 → Movy. Bundle tidak mendapat native fetch/require/ESM;
transport fixture memakai XHR sinkron yang meniru bridge native. Test Android WebView
sebenarnya disediakan terpisah; Chromium tidak diklaim menggantikan test perangkat.

### Hasil operasi live melalui bundle asli di Chromium

| Source | Hasil |
| --- | --- |
| LK21 | Home/search/list/detail HTTP **403**. Stream mengembalikan penolakan browser pipeline sesuai implementasi. |
| MovieBox | Home/list/movie detail/series detail/episodes **berhasil**. Search HTTP **400**, `invalid token`. Stream judul sampel **ACCESS_BLOCKED**, membutuhkan persetujuan/interaksi. |
| Movy | Home/search/list/movie detail/series detail/episodes **berhasil**. Upstream live suite juga memverifikasi episode detail (5 seasons, 62 episodes). Stream Miami **ACCESS_BLOCKED**, Videasy seed HTTP **403**. |

Tidak ada klaim playback live ExoPlayer atau keberhasilan operasi yang diblokir.
Snapshot test JavaScript hanya masuk resources/test APK; production APK tetap
mengambil extension, dependency dan extractor dari repository remote.

## Menjalankan ulang

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug assembleDebugAndroidTest
npm ci --prefix tools/extension-integration
npm test --prefix tools/extension-integration
npm run test:live --prefix tools/extension-integration
# Default /usr/bin/chromium; override dengan CHROMIUM_PATH bila diperlukan.
# Pada device/emulator:
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=rofil.movie.app.RemoteExtensionRuntimeTest
```

Output probe/operasi live tersimpan di `app/build/extension-live-report.json`.
`REVISION` mengikat snapshot dan unduhan integrasi pada revision yang diaudit;
production tetap mengambil manifest `main` dan mengikuti update/hash terbarunya.
