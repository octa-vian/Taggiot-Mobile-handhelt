package com.taggiot.handheld.data.db;

import androidx.lifecycle.LiveData;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@androidx.room.Dao
public interface AppDao {

    // ---------- outbox ----------
    @Insert(onConflict = OnConflictStrategy.REPLACE) void insertOutbox(OutboxEntity o);
    @Update void updateOutbox(OutboxEntity o);
    @Query("SELECT * FROM outbox ORDER BY CASE status WHEN 'SENT' THEN 1 ELSE 0 END, createdAt DESC LIMIT 50") LiveData<List<OutboxEntity>> outboxLive();
    @Query("SELECT * FROM outbox WHERE status != 'SENT' ORDER BY createdAt") List<OutboxEntity> pendingOutbox();
    @Query("SELECT COUNT(*) FROM outbox WHERE status != 'SENT'") LiveData<Integer> pendingCountLive();
    @Query("SELECT COALESCE(SUM(rows), 0) FROM outbox WHERE status != 'SENT'") LiveData<Integer> pendingRowsLive();
    @Query("SELECT COUNT(*) FROM outbox WHERE status = 'PENDING' AND countId = :countId") int pendingBatches(String countId);
    @Query("SELECT COUNT(*) FROM outbox WHERE status = 'PENDING' AND countId = :countId") LiveData<Integer> pendingBatchesLive(String countId);
    @Query("SELECT COUNT(*) FROM outbox WHERE status = 'FAILED' AND countId = :countId") int failedBatches(String countId);
    @Query("DELETE FROM outbox WHERE status = 'SENT' AND sentAt < :before") void purgeSent(long before);

    // ---------- count reads ----------
    @Insert(onConflict = OnConflictStrategy.IGNORE) long insertRead(CountReadEntity r);
    @Query("SELECT * FROM count_read WHERE sessionId = :sessionId AND batchId IS NULL ORDER BY id") List<CountReadEntity> unbatchedReads(String sessionId);
    @Query("UPDATE count_read SET batchId = :batchId WHERE id IN (:ids)") void markBatched(List<Long> ids, String batchId);
    @Query("SELECT COUNT(*) FROM count_read WHERE sessionId = :sessionId") int distinctReads(String sessionId);
    @Query("SELECT COUNT(*) FROM count_read WHERE sessionId = :sessionId AND batchId IS NULL") int unbatchedCount(String sessionId);
    @Query("SELECT epc FROM count_read WHERE sessionId = :sessionId") List<String> readEpcs(String sessionId);
    @Query("DELETE FROM count_read WHERE sessionId = :sessionId") void deleteReads(String sessionId);
    @Query("DELETE FROM count_read WHERE seenAt < :before") void purgeReads(long before);

    // ---------- count sessions (cache) ----------
    @Insert(onConflict = OnConflictStrategy.REPLACE) void upsertSession(CountSessionEntity s);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void upsertSessions(List<CountSessionEntity> s);
    @Query("SELECT * FROM count_session WHERE id = :id") CountSessionEntity session(String id);
    @Query("SELECT * FROM count_session WHERE id = :id") LiveData<CountSessionEntity> sessionLive(String id);
    @Query("SELECT * FROM count_session ORDER BY CASE status WHEN 'open' THEN 0 WHEN 'review' THEN 1 ELSE 2 END, updatedAt DESC") LiveData<List<CountSessionEntity>> sessionsLive();
    @Query("SELECT COUNT(*) FROM count_session WHERE status = 'open'") LiveData<Integer> openSessionCountLive();
    @Query("SELECT COUNT(*) FROM count_session WHERE status = 'open'") int openSessionCount();
    @Query("DELETE FROM count_session WHERE id NOT IN (:keep)") void deleteSessionsExcept(List<String> keep);
    @Query("DELETE FROM count_session") void clearSessions();
}
