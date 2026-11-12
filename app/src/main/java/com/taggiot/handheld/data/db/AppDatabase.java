package com.taggiot.handheld.data.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * Database lokal: antrean keluar, EPC terbaca per sesi, salinan sesi hitung.
 * Tidak ada master data lokal: Site Gateway adalah sumber kebenaran (finder, counts).
 * Produksi: pertimbangkan SQLCipher dengan kunci di Android Keystore (EPC adalah data khusus staf).
 */
@Database(entities = {OutboxEntity.class, CountReadEntity.class, CountSessionEntity.class}, version = 2, exportSchema = true)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase instance;

    public abstract AppDao dao();

    public static AppDatabase get(Context ctx) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(ctx.getApplicationContext(), AppDatabase.class, "taggiot_handheld.db")
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return instance;
    }
}
