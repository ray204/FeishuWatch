package io.github.ray204.feishuwatch;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class ReminderPolicyTest {
    private Event event(String id, long start) { return new Event(id, "示例", "A", start, start + 3600000, false, "", ""); }
    private final Set<String> empty = Collections.emptySet();
    private final Map<String, Long> noSnooze = Collections.emptyMap();
    @Test public void reminderBeforeAtAndAfterBoundary() {
        Event e = event("one", 1200000); List<Event> events = Collections.singletonList(e);
        assertTrue(ReminderPolicy.due(events, empty, noSnooze, 10, 599999, 9000000).isEmpty());
        assertEquals(events, ReminderPolicy.due(events, empty, noSnooze, 10, 600000, 9000000));
        assertEquals(events, ReminderPolicy.due(events, empty, noSnooze, 10, 600001, 9000000));
        assertEquals(600000, ReminderPolicy.next(events, empty, noSnooze, 10, 1, 9000000));
    }
    @Test public void zeroOffsetToleratesAlarmDispatchJitterButNotHistoricalCatchup() {
        List<Event> events = Collections.singletonList(event("one", 1200000));
        assertEquals(1, ReminderPolicy.due(events, empty, noSnooze, 0, 1200001, 9000000).size());
        assertTrue(ReminderPolicy.due(events, empty, noSnooze, 0, 1260000, 9000000).isEmpty());
    }
    @Test public void deliveredStateSurvivesRecomputationAndRescheduledInstanceGetsNewKey() {
        Event e = event("one", 1200000);
        Set<String> delivered = Collections.singleton(ReminderPolicy.deliveryKey(e, 10));
        assertTrue(ReminderPolicy.due(Collections.singletonList(e), delivered, noSnooze, 10, 600000, 9000000).isEmpty());
        Event moved = event("one", 1260000);
        assertEquals(Collections.singletonList(moved), ReminderPolicy.due(Collections.singletonList(moved), delivered, noSnooze, 10, 660000, 9000000));
        assertTrue(ReminderPolicy.due(Collections.emptyList(), delivered, noSnooze, 10, 660000, 9000000).isEmpty());
    }
    @Test public void expiredCacheAndAllDayEventsNeverAlert() {
        List<Event> events = Collections.singletonList(event("one", 1200000));
        assertTrue(ReminderPolicy.due(events, empty, noSnooze, 10, 600000, 600000).isEmpty());
        assertEquals(Long.MAX_VALUE, ReminderPolicy.next(events, empty, noSnooze, 10, 600000, 600000));
        Event day = new Event("day", "全天", "", 0, 0, true, "2033-05-18", "2033-05-18");
        assertTrue(ReminderPolicy.due(Collections.singletonList(day), empty, noSnooze, 10, 600000, 9000000).isEmpty());
        assertEquals(9000000, ReminderPolicy.next(Collections.singletonList(day), empty, noSnooze, 10, 600000, 9000000));
    }
    @Test public void simultaneousEventsBothAlertAndSnoozeDoesNotChangeEventTime() {
        Event a = event("a", 1200000), b = event("b", 1200000);
        assertEquals(Arrays.asList(a, b), ReminderPolicy.due(Arrays.asList(a, b), empty, noSnooze, 10, 600000, 9000000));
        Set<String> delivered = Collections.singleton(ReminderPolicy.deliveryKey(a, 10));
        Map<String, Long> snoozes = Collections.singletonMap(a.key(), 1500000L);
        assertTrue(ReminderPolicy.due(Collections.singletonList(a), delivered, snoozes, 10, 1499999, 9000000).isEmpty());
        assertEquals(Collections.singletonList(a), ReminderPolicy.due(Collections.singletonList(a), delivered, snoozes, 10, 1500000, 9000000));
        assertEquals(1200000, a.startMs);
        assertTrue(ReminderPolicy.due(Collections.singletonList(a), delivered, snoozes, 10, a.endMs, 9000000).isEmpty());
    }
    @Test public void upcomingSelectsCurrentThenNextAndNeverEndedOrAllDay() {
        Event ended = event("ended", 1), current = event("current", 3000000), next = event("next", 5000000);
        assertSame(current, ReminderPolicy.upcoming(Arrays.asList(next, ended, current), 4000000));
        assertNull(ReminderPolicy.upcoming(Collections.singletonList(ended), 4000000));
    }
}
