# Status implementasi Ambruz

**Versi app:** 0.1.0-m1  
**Tanggal scaffold:** 2026-10-13

## Milestone

| Milestone | Status | Keterangan |
|-----------|--------|------------|
| M1 Fondasi APK | **Partial** | Kotlin project, WebView browser, validasi URL, model proyek |
| M2 Dashboard UI | **Partial** | Main dashboard, list proyek, scan form |
| M3 Resource Analyzer | **Partial** | Ekstraksi HTML → CSS/JS/img/link |
| M4 Domain & Server | **Partial** | Hostname map + header observation |
| M5 API & Dependency | **Partial** | Regex endpoint + dependency edges |
| M6 Resource Collector | Planned | Unduh file lokal belum penuh |
| M7 ZIP Export | **Partial** | Manifest + laporan + checksum |
| M8 Website Editor | Planned | |
| M9 API Migration | Planned | Spec di master plan |
| M10 Backend CF | **Stub** | worker.mjs health + projects stub |
| M11 Hosting | Planned | |
| M12 Game Analyzer | **Stub** | Inventaris aset + disclaimer |
| M13 Final testing | Planned | Unit test UrlGuard ada |

## Prinsip yang ditegakkan di kode

- Hanya HTTPS; blokir localhost / IP privat (UrlGuard).
- Level akses A/B/C tercatat di model.
- Limitations selalu ditulis di hasil scan.
- Tidak mengklaim backend privat tersalin dari URL publik.
- Token/secret tidak di-bundle di APK.

## Cara buka di Android Studio

1. Clone repo
2. Open folder `Ambruz` di Android Studio Hedgehog+
3. Sync Gradle (butuh JDK 17)
4. Run app debug di emulator/perangkat

## Worker

```bash
cd worker && npm i && npx wrangler deploy
```

## UI shell (2026-10-13)

- Tampilan utama APK = WebView `assets/ambrus/` (index.html + style.css + app.js)
- Logo utama: `assets/ambrus/logo.png` + `@mipmap/ic_launcher`
- Bridge native: `window.Android` (startScan, stopScan, openUrl, saveZip)
- Tanpa bridge di browser biasa → mode DEMO
