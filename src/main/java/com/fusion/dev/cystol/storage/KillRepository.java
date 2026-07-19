package com.fusion.dev.cystol.storage;

import com.fusion.dev.cystol.kill.DenseRanking;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class KillRepository {

    private static final String UPSERT = """
            INSERT INTO kills(uuid, name, kills, reached_at_ms) VALUES(?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
              name = excluded.name,
              kills = excluded.kills,
              reached_at_ms = excluded.reached_at_ms
            """;

    private final SqliteDatabase db;

    public KillRepository(SqliteDatabase db) {
        this.db = db;
    }

    public Map<UUID, DenseRanking.KillRecord> loadAll() throws SQLException {
        return db.withConnection(c -> {
            Map<UUID, DenseRanking.KillRecord> map = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uuid, name, kills, reached_at_ms FROM kills");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = UUID.fromString(rs.getString("uuid"));
                    long reached = 0L;
                    try {
                        reached = rs.getLong("reached_at_ms");
                        if (rs.wasNull()) {
                            reached = 0L;
                        }
                    } catch (SQLException ignored) {
                        // column missing on extremely old open without migrate — treat 0
                    }
                    map.put(id, new DenseRanking.KillRecord(
                            id, rs.getString("name"), rs.getInt("kills"), reached
                    ));
                }
            }
            return map;
        });
    }

    public void upsert(UUID uuid, String name, int kills, long reachedAtMs) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(UPSERT)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name == null ? "Unknown" : name);
                ps.setInt(3, kills);
                ps.setLong(4, Math.max(0L, reachedAtMs));
                ps.executeUpdate();
            }
        });
    }

    public void clear() throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM kills")) {
                ps.executeUpdate();
            }
        });
    }

    public List<DenseRanking.KillRecord> listAll() throws SQLException {
        return new ArrayList<>(loadAll().values());
    }
}
