package com.fusion.dev.cystol.storage;

import com.fusion.dev.cystol.event.EventPhase;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

public final class EventRepository {

    public record StoredEvent(
            Instant start,
            Instant end,
            Instant ffaOverride,
            EventPhase phase,
            boolean ffaTeleported,
            boolean ceremonyDone,
            boolean paused,
            boolean spawnRtpDone,
            Instant countdownAnchor,
            Instant huntEntered,
            Instant ffaEntered
    ) {
    }

    private final SqliteDatabase db;

    public EventRepository(SqliteDatabase db) {
        this.db = db;
    }

    public StoredEvent load() throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    """
                            SELECT start_epoch, end_epoch, ffa_override_epoch, phase,
                                   ffa_teleported, ceremony_done, paused, spawn_rtp_done,
                                   countdown_anchor_epoch, hunt_entered_epoch, ffa_entered_epoch
                            FROM event_state WHERE id = 1
                            """)) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return new StoredEvent(
                                null, null, null, EventPhase.IDLE,
                                false, false, false, false,
                                null, null, null
                        );
                    }
                    return new StoredEvent(
                            epoch(rs.getObject("start_epoch")),
                            epoch(rs.getObject("end_epoch")),
                            epoch(rs.getObject("ffa_override_epoch")),
                            parsePhase(rs.getString("phase")),
                            rs.getInt("ffa_teleported") == 1,
                            rs.getInt("ceremony_done") == 1,
                            rs.getInt("paused") == 1,
                            rs.getInt("spawn_rtp_done") == 1,
                            epoch(rs.getObject("countdown_anchor_epoch")),
                            epoch(rs.getObject("hunt_entered_epoch")),
                            epoch(rs.getObject("ffa_entered_epoch"))
                    );
                }
            }
        });
    }

    public void save(StoredEvent state) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE event_state SET
                      start_epoch = ?,
                      end_epoch = ?,
                      ffa_override_epoch = ?,
                      phase = ?,
                      ffa_teleported = ?,
                      ceremony_done = ?,
                      paused = ?,
                      spawn_rtp_done = ?,
                      countdown_anchor_epoch = ?,
                      hunt_entered_epoch = ?,
                      ffa_entered_epoch = ?
                    WHERE id = 1
                    """)) {
                setEpoch(ps, 1, state.start());
                setEpoch(ps, 2, state.end());
                setEpoch(ps, 3, state.ffaOverride());
                ps.setString(4, state.phase() == null ? EventPhase.IDLE.name() : state.phase().name());
                ps.setInt(5, state.ffaTeleported() ? 1 : 0);
                ps.setInt(6, state.ceremonyDone() ? 1 : 0);
                ps.setInt(7, state.paused() ? 1 : 0);
                ps.setInt(8, state.spawnRtpDone() ? 1 : 0);
                setEpoch(ps, 9, state.countdownAnchor());
                setEpoch(ps, 10, state.huntEntered());
                setEpoch(ps, 11, state.ffaEntered());
                ps.executeUpdate();
            }
        });
    }

    private static Instant epoch(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return Instant.ofEpochSecond(n.longValue());
        }
        return null;
    }

    private static void setEpoch(PreparedStatement ps, int idx, Instant instant) throws SQLException {
        if (instant == null) {
            ps.setObject(idx, null);
        } else {
            ps.setLong(idx, instant.getEpochSecond());
        }
    }

    private static EventPhase parsePhase(String raw) {
        if (raw == null || raw.isBlank()) {
            return EventPhase.IDLE;
        }
        try {
            return EventPhase.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return EventPhase.IDLE;
        }
    }

    public Optional<String> metric(String key) throws SQLException {
        return db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT value FROM metrics WHERE key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return Optional.ofNullable(rs.getString(1));
                    }
                }
            }
            return Optional.empty();
        });
    }

    public void setMetric(String key, String value) throws SQLException {
        db.withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO metrics(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.executeUpdate();
            }
        });
    }
}
