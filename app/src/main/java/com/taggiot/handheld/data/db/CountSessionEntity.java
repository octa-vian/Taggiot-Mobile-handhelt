package com.taggiot.handheld.data.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Salinan lokal sesi hitung dari Site Gateway (untuk daftar & layar hitung saat offline). json = CountSession utuh. */
@Entity(tableName = "count_session", indices = {@Index("status"), @Index("updatedAt")})
public class CountSessionEntity {
    @PrimaryKey @NonNull public String id = "";
    @NonNull public String number = "";
    @NonNull public String kind = "inventory";
    @NonNull public String status = "open";
    public String area;
    public String createdBy;
    @NonNull public String json = "{}";
    public long updatedAt;
}
