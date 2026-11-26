# Menjalankan free-pi di GitHub Actions

Workflow: `.github/workflows/free-pi.yml` (otomatis saat `prompt.md` berubah,
manual, atau bisa dipanggil workflow lain) dan
`.github/workflows/free-pi-pr-review.yml` (contoh: review PR otomatis).

## 1. Ambil token free-pi

Token ada di `~/.free-pi/agent/credentials.json` setelah kamu sign in di terminal.

```bash
node -e 'console.log(JSON.parse(require("fs").readFileSync(process.env.HOME+"/.free-pi/agent/credentials.json","utf8")).token)'
```

## 2. Simpan sebagai secret repo

```bash
gh secret set FREEPI_TOKEN --repo <user>/<repo> --body "<token-dari-langkah-1>"
```

Atau lewat UI: **Settings → Secrets and variables → Actions → New repository secret**,
nama harus `FREEPI_TOKEN`.

Kalau mau dipakai di banyak repo, bikin secret di level organisasi, atau pakai
`environment` secret.

## 3. Jalankan

### Otomatis lewat `prompt.md`

Ubah `prompt.md` di root repo, commit, lalu push ke branch mana pun. Isi file itu
dipakai langsung sebagai prompt (`push` dengan `paths: [prompt.md]`), jadi tidak
perlu membuka tab Actions.

- Hanya perubahan pada `prompt.md` yang memicu run; commit lain diabaikan.
- Run baru di branch yang sama membatalkan run lama yang masih memakai prompt
  versi sebelumnya (hemat allowance).
- `prompt.md` kosong = run langsung gagal dengan pesan jelas, bukan memanggil
  model dengan prompt kosong.
- Model default `deepseek-v4.1-flash`, thinking `low`, karena trigger push tidak
  punya input. Mau beda? Ganti nilai fallback di step `env:` pada workflow.

### Manual

UI: **Actions → free-pi → Run workflow**, biarkan kolom prompt kosong untuk
memakai `prompt.md`, atau isi untuk override sekali jalan.

Dari CLI:

```bash
gh workflow run free-pi.yml -f prompt="Ringkas isi repo ini" -f model=deepseek-v4.1-flash
gh run watch
```

Dari workflow lain:

```yaml
jobs:
  ai:
    uses: <user>/<repo>/.github/workflows/free-pi.yml@main
    with:
      prompt: "Periksa perubahan di branch ini dan laporkan risikonya"
      thinking: medium
    secrets:
      FREEPI_TOKEN: ${{ secrets.FREEPI_TOKEN }}
```

## Kenapa pakai `pi`, bukan `free-pi-cli`

`free-pi-cli` adalah aplikasi terminal interaktif: tidak ada mode `--print`, dan
perintahnya selalu membuka TUI, jadi menggantung di runner. Karena itu workflow
menjalankan `pi` (agent yang sama, yang dipakai free-pi di dalamnya) dengan
`models.json` yang menunjuk ke endpoint free-pi:

- base URL: `https://api.freepi.ai/v1` (OpenAI-compatible)
- API key: token free-pi kamu (`$FREEPI_TOKEN`, dibaca `models.json` saat request)
- model, kuota harian, dan credit: sama persis dengan pemakaian di terminal

Artinya pemakaian CI ikut memotong allowance harian akunmu. Satu run yang wajar
biasanya kecil (`ai/1.6` atau lebih untuk model non-free), tapi jangan pasang
jadwal `cron` ketat.

## Hal yang perlu diingat

- **Token kedaluwarsa.** Umurnya sekitar 3 bulan. Workflow mengecek `exp` dan
  gagal lebih awal dengan pesan jelas; perbarui secret dengan sign in ulang di
  terminal (`npx -y free-pi-cli@latest`).
- **Ads tidak tampil di CI.** Workflow mengirim header `x-ad-context: none`
  (opt-out), sama seperti free-pi saat tidak ada consent. Pemakaian tetap
  dihitung ke allowance/credit.
- **Terms.** Pemakaian lewat API adalah pemakaian akunmu; pastikan kamu sudah
  menerima ToS free-pi (https://freepi.ai/terms) saat sign in.
- **Jangan pakai di repo publik dengan PR dari fork** untuk hal sensitif:
  secret tidak dikirim ke fork, dan `pull_request_target` sengaja tidak dipakai di
  contoh ini.
- **Model pilihan.** Daftar model ada di `.github/free-pi/models.json`. Yang
  terbaik untuk CI umumnya `deepseek-v4.1-flash` dan `particle` (gratis). Kalau
  mau menambah model, salin id dari `https://api.freepi.ai/client-version`
  (`models[].id`) dan jangan lupa `contextWindow`-nya.
- **Mau model lain di luar daftar itu?** Tambahkan entri baru ke
  `models.json` dengan `"id": "<id-dari-catalog>"`; sisanya samakan.
- **Allowance harian.** Pemakaian CI ikut memotong allowance akunmu, termasuk
  run yang dipicu `prompt.md`, jadi jangan jadikan `prompt.md` tempat eksperimen
  commit berulang-ulang. Turunkan frekuensi push atau pakai `workflow_dispatch`
  saat sedang menguji.
- **Memori/langkah lama.** Runner dibatasi `timeout-minutes: 30`. Turunkan atau
  naikkan sesuai kebutuhan, dan pertimbangkan `--tools` untuk membatasi tool.
- **Trust proyek.** Workflow memakai `--no-approve`, jadi `AGENTS.md`, skill, dan
  extension dari folder repo tidak dimuat. Kalau kamu memang percaya repo
  sendiri, ganti dengan `--approve` di step "Run the agent".
