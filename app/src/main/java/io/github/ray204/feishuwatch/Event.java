package io.github.ray204.feishuwatch;

public final class Event {
    public final String id, title, location, startDate, endDate, attendeeEventId;
    public final long startMs, endMs;
    public final boolean allDay;
    public Event(String id, String title, String location, long startMs, long endMs, boolean allDay, String startDate, String endDate) {
        this(id, title, location, startMs, endMs, allDay, startDate, endDate, id);
    }
    public Event(String id, String title, String location, long startMs, long endMs, boolean allDay, String startDate, String endDate, String attendeeEventId) {
        this.id = id; this.title = title; this.location = location; this.startMs = startMs; this.endMs = endMs;
        this.allDay = allDay; this.startDate = startDate; this.endDate = endDate;
        this.attendeeEventId = attendeeEventId;
    }
    public String key() { return id + "@" + startMs; }
}
