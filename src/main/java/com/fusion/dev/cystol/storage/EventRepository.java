package com.fusion.dev.cystol.storage;

import com.fusion.dev.cystol.event.EventPhase;

import java.sql.Connection;
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
            boolean ceremonyDone
    ) {
    }

    private final SqliteDatabase db;

    public EventRepository(SqliteDatabase db) {
        this.db = db;
    }

    public StoredEvent load() throws SQLException {
        Connection c = db.connection();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT start_epoch, end_epoch, ffa_override_epoch, phase, ffa_teleported, ceremony_done FROM event_state WHERE id = 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new StoredEvent(null, null, null, EventPhase.IDLE, false, false);
                }
                return new StoredEvent(
                        epoch(rs.getObject("start_epoch")),
                        epoch(rs.getObject("end_epoch")),
                        epoch(rs.getObject("ffa_override_epoch")),
                        parsePhase(rs.getString("phase")),
                        rs.getInt("ffa_teleported") == 1,
                        rs.getInt("ceremony_done") == 1
                );
            }
        }
    }

    public void save(StoredEvent state) throws SQLException {
        Connection c = db.connection();
        try (PreparedStatement ps = c.prepareStatement("""
                UPDATE event_state SET
                  start_epoch = ?,
                  end_epoch = ?,
                  ffa_override_epoch = ?,
                  phase = ?,
                  ffa_teleported = ?,
                  ceremony_done = ?
                WHERE id = 1
                """)) {
            setEpoch(ps, 1, state.start());
            setEpoch(ps, 2, state.end());
            setEpoch(ps, 3, state.ffaOverride());
            ps.setString(4, state.phase() == null ? EventPhase.IDLE.name() : state.phase().name());
            ps.setInt(5, state.ffaTeleported() ? 1 : 0);
            ps.setInt(6, state.ceremonyDone() ? 1 : 0);
            ps.executeUpdate();
        }
    }

    private static Instant epoch(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            long v = n.longValue();
            if (v == 0 && !(o instanceof Long)) {
                // still valid epoch
            }
            return Instant.ofEpochSecond(v);
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
        Connection c = db.connection();
        try (PreparedStatement ps = c.prepareStatement("SELECT value FROM metrics WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString(1));
                }
            }
        }
        return Optional.empty();
    }

    public void setMetric(String key, String value) throws SQLException {
        Connection c = db.connection();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO metrics(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }
}
