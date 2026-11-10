package io.causelume;

import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import org.apache.commons.codec.binary.Base32;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.*;

@ActiveProfiles("prod")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "APP_ADMIN_PASSWORD=test-admin-secret","APP_OPERATOR_PASSWORD=test-operator-secret",
    "APP_MFA_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class PostgresAccountsIT {
    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry p) {
        p.add("APP_DB_URL",()->Objects.requireNonNull(System.getenv("TEST_PG_APP_URL")));
    }
    @LocalServerPort int port;
    @Autowired Store store;
    final ObjectMapper json=new ObjectMapper();
    final HttpClient admin=session(), other=session(), operator=session();
    HttpClient session() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
    }
    JsonNode call(HttpClient who,String path,Object body,int expected) throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/"+path));
        if(body!=null) req.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var res=who.send(req.build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(expected,res.statusCode(),path+" returned unexpected HTTP status");
        return json.readTree(res.body());
    }
    @Test void enforcesMfaAndRevokesStaleSessions() throws Exception {
        var password=Map.of("username","admin","password","test-admin-secret");
        assertTrue(call(admin,"login",password,200).get("enrollmentRequired").asBoolean());
        call(admin,"tickets",null,403);
        String seed=call(admin,"account/mfa/enroll",Map.of("password","test-admin-secret"),200).get("secret").asString();
        String otp=new TimeBasedOneTimePasswordGenerator().generateOneTimePasswordString(new SecretKeySpec(new Base32().decode(seed),"HmacSHA1"),Instant.now(),Locale.ROOT);
        var codes=call(admin,"account/mfa/confirm",Map.of("code",otp),200).get("recoveryCodes");
        assertEquals(8,codes.size());
        assertNotEquals(seed,store.one("select mfa_seed from accounts where id=1").get("mfaSeed"));
        assertTrue(call(admin,"me",null,200).get("mfaEnabled").asBoolean());
        var stale=Map.of("username","admin","password","test-admin-secret","code",otp);
        call(other,"login",stale,401);
        assertEquals("MFA_REQUIRED",call(other,"login",password,401).get("code").asString());
        var once=Map.of("username","admin","password","test-admin-secret","code",codes.get(0).asString());
        call(other,"login",once,200);
        call(session(),"login",once,401);
        call(admin,"account/profile",Map.of("email","bad"),400);
        for(String invalid:List.of("oncall@example..test","oncall@.example.test","oncall@example.test.","!@!."+"!.".repeat(100)))
            call(admin,"account/profile",Map.of("email",invalid),400);
        assertEquals("oncall+alerts@ops.example.test",call(admin,"account/profile",Map.of("email"," oncall+alerts@ops.example.test "),200).get("email").asString());
        assertEquals("oncall@example.test",call(admin,"account/profile",Map.of("email","oncall@example.test"),200).get("email").asString());
        call(admin,"users/1/status",Map.of("active",false),409);
        call(operator,"login",Map.of("username","operator","password","test-operator-secret"),200);
        call(admin,"users/2/password",Map.of("newPassword","operator-new-secret"),200);
        call(operator,"me",null,401);
        call(operator,"login",Map.of("username","operator","password","operator-new-secret"),200);
        call(admin,"users/2/status",Map.of("active",false),200);
        call(operator,"me",null,401);
        call(operator,"login",Map.of("username","operator","password","operator-new-secret"),401);
        var alert=Map.of("serviceId",1,"source","compat","externalId","unique","severity","WARNING","title","CPU","occurredAt","2026-11-01T11:00:00Z");
        long alertId=call(admin,"alerts",alert,200).get("id").asLong();
        assertEquals(alertId,call(admin,"alerts",alert,200).get("id").asLong());
        var ticket=Map.of("serviceId",1,"title","PG idempotency","alertId",alertId,"requestId",UUID.randomUUID());
        long ticketId=call(admin,"tickets",ticket,200).get("id").asLong();
        assertEquals(ticketId,call(admin,"tickets",ticket,200).get("id").asLong());
        assertEquals(1,call(admin,"tickets/page?q=idempotency",null,200).get("total").asLong());
        call(admin,"tickets/"+ticketId+"/actions",Map.of("version",0,"action","ASSIGN","assigneeId",1,"note","on call"),200);
        var investigation=Map.of("ticketId",ticketId,"requestId",UUID.randomUUID(),
            "windowStart","2026-11-09T01:00:00.123456789Z","windowEnd","2026-11-09T01:10:00.987654321Z");
        var legacyRequest=UUID.randomUUID();
        long legacy=store.insert("insert into tasks(ticket_id,creator_id,request_id,status,window_start,window_end) values (?,1,?,'FAILED',?,?)",
            ticketId,legacyRequest,Instant.parse((String)investigation.get("windowStart")),Instant.parse((String)investigation.get("windowEnd")));
        assertEquals(legacy,call(admin,"tasks",Map.of("ticketId",ticketId,"requestId",legacyRequest,
            "windowStart",investigation.get("windowStart"),"windowEnd",investigation.get("windowEnd")),200).get("id").asLong());
        long taskId=call(admin,"tasks",investigation,200).get("id").asLong();
        assertEquals(taskId,call(admin,"tasks",investigation,200).get("id").asLong());
        assertEquals(taskId,call(admin,"tasks",Map.of("ticketId",ticketId,"requestId",investigation.get("requestId"),
            "windowStart","2026-11-09T01:00:00.123457Z","windowEnd","2026-11-09T01:10:00.987654Z"),200).get("id").asLong());
        call(admin,"tasks",Map.of("ticketId",ticketId,"requestId",investigation.get("requestId"),
            "windowStart","2026-11-09T01:00:00.123458Z","windowEnd",investigation.get("windowEnd")),409);
        for(int i=0;i<100;i++) {
            String status=store.db.queryForObject("select status from tasks where id=?",String.class,taskId);
            if(status.equals("FAILED")) break;
            Thread.sleep(100);
        }
        assertEquals("FAILED",store.one("select status from tasks where id=?",taskId).get("status"));
        call(admin,"services/1",Map.of("name","checkout","description","retired fixture","resource","http",
            "environment","test","active",false,"version",0),200);
        assertEquals(alertId,call(admin,"alerts",alert,200).get("id").asLong());
        var newAlert=new HashMap<String,Object>(alert);
        newAlert.put("externalId","new-event-after-retirement");
        call(admin,"alerts",newAlert,409);
        assertEquals(ticketId,call(admin,"tickets",ticket,200).get("id").asLong());
        call(admin,"tickets",Map.of("serviceId",1,"title","changed payload","requestId",ticket.get("requestId")),409);
        call(admin,"tickets",Map.of("serviceId",1,"title","new request","requestId",UUID.randomUUID()),409);
        call(admin,"account/password",Map.of("currentPassword","test-admin-secret","newPassword","new-admin-pass"),200);
        call(admin,"me",null,401);
        call(other,"me",null,401);
        assertFalse(store.db.queryForObject("select has_schema_privilege(current_user,'public','CREATE')",Boolean.class));
    }
}
