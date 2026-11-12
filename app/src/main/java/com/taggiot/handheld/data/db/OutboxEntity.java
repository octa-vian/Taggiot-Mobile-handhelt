package com.taggiot.handheld.data.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Antrean keluar: setiap unggahan disimpan di sini DULU, baru dikirim ke Site Gateway oleh SyncWorker.
 * READS: id = batch_id (UUID) di body → kirim ulang menjawab replayed:true, tidak menggandakan.
 * BIND : id = Idempotency-Key header → kirim ulang mengembalikan hasil pertama.
 * Baris bertahan saat aplikasi ditutup / restart (checklist: antrean tidak hilang).
 */
@Entity(tableName = "outbox", indices = {@Index("status"), @Index("countId")})
public class OutboxEntity {
    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";

    public static final String KIND_READS = "READS";
    public static final String KIND_BIND = "BIND";

    @PrimaryKey @NonNull public String id = "";
    @NonNull public String kind = KIND_READS;
    @NonNull public String title = "";
    @NonNull public String subtitle = "";
    public int rows;
    /** Path relatif base URL, mis. api/v1/handheld/counts/reads */
    @NonNull public String endpoint = "";
    /** Header Idempotency-Key (bind/replace); null untuk reads (memakai batch_id di body). */
    public String idempotencyKey;
    /** id sesi hitung untuk READS (agar tally bisa diperbarui dari jawaban). */
    public String countId;
    @NonNull public String payloadJson = "{}";
    public String responseJson;
    @NonNull public String status = PENDING;
    public int attempts;
    public String lastError;
    public long createdAt;
    public long sentAt;
}
