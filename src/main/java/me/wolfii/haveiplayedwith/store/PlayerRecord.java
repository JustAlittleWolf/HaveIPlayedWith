package me.wolfii.haveiplayedwith.store;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * One player’s live row. Lifetime totals and every play day stay here; only
 * the last {@link #KEEP_RECENT} sessions are kept. Server and past-name lists
 * stay within {@link #KEEP_HISTORY} so the on-disk count byte cannot wrap.
 */
final class PlayerRecord {
    static final int KEEP_RECENT = 5;
    static final int KEEP_HISTORY = 255;

    final UUID uuid;
    String username = "";
    String note = "";
    long noteTakenAt;
    long totalMinutes;
    int sessionCount;
    int daysPlayed;
    final List<Integer> recentDays = new ArrayList<>();
    final List<Session> sessions = new ArrayList<>();
    final List<ServerPlay> servers = new ArrayList<>();
    final List<SeenName> names = new ArrayList<>();

    PlayerRecord(UUID uuid) {
        this.uuid = uuid;
    }

    void setNote(String note, long noteTakenAt) {
        this.note = note;
        this.noteTakenAt = note.isEmpty() ? 0L : noteTakenAt;
    }

    void setCurrentUsername(String username) {
        this.username = username;
    }

    Optional<String> previousNameIfDifferent(String username) {
        if (names.isEmpty() || names.getFirst().username().equalsIgnoreCase(username)) {
            return Optional.empty();
        }
        return Optional.of(names.getFirst().username());
    }

    void touchName(String username, Instant seenAt) {
        if (!names.isEmpty() && names.getFirst().username().equalsIgnoreCase(username)) {
            return;
        }
        names.removeIf(name -> name.username().equalsIgnoreCase(username));
        names.addFirst(new SeenName(username, seenAt));
        while (names.size() > KEEP_HISTORY) {
            names.removeLast();
        }
    }

    void credit(LocalDate day, String sessionId, String serverId) {
        addSessionMinute(sessionId);
        int epochDay = Math.toIntExact(day.toEpochDay());
        if (!recentDays.contains(epochDay)) {
            daysPlayed++;
            recentDays.add(epochDay);
            recentDays.sort(null);
        }
        totalMinutes++;
        addServerMinute(serverId);
    }

    long minutesForSession(String sessionId) {
        for (Session session : sessions) {
            if (session.id.equals(sessionId)) {
                return session.minutes;
            }
        }
        return 0L;
    }

    boolean matchesName(String usernameLower) {
        if (username.toLowerCase(Locale.ROOT).equals(usernameLower)) {
            return true;
        }
        for (SeenName name : names) {
            if (name.username().toLowerCase(Locale.ROOT).equals(usernameLower)) {
                return true;
            }
        }
        return false;
    }

    PlayerSnapshot snapshot() {
        Optional<String> noteText = Optional.of(note).filter(value -> !value.isBlank());
        Optional<Instant> takenAt = noteText.isEmpty() || noteTakenAt == 0L
            ? Optional.empty()
            : Optional.of(Instant.ofEpochMilli(noteTakenAt));
        return new PlayerSnapshot(
            uuid,
            username,
            noteText,
            takenAt,
            totalMinutes,
            sessionCount,
            daysPlayed,
            lastPlayedBeforeToday(),
            List.copyOf(names),
            mostPlayedServer()
        );
    }

    private Optional<LocalDate> lastPlayedBeforeToday() {
        int today = Math.toIntExact(LocalDate.now().toEpochDay());
        Integer latest = null;
        for (int day : recentDays) {
            if (day != today && (latest == null || day > latest)) {
                latest = day;
            }
        }
        return latest == null ? Optional.empty() : Optional.of(LocalDate.ofEpochDay(latest));
    }

    private Optional<ServerPlay> mostPlayedServer() {
        ServerPlay best = null;
        for (ServerPlay server : servers) {
            if (best == null
                || server.minutes() > best.minutes()
                || (server.minutes() == best.minutes() && server.serverId().compareTo(best.serverId()) < 0)) {
                best = server;
            }
        }
        return Optional.ofNullable(best);
    }

    private void addServerMinute(String serverId) {
        for (int i = 0; i < servers.size(); i++) {
            ServerPlay server = servers.get(i);
            if (server.serverId().equals(serverId)) {
                servers.set(i, new ServerPlay(serverId, server.minutes() + 1));
                return;
            }
        }
        servers.add(new ServerPlay(serverId, 1L));
        if (servers.size() > KEEP_HISTORY) {
            int drop = 0;
            for (int i = 1; i < servers.size(); i++) {
                if (servers.get(i).minutes() < servers.get(drop).minutes()
                    || (servers.get(i).minutes() == servers.get(drop).minutes()
                        && servers.get(i).serverId().compareTo(servers.get(drop).serverId()) > 0)) {
                    drop = i;
                }
            }
            servers.remove(drop);
        }
    }

    private void addSessionMinute(String sessionId) {
        for (int i = 0; i < sessions.size(); i++) {
            Session session = sessions.get(i);
            if (session.id.equals(sessionId)) {
                session.minutes++;
                return;
            }
        }
        sessions.add(new Session(sessionId, 1L));
        sessionCount++;
        while (sessions.size() > KEEP_RECENT) {
            sessions.removeFirst();
        }
    }

    static final class Session {
        final String id;
        long minutes;

        Session(String id, long minutes) {
            this.id = id;
            this.minutes = minutes;
        }
    }
}