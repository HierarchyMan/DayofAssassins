package com.fusion.dev.cystol.storage;

import com.fusion.dev.cystol.kill.DenseRanking;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Chronological past Day of Assassins games: ids 1, 2, 3… via SQLite AUTOINCREMENT.
 * Each game stores a frozen dense ranking snapshot from event end.
 */
public final class PastGameRepository {

    public record PastGame(int id, long endedAtEpochSeconds, List<DenseRanking.Entry> ranking) {
    }

    private final SqliteDatabase db;

    public PastGameRepository(SqliteDatabase db) {
        this.db = db;
    }

    /**
     * Insert a new past game with the given dense ranking.
     *
     * @return new chronological game id (1, 2, 3…)
     */
    public int insertGame(long endedAtEpochSeconds, List<DenseRanking.Entry> ranking) throws SQLException {
        return db.withConnection(c -> {
            int gameId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO past_games(ended_at_epoch) VALUES(?)",
                    Statement.RETURN_GENERATED_KEYS
            )) {
                ps.setLong(1, endedAtEpochSeconds);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("past_games insert returned no id");
                    }
                    gameId = keys.getInt(1);
                }
            }
            if (ranking != null && !ranking.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO past_game_kills(game_id, uuid, name, kills, place)
                        VALUES(?, ?, ?, ?, ?)
                        """)) {
                    for (DenseRanking.Entry e : ranking) {
                        if (e == null || e.uuid() == null) {
                            continue;
                        }
                        ps.setInt(1, gameId);
                        ps.setString(2, e.uuid().toString());
                        ps.setString(3, e.name() == null ? "Unknown" : e.name());
                        ps.setInt(4, e.kills());
                        ps.setInt(5, e.place());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            return gameId;
        });
    }

    public OptionalInt latestGameId() throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT MAX(id) FROM past_games");
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return OptionalInt.empty();
                }
                int id = rs.getInt(1);
                if (rs.wasNull() || id <= 0) {
                    return OptionalInt.empty();
                }
                return OptionalInt.of(id);
            }
        });
    }

    public int gameCount() throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM past_games");
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return 0;
                }
                return rs.getInt(1);
            }
        });
    }

    public Optional<PastGame> loadGame(int gameId) throws SQLException {
        if (gameId <= 0) {
            return Optional.empty();
        }
        return db.withConnection(c -> {
            long ended;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ended_at_epoch FROM past_games WHERE id = ?")) {
                ps.setInt(1, gameId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    ended = rs.getLong(1);
                }
            }
            List<DenseRanking.Entry> ranking = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT uuid, name, kills, place FROM past_game_kills
                    WHERE game_id = ?
                    ORDER BY place ASC, kills DESC, name COLLATE NOCASE ASC
                    """)) {
                ps.setInt(1, gameId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ranking.add(new DenseRanking.Entry(
                                UUID.fromString(rs.getString("uuid")),
                                rs.getString("name"),
                                rs.getInt("kills"),
                                rs.getInt("place")
                        ));
                    }
                }
            }
            return Optional.of(new PastGame(gameId, ended, List.copyOf(ranking)));
        });
    }

    public Optional<PastGame> loadLatest() throws SQLException {
        OptionalInt id = latestGameId();
        if (id.isEmpty()) {
            return Optional.empty();
        }
        return loadGame(id.getAsInt());
    }
}
