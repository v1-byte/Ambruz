# Ambruz — Web Copier & Analyzer (Android)

Aplikasi Android (Kotlin + WebView) untuk **analisis website yang diizinkan**, inventaris resource/domain/endpoint, ekspor ZIP, dan (bertahap) migrasi API + hosting ke Cloudflare.

> **Bukan** alat untuk mengambil alih atau menyalin backend layanan pihak ketiga tanpa izin.  
> Lihat tingkat akses Level A / B / C di master plan.

## Status

Scaffold **M1–M5 + M7 partial + Worker stub**. Detail: [docs/STATUS.md](docs/STATUS.md)  
Master plan: [docs/MASTER-PLAN-V2.txt](docs/MASTER-PLAN-V2.txt)

## Tampilan APK

UI utama = WebView memuat `app/src/main/assets/ambrus/` (HTML/CSS/JS).
Logo utama: `logo.png` + icon launcher `@mipmap/ic_launcher`.
Tanpa native bridge di browser → mode **DEMO**; di APK → badge **LIVE**.

## Fitur yang sudah ada di kode

- Validasi URL HTTPS + pencegahan SSRF dasar
- Scan halaman publik (status, header, cuplikan HTML)
- Resource analyzer (script, img, link, css)
- Domain & API endpoint inventory (dari konten yang terlihat)
- Dependency map sederhana
- Game resource inventory (disclaimer ketat)
- Simpan proyek lokal
- Ekspor ZIP (`project.json`, laporan, checksum)
- WebView browser terkendali
- Cloudflare Worker stub (`/api/health`, `/api/projects`)

## Belum

- Pengunduhan massal resource ke disk (M6 penuh)
- Editor website (M8)
- Adapter migrasi API end-to-end (M9)
- D1 persistence & auth (M10 penuh)
- Deploy hosting otomatis (M11)

## Struktur

```
Ambruz/
├── app/                 # Android application
├── worker/              # Cloudflare Worker backend
├── templates/zip-export/
├── docs/
│   ├── MASTER-PLAN-V2.txt
│   └── STATUS.md
└── README.md
```

## Build

Android Studio → Open project → Run.  
Min SDK 26, compile SDK 35, JDK 17.

## Lisensi & etika

Gunakan hanya pada properti yang Anda miliki atau yang Anda miliki izin tertulis untuk dianalisis/dibangun ulang.
