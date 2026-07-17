package com.fusion.dev.cystol.kill;

import com.fusion.dev.cystol.storage.KillRepository;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class KillService {

    private final KillRepository repository;
    private final Logger logger;
    private final ConcurrentHashMap<UUID, DenseRanking.KillRecord> kills = new ConcurrentHashMap<>();

    public KillService(KillRepository repository, Logger logger) {
        this.repository = repository;
        this.logger = logger;
    }

    public void load() {
        try {
            kills.clear();
            kills.putAll(repository.loadAll());
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
            flush(record);
            return record;
        });
    }

    public void setName(UUID uuid, String name) {
        kills.computeIfPresent(uuid, (id, prev) -> {
            DenseRanking.KillRecord record = new DenseRanking.KillRecord(id, name, prev.kills());
            flush(record);
            return record;
        });
    }

    private void flush(DenseRanking.KillRecord record) {
        try {
            repository.upsert(record.uuid(), record.name(), record.kills());
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to persist kill for " + record.uuid(), e);
        }
    }

    public List<DenseRanking.Entry> ranking() {
        return DenseRanking.rank(new ArrayList<>(kills.values()));
    }

    public Optional<DenseRanking.Entry> topKiller() {
        return ranking().stream().min(Comparator.comparingInt(DenseRanking.Entry::place));
    }

    public Map<UUID, DenseRanking.KillRecord> snapshot() {
        return Map.copyOf(kills);
    }

    public void clearAll() {
        kills.clear();
        try {
            repository.clear();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to clear kills", e);
        }
    }
}
