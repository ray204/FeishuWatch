package io.github.ray204.feishuwatch;

public final class Event {
    public final String id, title, location, startDate, endDate;
    public final long startMs, endMs;
    public final boolean allDay;
    public Event(String id, String title, String location, long startMs, long endMs, boolean allDay, String startDate, String endDate) {
        this.id = id; this.title = title; this.location = location; this.startMs = startMs; this.endMs = endMs;
        this.allDay = allDay; this.startDate = startDate; this.endDate = endDate;
    }
    public String key() { return id + "@" + startMs; }
}
