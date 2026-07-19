package com.fusion.dev.cystol.kill;

import com.fusion.dev.cystol.storage.KillRepository;
import com.fusion.dev.cystol.storage.PastGameRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
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
 * In-memory kill table is the source of truth for the <em>current</em> event.
 * Disk is a durable mirror drained on a single writer thread so combat never blocks on SQLite.
 *
 * <p>Live ranking uses competition places (first-to-reach). Ceremony / archive use dense places.
 * Past games are frozen snapshots with chronological ids 1, 2, 3…
 */
public final class KillService {

    private final KillRepository repository;
    private final PastGameRepository pastGames;
    private final Logger logger;
    private final ConcurrentHashMap<UUID, DenseRanking.KillRecord> kills = new ConcurrentHashMap<>();
    private final KillFarmGuard farmGuard = new KillFarmGuard();
    private final ExecutorService writer;
    private final AtomicBoolean rankingDirty = new AtomicBoolean(true);
    private volatile List<DenseRanking.Entry> liveRankingCache = List.of();
    private volatile List<DenseRanking.Entry> finalRankingCache = List.of();
    private volatile DenseRanking.Entry topCache;

    public KillService(KillRepository repository, PastGameRepository pastGames, Logger logger) {
        this.repository = repository;
        this.pastGames = pastGames;
        this.logger = logger;
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "DayOfAssassins-KillIO");
            t.setDaemon(true);
            return t;
        };
        this.writer = Executors.newSingleThreadExecutor(factory);
    }

    /** Tests / legacy: past-game archive disabled. */
    public KillService(KillRepository repository, Logger logger) {
        this(repository, null, logger);
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

    /** In-memory killer→victim anti-farm window (cleared with {@link #clearAll()}). */
    public KillFarmGuard farmGuard() {
        return farmGuard;
    }

    public void creditKill(UUID killer, String killerName) {
        long nowMs = System.currentTimeMillis();
        kills.compute(killer, (id, prev) -> {
            int next = (prev == null ? 0 : prev.kills()) + 1;
            String name = killerName != null ? killerName : (prev != null ? prev.name() : "Unknown");
            DenseRanking.KillRecord record = new DenseRanking.KillRecord(id, name, next, nowMs);
            enqueueUpsert(record);
            return record;
        });
        markRankingDirty();
    }

    /**
     * Staff absolute set of kill count. {@code 0} removes the row from live ranking + disk.
     * No schema change — uses existing upsert / delete by uuid.
     */
    public void setKills(UUID uuid, String name, int amount) {
        if (uuid == null) {
            return;
        }
        int killsAmount = Math.max(0, amount);
        if (killsAmount == 0) {
            kills.remove(uuid);
            markRankingDirty();
            writer.execute(() -> {
                try {
                    repository.delete(uuid);
                } catch (SQLException e) {
                    logger.log(Level.SEVERE, "Failed to delete kill row for " + uuid, e);
                }
            });
            return;
        }
        long nowMs = System.currentTimeMillis();
        String n = name != null && !name.isBlank() ? name : "Unknown";
        DenseRanking.KillRecord record = new DenseRanking.KillRecord(uuid, n, killsAmount, nowMs);
        kills.put(uuid, record);
        enqueueUpsert(record);
        markRankingDirty();
    }

    public void setName(UUID uuid, String name) {
        kills.computeIfPresent(uuid, (id, prev) -> {
            DenseRanking.KillRecord record = new DenseRanking.KillRecord(
                    id, name, prev.kills(), prev.reachedAtMs()
            );
            enqueueUpsert(record);
            return record;
        });
        markRankingDirty();
    }

    private void enqueueUpsert(DenseRanking.KillRecord record) {
        writer.execute(() -> {
            try {
                repository.upsert(record.uuid(), record.name(), record.kills(), record.reachedAtMs());
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to persist kill for " + record.uuid(), e);
            }
        });
    }

    private void markRankingDirty() {
        rankingDirty.set(true);
    }

    /**
     * Live leaderboard: competition places, first-to-reach order.
     * Cached until kills change.
     */
    public List<DenseRanking.Entry> ranking() {
        if (rankingDirty.get()) {
            refreshRankingCache();
        }
        return liveRankingCache;
    }

    /**
     * End-of-event dense ranking (ties share place). Use for ceremony, rewards, archive.
     */
    public List<DenseRanking.Entry> rankingFinal() {
        if (rankingDirty.get()) {
            refreshRankingCache();
        }
        return finalRankingCache;
    }

    public Optional<DenseRanking.Entry> topKiller() {
        if (rankingDirty.get()) {
            refreshRankingCache();
        }
        return Optional.ofNullable(topCache);
    }

    /**
     * First {@code n} live entries (competition order). Always at most {@code n} rows.
     */
    public List<DenseRanking.Entry> top(int n) {
        if (n <= 0) {
            return List.of();
        }
        List<DenseRanking.Entry> all = ranking();
        if (all.isEmpty()) {
            return List.of();
        }
        return all.subList(0, Math.min(n, all.size()));
    }

    private synchronized void refreshRankingCache() {
        List<DenseRanking.KillRecord> rows = new ArrayList<>(kills.values());
        List<DenseRanking.Entry> live = DenseRanking.rankCompetition(rows);
        List<DenseRanking.Entry> dens = DenseRanking.rankDense(rows);
        liveRankingCache = List.copyOf(live);
        finalRankingCache = List.copyOf(dens);
        topCache = live.isEmpty() ? null : live.getFirst();
        rankingDirty.set(false);
    }

    public Map<UUID, DenseRanking.KillRecord> snapshot() {
        return Map.copyOf(kills);
    }

    /**
     * Wipe live event kills (memory + disk). Call when a new hunt arm first opens —
     * does <strong>not</strong> delete past_games history.
     */
    public void clearAll() {
        kills.clear();
        farmGuard.clear();
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
     * Copy live scores into a new chronological past game (dense places), keep live table.
     *
     * @return new game id, or empty if no scores / archive unavailable
     */
    public OptionalInt archiveLiveToPastGame(long endedAtEpochSeconds) {
        if (pastGames == null) {
            logger.warning("Past game archive skipped — PastGameRepository not configured");
            return OptionalInt.empty();
        }
        List<DenseRanking.Entry> snapshot = rankingFinal();
        if (snapshot.isEmpty()) {
            logger.info("Past game archive skipped — no kill rows");
            return OptionalInt.empty();
        }
        try {
            int id = pastGames.insertGame(endedAtEpochSeconds, snapshot);
            logger.info("Archived " + snapshot.size() + " kill rows as past game #" + id);
            return OptionalInt.of(id);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to archive kills to past game", e);
            return OptionalInt.empty();
        }
    }

    public OptionalInt latestPastGameId() {
        if (pastGames == null) {
            return OptionalInt.empty();
        }
        try {
            return pastGames.latestGameId();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to read latest past game id", e);
            return OptionalInt.empty();
        }
    }

    public int pastGameCount() {
        if (pastGames == null) {
            return 0;
        }
        try {
            return pastGames.gameCount();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to count past games", e);
            return 0;
        }
    }

    public Optional<PastGameRepository.PastGame> pastGame(int gameId) {
        if (pastGames == null || gameId <= 0) {
            return Optional.empty();
        }
        try {
            return pastGames.loadGame(gameId);
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to load past game #" + gameId, e);
            return Optional.empty();
        }
    }

    public Optional<PastGameRepository.PastGame> latestPastGame() {
        if (pastGames == null) {
            return Optional.empty();
        }
        try {
            return pastGames.loadLatest();
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to load latest past game", e);
            return Optional.empty();
        }
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
                    repository.upsert(record.uuid(), record.name(), record.kills(), record.reachedAtMs());
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
