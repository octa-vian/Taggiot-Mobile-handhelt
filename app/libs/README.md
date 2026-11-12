# SDK SEUIC

Taruh file SDK UHF dari SEUIC (minta ke SEUIC / distributor sesuai model handheld) di folder ini:

- `uhf.jar` — API UHF (`com.seuic.uhf.UHFService`, `com.seuic.uhf.EPC`)

Bila `uhf.jar` ada, Gradle otomatis:
1. menambahkan `uhf.jar` sebagai dependency `compileOnly` (jar ini hanya stub; implementasi asli adalah
   system library `com.seuic.uhf` di firmware, jadi tidak boleh ikut dikemas ke APK),
2. ikut mengompilasi `app/src/seuic/java` (adaptor `SeuicUhfReader`),
3. mengisi `BuildConfig.HAS_SEUIC_SDK = true`.

Tanpa jar ini aplikasi tetap bisa dibangun dan berjalan dengan reader simulasi (`FakeUhfReader`),
misalnya di emulator.

Adaptor `app/src/seuic/java/com/taggiot/handheld/reader/seuic/SeuicUhfReader.java` sudah dicocokkan dengan
manual dan demo SDK serta diuji di SEUIC CRUISE2. Untuk SDK/model lain, cocokkan ulang signature method-nya.
