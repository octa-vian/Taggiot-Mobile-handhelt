package com.taggiot.handheld.data.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * EPC yang sudah terbaca handheld ini untuk satu sesi hitung (dedup lokal: satu EPC dihitung sekali
 * per sesi, sama dengan aturan server). batchId null = belum dimasukkan ke batch outbox.
 */
@Entity(tableName = "count_read",
        indices = {@Index(value = {"sessionId", "epc"}, unique = true), @Index(value = {"sessionId", "batchId"})})
public class CountReadEntity {
    @PrimaryKey(autoGenerate = true) public long id;
    @NonNull public String sessionId = "";
    @NonNull public String epc = "";
    @NonNull public String area = "";
    public long seenAt;
    public String batchId;
}
