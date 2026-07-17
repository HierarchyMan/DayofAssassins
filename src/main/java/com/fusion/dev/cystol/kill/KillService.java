package com.fusion.dev.cystol.kill;

import com.fusion.dev.cystol.storage.KillRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-memory kill table is the source of truth. Disk is a durable mirror drained on a
 * single writer thread so combat never blocks on SQLite.
 */
public final class KillService {

    private final KillRepository repository;
    private final Logger logger;
    private final ConcurrentHashMap<UUID, DenseRanking.KillRecord> kills = new ConcurrentHashMap<>();
    private final ExecutorService writer;
    private final AtomicBoolean rankingDirty = new AtomicBoolean(true);
    private volatile List<DenseRanking.Entry> rankingCache = List.of();
    private volatile DenseRanking.Entry topCache;

    public KillService(KillRepository repository, Logger logger) {
        this.repository = repository;
        this.logger = logger;
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "DayOfAssassins-KillIO");
            t.setDaemon(true);
            return t;
        };
        this.writer = Executors.newSingleThreadExecutor(factory);
    }

    public void load() {
        try {
            kills.clear();
            kills.putAll(repository.loadAll());
            markRankingDirty();
            refreshRankingCache();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to load kills", e);
        }
    }

    public int getKills(UUID uuid) {
        DenseRanking.KillRecord r = kills.get(uuid);
        return r == null ? 0 : r.kills();
    }

    public void creditKill(UUID killer, String killerName) {
        kills.compute(killer, (id, prev) -> {
            int next = (prev == null ? 0 : prev.kills()) + 1;
            String name = killerName != null ? killerName : (prev != null ? prev.name() : "Unknown");
            DenseRanking.KillRecord record = new DenseRanking.KillRecord(id, name, next);
            enqueueUpsert(record);
            return record;
        });
        markRankingDirty();
    }

    public void setName(UUID uuid, String name) {
        kills.computeIfPresent(uuid, (id, prev) -> {
            DenseRanking.KillRecord record = new DenseRanking.KillRecord(id, name, prev.kills());
            enqueueUpsert(record);
            return record;
        });
        markRankingDirty();
    }

    private void enqueueUpsert(DenseRanking.KillRecord record) {
        writer.execute(() -> {
            try {
                repository.upsert(record.uuid(), record.name(), record.kills());
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to persist kill for " + record.uuid(), e);
            }
        });
    }

    private void markRankingDirty() {
        rankingDirty.set(true);
    }

    /**
     * Dense ranking of everyone with a kill row. Cached until kills change.
     */
    public List<DenseRanking.Entry> ranking() {
        if (rankingDirty.get()) {
            refreshRankingCache();
        }
        return rankingCache;
    }

    public Optional<DenseRanking.Entry> topKiller() {
        if (rankingDirty.get()) {
            refreshRankingCache();
        }
        return Optional.ofNullable(topCache);
    }

    private synchronized void refreshRankingCache() {
        // Always recompute when called under dirty flag; concurrent credits may set dirty again after.
        List<DenseRanking.Entry> ranked = DenseRanking.rank(new ArrayList<>(kills.values()));
        rankingCache = List.copyOf(ranked);
        topCache = ranked.isEmpty() ? null : ranked.getFirst();
        rankingDirty.set(false);
    }

    public Map<UUID, DenseRanking.KillRecord> snapshot() {
        return Map.copyOf(kills);
    }

    public void clearAll() {
        kills.clear();
        markRankingDirty();
        refreshRankingCache();
        writer.execute(() -> {
            try {
                repository.clear();
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to clear kills", e);
            }
        });
    }

    /**
     * Block until pending writes finish and the latest memory snapshot is on disk.
     * Call from plugin disable on the main thread.
     */
    public void shutdown() {
        Map<UUID, DenseRanking.KillRecord> snap = snapshot();
        writer.execute(() -> {
            for (DenseRanking.KillRecord record : snap.values()) {
                try {
                    repository.upsert(record.uuid(), record.name(), record.kills());
                } catch (SQLException e) {
                    logger.log(Level.SEVERE, "Failed final kill flush for " + record.uuid(), e);
                }
            }
        });
        writer.shutdown();
        try {
            if (!writer.awaitTermination(8, TimeUnit.SECONDS)) {
                logger.warning("Kill IO writer did not finish in time; forcing shutdown");
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }
}
