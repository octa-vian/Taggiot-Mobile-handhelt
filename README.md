# Taggiot Handheld — aplikasi Android (Java native) untuk GMF Tool Control

Aplikasi handheld UHF RFID untuk staf tool crib GMF: **cari alat** (RSSI homing), **drawer check**,
**inventory count / stock opname**. Aplikasi hanya bicara ke **Site Gateway handheld API**
(`POST https://<site>/api/v1/handheld/...`). Hasilnya sampai ke aplikasi GMF lewat webhook
`count_review` dari Site Gateway; handheld tidak pernah memanggil GMF dan tidak pernah mengubah status tag.

Acuan: *Taggiot Handheld API v1.0* (`Open-API-Handheld.pdf`, dari `api/site/openapi.yaml`) dan
*GMF Tool Control: RFID Handheld Developer Handover* (10 Nov 2026).

## Layar

| Layar | Kelas | Peran | Panggilan Site Gateway |
| --- | --- | --- | --- |
| Pairing (sekali) | `ui/pair/PairActivity` | admin site memberi kode | `pair` → device key di Android Keystore |
| Masuk | `ui/login/LoginActivity` | semua | `login` (header `X-Handheld-Key`), `me` |
| Beranda | `ui/home/HomeActivity` | semua | menu sesuai peran, masa berlaku token |
| Cari Alat | `ui/find/FindTagActivity` | viewer+ | `finder/epc`, `finder/sku`, `finder/products` |
| Scan bebas | `ui/inventory/InventoryActivity` | viewer+ | `finder/lookup` (≤ 200 EPC), tampil EPC besar + Salin untuk binding di GMF |
| Drawer check & hitung | `ui/count/CountListActivity` | operator+ | `counts/list`, `counts/create` |
| Sesi hitung | `ui/count/CountSessionActivity` | operator+ | `counts/get`, `counts/reads` (lewat antrean), `counts/close`/`cancel` |
| Review | `ui/count/ReviewActivity` | supervisor | `counts/review`, `differences`, `resolve`, `reopen`, `close` |
| Sinkron | `ui/sync/SyncActivity` | semua | antrean batch, status kirim |
| Profil | `ui/settings/SettingsActivity` | semua (perangkat: supervisor) | `pin`, `logout`, pasangkan ulang |
| Bind tag | `ui/encode/EncodeActivity` | supervisor, **site non-GMF saja** | `items/bind` + `Idempotency-Key` |

## Aturan yang diterapkan

- **HTTPS saja.** URL `http://` ditolak sebelum PIN dikirim (`ApiClient` + `network_security_config`).
- **Rahasia.** Device key dan token disimpan terenkripsi AES-GCM dengan kunci Android Keystore
  (`security/`). Uninstall menghapus kunci → pairing baru wajib. PIN tidak pernah disimpan.
  Log tidak memuat EPC, PIN, token, atau device key (header Authorization/X-Handheld-Key diredaksi).
- **EPC.** Semua EPC di-trim, di-uppercase, dan dicek `^([0-9A-F]{4}){1,31}$` sebelum panggilan apa pun (`domain/Epc`).
- **Login.** PIN salah dan operator tidak dikenal tampak sama; 429 menampilkan waktu tunggu dari `Retry-After`.
- **Peran.** viewer: cari & scan. operator: hitung. supervisor: review, resolve, reopen, close, cancel.
- **Site GMF** (default aktif, Profil → *Site GMF*): bind/replace/encode disembunyikan; binding alat dilakukan di aplikasi GMF.
- **Drawer count.** Daya penuh, EPC unik per sesi disimpan lokal (`count_read`), dibungkus batch ≤ 1.000 dengan
  `batch_id` UUID baru tiap 500 EPC atau 5 detik, dikirim `SyncWorker`. Batch bertahan saat aplikasi ditutup.
  Kirim ulang batch yang sama → server menjawab `replayed: true`, tidak ganda.
- **401 unauthenticated.** Token dibuang, antrean ditahan, operator masuk lagi (nomor operator terisi),
  lalu antrean dikirim ulang dengan id yang sama. `409 count_state` dan 4xx lain → batch “Ditolak”.
- **Review** hanya setelah semua batch sesi terkirim, supaya hasil `count_review` ke GMF lengkap.
  Alat `released` (dipinjam) yang tercatat hilang diberi keterangan “cek pinjaman di GMF”.
- **Area = kode laci GMF** apa adanya (mis. `D3`). Scan barcode label laci di daftar membuka sesi laci itu.

## Mode demo (tanpa Site Gateway dan tanpa perangkat)

`net/DemoGateway` adalah Site Gateway tiruan di dalam aplikasi dengan aturan yang sama (pairing sekali pakai,
kunci 15 menit, token 12 jam, peran, `batch_id` idempoten, status open → review → closed, nomor `SO-/IC-yyyymmdd-nnn`).
Reader simulasi dipakai otomatis bila SDK reader tidak ada.

- Kode pairing `DEMO1234`. Operator `1042` (operator), `2001` (supervisor), `3003` (viewer), PIN `123456`.
- Laci D1–D4 berisi alat contoh `GMF-TL-0001…0018`. Drawer check **D3** sudah terbuka (“dibuat GMF”);
  reader simulasi: 1 alat hilang, 1 alat nyasar dari D1, 1 tag tidak dikenal. `GMF-TL-0005` berstatus `released`.

## Menjalankan

```
JAVA_HOME=~/Library/Java/JavaVirtualMachines/jbr-17.0.9/Contents/Home ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Server nyata: matikan *Mode demo* di layar pairing, isi `https://rfid.taggiot.com/` (demo RFID), masukkan kode pairing
dari owner (`/handhelds/create`). Akun operator dan supervisor dibuat owner lewat `/operators/create`.
**Jangan uji ke GMF live.**

## Uji end-to-end (handover)

1. GMF demo: tambah 3 alat dengan EPC dan laci `D3`, lalu Start drawer check `D3` (catat `SO-…`).
2. Handheld: masuk sebagai operator → Drawer check → buka `SO-…` laci D3 → baca 2 dari 3 tag.
3. Matikan Wi-Fi, baca lagi, nyalakan: antrean terkirim sekali (`replayed` atau tanpa duplikat).
4. Masuk sebagai supervisor → Review: 1 hilang. GMF menampilkan 2/3 dengan nama alat yang hilang.
5. Tutup sesi dengan komentar. Cari Alat: cari alat yang hilang, ikuti bar, status `active`.

## Belum / perlu dicek ke tim RFID

- **`area` di counts/create dan di jawaban sesi** ada di handover tetapi tidak ada di API reference PDF v1.0.
  Aplikasi mengirim `area` dan, bila jawaban tidak memuatnya, memakai `tally.by_area` atau nilai lokal.
- **Pencarian `item_ref`** (kode alat GMF) di finder sudah diputuskan 2026-11-10 tetapi endpoint-nya belum ada.
  Sementara Cari Alat memakai `finder/sku` lalu `finder/products`; field `item_ref` sudah dibaca bila dikirim server.
- Model handheld final belum dipilih; adaptor SEUIC (`src/seuic`) tetap ada, driver Chainway/Zebra ditambah di `reader/`.
- TLS di LAN GMF; root CA Taggiot bisa ditambahkan di `res/raw` + `network_security_config`.
- Database lokal belum terenkripsi (SQLCipher) meski EPC data khusus staf.
