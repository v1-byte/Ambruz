# Struktur ZIP ekspor Ambrus (= kerangka web-copier-analyzer-android)

```
web-copier-analyzer-android/
├── README.md
├── LICENSES.md
├── .gitignore
├── docs/          (website-map, technology-report, asset-inventory, ...)
├── web/           (index, pages/, components/, assets/{css,js,images,...})
├── games/         (catalog.json, game-001/, shared/, engine-adapters/)
├── api/           (openapi.yaml, schemas/, mock-responses/)
├── worker/        (src/, migrations/, wrangler.toml)
├── database/      (schema.sql, seed.sql, ERD.md)
├── storage/
├── android-app/
├── analyzer/      (page/asset/api inspectors, reports/)
├── tests/
├── scripts/       (validate-project.sh, build-zip.sh)
└── checksums/SHA256SUMS.txt
```

Resource hasil scan dipetakan ke `web/assets/...` atau `api/mock-responses/`.
