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
            INSERT INTO kills(uuid, name, kills) VALUES(?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, kills = excluded.kills
            """;

    private final SqliteDatabase db;

    public KillRepository(SqliteDatabase db) {
        this.db = db;
    }

    public Map<UUID, DenseRanking.KillRecord> loadAll() throws SQLException {
        return db.withConnection(c -> {
            Map<UUID, DenseRanking.KillRecord> map = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, kills FROM kills");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = UUID.fromString(rs.getString("uuid"));
                    map.put(id, new DenseRanking.KillRecord(id, rs.getString("name"), rs.getInt("kills")));
                }
            }
            return map;
        });
    }

    public void upsert(UUID uuid, String name, int kills) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(UPSERT)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name == null ? "Unknown" : name);
                ps.setInt(3, kills);
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
