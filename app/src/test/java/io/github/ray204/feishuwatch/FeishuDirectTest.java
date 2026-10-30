package io.github.ray204.feishuwatch;

import org.junit.Test;
import org.json.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class FeishuDirectTest {
    @Test public void ordinaryOccurrencesUseSeriesAttendeesButExceptionsKeepTheirOwnList() throws Exception {
        String prefix = "11111111-2222-3333-4444-555555555555_";
        JSONArray events = new JSONArray();
        for (int i = 1; i <= 3; i++) {
            JSONObject e = new JSONObject().put("event_id", prefix + i).put("start_time", new JSONObject().put("timestamp", i * 1000))
                .put("end_time", new JSONObject().put("timestamp", i * 1000 + 60));
            if (i < 3) e.put("is_exception", i == 2);
            events.put(e);
        }
        JSONArray result = FeishuDirect.normalize(events);
        assertEquals(prefix + "0", result.getJSONObject(0).getString("attendeeEventId"));
        assertEquals(prefix + "2", result.getJSONObject(1).getString("attendeeEventId"));
        assertEquals(prefix + "3", result.getJSONObject(2).getString("attendeeEventId"));
        assertEquals(prefix + "1", result.getJSONObject(0).getString("id"));
        assertEquals(1000_000, result.getJSONObject(0).getLong("startMs"));
    }
    void primaryReply() throws Exception {
        wire.add(200, "{\"data\":{\"calendars\":[{\"calendar\":{\"calendar_id\":\"primary/id\"}}]}}");
    }
    JSONObject person(int i) throws Exception { return new JSONObject().put("type", "user").put("user_id", "fixture-" + i).put("display_name", "示例参会人" + i); }
    void peopleReply(JSONArray items, boolean more, String token) throws Exception {
        wire.add(200, new JSONObject().put("data", new JSONObject().put("items", items).put("has_more", more).put("page_token", token)).toString());
    }
    @Test public void attendeesPaginateFilterResourcesAndShowFirstTenPeople() throws Exception {
        authorized(false); primaryReply();
        JSONArray first = new JSONArray().put(new JSONObject().put("type", "resource").put("display_name", "示例会议室"))
            .put(person(99).put("rsvp_status", "removed"));
        for (int i = 0; i < 8; i++) first.put(person(i));
        peopleReply(first, true, "page/2");
        peopleReply(new JSONArray().put(person(8)).put(person(9)).put(person(10)).put(person(11)), false, "");
        JSONObject result = client.attendees("event/id_123"); JSONArray names = result.getJSONArray("names");
        assertEquals(10, names.length()); assertEquals("示例参会人0", names.getString(0)); assertEquals("示例参会人9", names.getString(9));
        assertTrue(result.getBoolean("hasMore")); assertFalse(result.getBoolean("partial"));
        assertTrue(wire.urls.get(1).contains("primary%2Fid/events/event%2Fid_123/attendees?"));
        assertTrue(wire.urls.get(2).contains("page_token=page%2F2"));
        assertEquals("Bearer old-access", wire.headers.get(1).get("Authorization"));
    }
    @Test public void attendeeGroupsDeduplicatePeopleAndPreserveVisibleNamesOnPartialAccess() throws Exception {
        authorized(false); primaryReply();
        peopleReply(new JSONArray().put(person(1).put("is_organizer", true))
            .put(new JSONObject().put("type", "chat").put("attendee_id", "group/one"))
            .put(new JSONObject().put("type", "chat").put("attendee_id", "group/two")), false, "");
        JSONObject member1 = person(1), member2 = person(2);
        member1.put("open_id", member1.remove("user_id")); member2.put("open_id", member2.remove("user_id"));
        peopleReply(new JSONArray().put(member1).put(member2), false, "");
        wire.add(403, "{\"code\":194001}");
        JSONObject result = client.attendees("event");
        assertEquals("[\"示例参会人1 · 组织者\",\"示例参会人2\"]", result.getJSONArray("names").toString());
        assertTrue(result.getBoolean("partial")); assertFalse(result.getBoolean("hasMore"));
        assertTrue(wire.urls.get(2).contains("/group%2Fone/chat_members?"));
        assertFalse(state.read().optBoolean("reauthorize"));
    }
    @Test public void attendeeReadRefreshesCredentialsAndPermissionDenialKeepsConnection() throws Exception {
        authorized(true); wire.add(200, tokens("new-access", "new-refresh")); primaryReply();
        wire.add(403, "{\"code\":194001,\"msg\":\"private provider text\"}");
        IOException failure = assertThrows(IOException.class, () -> client.attendees("event"));
        assertEquals("此日程未开放参会人名单", failure.getMessage());
        assertEquals("Bearer new-access", wire.headers.get(2).get("Authorization"));
        assertEquals("new-refresh", state.read().getString("refreshToken")); assertFalse(state.read().getBoolean("reauthorize"));
        primaryReply(); peopleReply(new JSONArray(), false, "");
        assertEquals(0, client.attendees("empty").getJSONArray("names").length());
    }
    @Test public void attendeeRevokedTokenRequiresReauthorizationWithoutReplayingRefresh() throws Exception {
        authorized(false); primaryReply(); wire.add(401, "{}");
        assertThrows(FeishuDirect.Reauthorize.class, () -> client.attendees("event"));
        assertTrue(state.read().getBoolean("reauthorize")); int requests = wire.urls.size();
        assertThrows(FeishuDirect.Reauthorize.class, () -> client.attendees("event")); assertEquals(requests, wire.urls.size());
    }
    static final class Memory implements FeishuDirect.State {
        String value = "{}";
        public JSONObject read() throws Exception { return new JSONObject(value); }
        public void save(JSONObject value) { this.value = value.toString(); }
    }
    static final class Wire implements FeishuDirect.Transport {
        final Queue<FeishuDirect.Response> replies = new ArrayDeque<>();
        final List<String> urls = new ArrayList<>(), bodies = new ArrayList<>();
        final List<Map<String,String>> headers = new ArrayList<>();
        boolean fail;
        void add(int status, String json) throws Exception { replies.add(new FeishuDirect.Response(status,new JSONObject(json))); }
        public FeishuDirect.Response request(String url,String method,Map<String,String> h,String body) throws Exception {
            urls.add(url); bodies.add(body); headers.add(new HashMap<>(h));
            if (fail) throw new IOException("fixture network interruption");
            assertFalse("Unexpected request",replies.isEmpty()); return replies.remove();
        }
    }
    final Memory state = new Memory(); final Wire wire = new Wire(); final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    final FeishuDirect client = new FeishuDirect(state,wire,now::get);
    void configure() throws Exception { client.configure("cli_fixture", "fixture+secret"); }
    void begin() throws Exception {
        configure(); wire.add(200,"{\"device_code\":\"fixture-device\",\"user_code\":\"ABCD-EFGH\",\"verification_uri_complete\":\"https://accounts.feishu.cn/oauth/v1/device/verify?user_code=ABCD-EFGH\",\"expires_in\":600,\"interval\":5}"); client.begin();
    }
    String tokens(String access,String refresh) { return "{\"access_token\":\""+access+"\",\"refresh_token\":\""+refresh+"\",\"expires_in\":7200}"; }
    void authorized(boolean expired) throws Exception {
        configure(); JSONObject s=state.read(); s.put("accessToken","old-access").put("refreshToken","old-refresh").put("expiresAt",now.get()+(expired?60_000:7200_000)); state.save(s);
    }
    void agendaReplies(String items) throws Exception {
        wire.add(200,"{\"code\":0,\"data\":{\"calendars\":[{\"calendar\":{\"calendar_id\":\"primary/id\"}}]}}");
        wire.add(200,"{\"code\":0,\"data\":{\"items\":"+items+"}}");
    }
    @Test public void deviceAuthorizationPollsFeishuWithoutCallbackServer() throws Exception {
        begin(); assertEquals(1,wire.urls.size()); assertEquals(FeishuDirect.ACCOUNTS+"/oauth/v1/device_authorization",wire.urls.get(0));
        assertEquals("Basic "+Base64.getEncoder().encodeToString("cli_fixture:fixture+secret".getBytes("UTF-8")),wire.headers.get(0).get("Authorization"));
        assertTrue(wire.bodies.get(0).contains("offline_access")); assertFalse(wire.bodies.get(0).contains("redirect_uri"));
        assertFalse(client.poll()); assertEquals(1,wire.urls.size());
        now.addAndGet(5000); wire.add(400,"{\"code\":20094}"); assertFalse(client.poll());
        now.addAndGet(5000); wire.add(200,tokens("access-one","refresh-one")); assertTrue(client.poll());
        assertTrue(wire.bodies.get(2).contains("client_secret=fixture%2Bsecret")); assertTrue(wire.bodies.get(2).contains("device_code=fixture-device"));
        assertEquals("access-one",state.read().getString("accessToken")); assertEquals("refresh-one",state.read().getString("refreshToken"));
        assertFalse(state.read().has("deviceCode")); assertFalse(state.read().has("pairUrl"));
    }
    @Test public void slowDownSurvivesRecreationAndPreventsEarlyPoll() throws Exception {
        begin(); now.addAndGet(5000); wire.add(400,"{\"error\":\"slow_down\"}"); assertFalse(client.poll());
        assertEquals(10,state.read().getInt("interval")); long retry=state.read().getLong("nextPoll");
        FeishuDirect recreated=new FeishuDirect(state,wire,now::get); now.set(retry-1); assertFalse(recreated.poll()); assertEquals(2,wire.urls.size());
        now.set(retry); wire.add(400,"{\"error\":\"authorization_pending\"}"); assertFalse(recreated.poll()); assertEquals(3,wire.urls.size());
    }
    @Test public void deniedAndExpiredAuthorizationsCannotBePolledAgain() throws Exception {
        for(String error:new String[]{"access_denied","expired_token","invalid_grant"}) {
            begin(); now.addAndGet(5000); wire.add(400,"{\"error\":\""+error+"\"}");
            assertThrows(IOException.class,client::poll); assertFalse(state.read().has("deviceCode")); int count=wire.urls.size();
            assertThrows(IOException.class,client::poll); assertEquals(count,wire.urls.size());
        }
        begin(); now.set(state.read().getLong("pairExpires")); int count=wire.urls.size();
        assertThrows(IOException.class,client::poll); assertEquals(count,wire.urls.size());
    }
    @Test public void cancelRemovesPendingAuthorizationButKeepsAppCredentials() throws Exception {
        begin(); client.cancelPair(); assertFalse(state.read().has("deviceCode")); assertFalse(state.read().has("pairExpires"));
        assertEquals("fixture+secret",state.read().getString("appSecret")); assertFalse(state.read().has("accessToken"));
    }
    @Test public void unsafeVerificationAddressAndIncompleteTokensAreRejected() throws Exception {
        configure(); wire.add(200,"{\"verification_uri_complete\":\"https://example.com/collect\"}");
        assertThrows(IOException.class,client::begin); assertFalse(state.read().has("deviceCode"));
        begin(); now.addAndGet(5000); wire.add(200,"{\"access_token\":\"incomplete\",\"expires_in\":7200}");
        assertThrows(IOException.class,client::poll); assertFalse(state.read().has("accessToken"));
        assertFalse(state.read().has("deviceCode"));
        int attempts = wire.urls.size(); assertThrows(IOException.class,client::poll); assertEquals(attempts,wire.urls.size());
    }
    @Test public void refreshRotatesTokensBeforeCalendarReadAndSurvivesRestart() throws Exception {
        authorized(true); wire.add(200,tokens("new-access","new-refresh")); agendaReplies("[]"); JSONObject result=client.agenda();
        assertEquals(0,result.getJSONArray("events").length()); assertEquals(now.get()+86400_000,result.getLong("validUntil"));
        assertTrue(wire.bodies.get(0).contains("refresh_token=old-refresh")); assertEquals("Bearer new-access",wire.headers.get(1).get("Authorization"));
        assertEquals("new-refresh",state.read().getString("refreshToken")); assertFalse(state.read().getBoolean("reauthorize"));
        agendaReplies("[]"); new FeishuDirect(state,wire,now::get).agenda(); assertEquals(5,wire.urls.size());
        assertTrue(wire.urls.get(2).contains("primary%2Fid/events/instance_view"));
    }
    @Test public void ambiguousRefreshIsNotReplayedAfterRestart() throws Exception {
        authorized(true); wire.fail=true; assertThrows(FeishuDirect.Reauthorize.class,client::agenda);
        assertTrue(state.read().getBoolean("reauthorize")); assertEquals("old-refresh",state.read().getString("refreshToken"));
        wire.fail=false; assertThrows(FeishuDirect.Reauthorize.class,new FeishuDirect(state,wire,now::get)::agenda); assertEquals(1,wire.urls.size());
    }
    @Test public void revokedUserTokenRequiresReauthorizationWithoutLeakingProviderError() throws Exception {
        authorized(false); wire.add(401,"{\"code\":99991668,\"msg\":\"sensitive-provider-detail\"}");
        Exception error=assertThrows(FeishuDirect.Reauthorize.class,client::agenda);
        assertTrue(state.read().getBoolean("reauthorize")); assertFalse(error.getMessage().contains("sensitive"));
    }
    @Test public void permissionFailureKeepsCredentialsAndMalformedCalendarIsRejected() throws Exception {
        authorized(false); String previous=state.value; wire.add(403,"{\"code\":99991672}");
        assertThrows(IOException.class,client::agenda); assertEquals(previous,state.value);
        wire.add(200,"{\"data\":{\"calendars\":[]}}"); assertThrows(IOException.class,client::agenda); assertEquals(previous,state.value);
    }
    @Test public void normalizationPreservesInstancesAllDayDatesAndFiltersRemovedEvents() throws Exception {
        JSONArray input=new JSONArray("[{\"event_id\":\"cancel\",\"status\":\"cancelled\"},{\"event_id\":\"declined\",\"rsvp_status\":\"decline\"},{\"event_id\":\"removed\",\"rsvp_status\":\"removed\"},{\"event_id\":\"instance-a\",\"summary\":\"Fixture\",\"start_time\":{\"timestamp\":\"1800000100\"},\"end_time\":{\"timestamp\":\"1800000200\"}},{\"event_id\":\"all-day\",\"start_time\":{\"date\":\"2027-01-15\"},\"end_time\":{\"date\":\"2027-01-16\"}}]");
        JSONArray events=FeishuDirect.normalize(input); assertEquals(2,events.length());
        assertTrue(events.getJSONObject(0).getBoolean("allDay")); assertEquals("2027-01-15",events.getJSONObject(0).getString("startDate"));
        assertEquals("instance-a",events.getJSONObject(1).getString("id")); assertEquals(1800000100000L,events.getJSONObject(1).getLong("startMs"));
        assertThrows(Exception.class,()->FeishuDirect.normalize(new JSONArray("[{\"event_id\":\"bad\",\"start_time\":{},\"end_time\":{}}]")));
    }
    @Test public void networkFailurePreservesPollThrottle() throws Exception {
        begin(); now.addAndGet(5000); wire.fail=true; assertThrows(IOException.class,client::poll);
        wire.fail=false; assertFalse(client.poll()); assertEquals(2,wire.urls.size()); assertTrue(state.read().has("deviceCode"));
    }
    @Test public void authorizationDiagnosticsRedactAppSecretAndDeviceCode() throws Exception {
        begin(); now.addAndGet(5000);
        wire.add(400,"{\"error\":\"invalid_grant\",\"error_description\":\"Rejected fixture+secret and fixture-device for ABCD-EFGH\"}");
        assertThrows(IOException.class,client::poll);
        assertEquals("invalid_grant",state.read().getString("lastAuthError"));
        assertEquals("Rejected [redacted] and [redacted] for [redacted]",state.read().getString("lastAuthDetail"));
    }
    @Test public void wrappedOAuthSuccessIsAcceptedForLoginAndTokenRefresh() throws Exception {
        begin(); now.addAndGet(5000);
        wire.add(200,"{\"code\":0,\"data\":"+tokens("wrapped-access","wrapped-refresh")+"}");
        assertTrue(client.poll()); assertEquals("wrapped-access",state.read().getString("accessToken"));
        assertFalse(state.read().has("deviceCode"));
        now.addAndGet(7200_000);
        wire.add(200,"{\"code\":0,\"data\":"+tokens("rotated-access","rotated-refresh")+"}"); agendaReplies("[]");
        client.agenda(); assertEquals("rotated-refresh",state.read().getString("refreshToken"));
        assertEquals("Bearer rotated-access",wire.headers.get(3).get("Authorization"));
    }
}
