package io.github.ray204.feishuwatch;

import java.util.*;

/** Pure policy; all times are epoch milliseconds. No network/Activity dependency. */
public final class ReminderPolicy {
    public static final long MINUTE = 60_000L;
    public static String deliveryKey(Event event, int minutes) { return event.key() + ":" + minutes; }
    public static List<Event> due(List<Event> events, Set<String> delivered, Map<String, Long> snoozes, int minutes, long now, long validUntil) {
        List<Event> result = new ArrayList<>();
        if (now >= validUntil) return result;
        for (Event event : events) {
            if (event.allDay || event.endMs <= now) continue;
            Long snooze = snoozes.get(event.key());
            boolean normal = !delivered.contains(deliveryKey(event, minutes)) && event.startMs - minutes * MINUTE <= now && now < event.startMs + MINUTE;
            if (normal || (snooze != null && snooze <= now)) result.add(event);
        }
        return result;
    }
    public static long next(List<Event> events, Set<String> delivered, Map<String, Long> snoozes, int minutes, long now, long validUntil) {
        if (now >= validUntil) return Long.MAX_VALUE;
        long next = validUntil;
        for (Event event : events) {
            if (event.allDay || event.endMs <= now) continue;
            if (event.startMs > now) next = Math.min(next, event.startMs);
            next = Math.min(next, event.endMs);
            if (!delivered.contains(deliveryKey(event, minutes)) && now < event.startMs + MINUTE) next = Math.min(next, Math.max(now + 1000, event.startMs - minutes * MINUTE));
            Long snooze = snoozes.get(event.key());
            if (snooze != null && snooze < event.endMs) next = Math.min(next, Math.max(now + 1000, snooze));
        }
        return next;
    }
    public static Event upcoming(List<Event> events, long now) {
        return events.stream().filter(e -> !e.allDay && e.endMs > now).min(Comparator.comparingLong(e -> e.startMs)).orElse(null);
    }
}
