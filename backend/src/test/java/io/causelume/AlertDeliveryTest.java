package io.causelume;

import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:alert-delivery;DB_CLOSE_DELAY=-1","APP_ADMIN_PASSWORD=test-admin-secret",
    "APP_OPERATOR_PASSWORD=test-operator-secret","ALERTMANAGER_TOKEN=test-machine-secret","app.mail.poll-ms=600000"})
class AlertDeliveryTest {
    @LocalServerPort int port;
    @Autowired Store store;
    final HttpClient http=HttpClient.newHttpClient();
    final ObjectMapper json=new ObjectMapper();

    HttpRequest event(String fingerprint,String status,String start,String token) {
        var item=Map.of("fingerprint",fingerprint,"startsAt",start,"endsAt","2026-02-02T01:00:00Z","status",status,
            "labels",Map.of("service","checkout","severity","critical","alertname","checkout failure"),
            "annotations",Map.of("summary","checkout returned 503"));
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/integrations/alertmanager/primary"))
            .header("Content-Type","application/json").header("Authorization","Bearer "+token)
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("version","4","alerts",java.util.List.of(item))))).build();
    }
    long count(String sql,Object... args) {return store.db.queryForObject(sql,Long.class,args);}
    int send(HttpRequest req) throws Exception {return http.send(req,HttpResponse.BodyHandlers.discarding()).statusCode();}

    @Test void machineLifecycleConcurrentDeliveryAndMaintenance() throws Exception {
        String start="2026-02-01T00:00:00Z",token="test-machine-secret";
        assertEquals(401,send(event("f1","firing",start,"invalid")));
        store.db.update("update services set owner_id=(select id from accounts where username='operator') where name='checkout'");
        var firing=event("f1","firing",start,token);
        var first=http.sendAsync(firing,HttpResponse.BodyHandlers.ofString());
        var second=http.sendAsync(firing,HttpResponse.BodyHandlers.ofString());
        assertEquals(200,first.get().statusCode(),first.get().body());
        assertEquals(200,second.get().statusCode(),second.get().body());
        var row=store.one("select k.id,k.status,a.id as alert_id,u.username from tickets k join alerts a on k.alert_id=a.id join accounts u on u.id=k.assignee_id where a.fingerprint='f1'");
        long id=(long)row.get("id");
        assertEquals("ASSIGNED",row.get("status")); assertEquals("operator",row.get("username"));
        assertEquals(1,count("select count(*) from notifications where ticket_id=?",id));
        assertEquals(200,send(event("f1","resolved",start,token)));
        assertEquals(200,send(firing));
        assertEquals("RESOLVED",store.one("select lifecycle_status from alerts where id=?",row.get("alertId")).get("lifecycleStatus"));
        assertEquals("ASSIGNED",store.one("select status from tickets where id=?",id).get("status"));
        assertEquals(2,count("select count(*) from notifications where ticket_id=?",id));

        store.db.update("update services set maintenance_until=? where name='checkout'",Instant.now().plusSeconds(600));
        assertEquals(200,send(event("f2","firing",start,token)));
        assertEquals(1,count("select count(*) from tickets k join alerts a on k.alert_id=a.id where a.fingerprint='f2'"));
        assertEquals(2,count("select count(*) from notifications"));
        store.db.update("update services set maintenance_until=? where name='checkout'",Instant.now().minusSeconds(1));
        assertEquals(200,send(event("f2","resolved",start,token)));
        assertEquals(3,count("select count(*) from notifications"));
        assertEquals(200,send(event("f3","resolved",start,token)));
        assertEquals(200,send(event("f3","firing",start,token)));
        assertEquals(0,count("select count(*) from tickets k join alerts a on k.alert_id=a.id where a.fingerprint='f3'"));
        assertEquals(200,send(event("f1","firing","2026-02-02T00:00:00Z",token)));
        assertEquals(2,count("select count(*) from alerts where fingerprint='f1'"));
    }
}
