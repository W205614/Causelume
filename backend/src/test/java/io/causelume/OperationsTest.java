package io.causelume;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:operations;DB_CLOSE_DELAY=-1",
    "APP_ADMIN_PASSWORD=test-admin-secret", "APP_OPERATOR_PASSWORD=test-operator-secret"})
class OperationsTest {
    @LocalServerPort int port;
    final ObjectMapper json=new ObjectMapper();
    final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();

    HttpResponse<String> request(String path,Object body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/"+path));
        if(body!=null) builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    int post(String path,Object data) throws Exception { return request(path,data).statusCode(); }

    @Test void alertReplaySurvivesServiceDeactivation() throws Exception {
        assertEquals(200,post("login",Map.of("username","admin","password","test-admin-secret")));
        long serviceId=json.readTree(request("services",Map.of("name","alert-replay-service","description","test","resource","http")).body()).get("id").asLong();
        var original=Map.of("serviceId",serviceId,"source","probe","externalId","retired-event","severity","WARNING",
            "title","first observation","occurredAt","2026-11-09T00:00:00Z");
        var first=request("alerts",original);
        assertEquals(200,first.statusCode(),first.body());
        var expected=json.readTree(first.body());
        assertEquals(200,post("services/"+serviceId,Map.of("name","alert-replay-service","description","test","resource","http",
            "environment","test","active",false,"version",0)));
        var replay=request("alerts",original);
        assertEquals(200,replay.statusCode(),replay.body());
        assertEquals(expected,json.readTree(replay.body()));
        var changed=new java.util.HashMap<String,Object>(original);
        changed.put("title","later observation");
        changed.put("severity","CRITICAL");
        assertEquals(expected,json.readTree(request("alerts",changed).body()));
        changed.put("externalId","new-event");
        assertEquals(409,post("alerts",changed));
        changed.put("externalId","retired-event");
        changed.put("source","other-probe");
        assertEquals(409,post("alerts",changed));
        var alerts=json.readTree(request("alerts/page?serviceId="+serviceId,null).body());
        assertEquals(1,alerts.get("total").asLong());
        assertEquals(expected.get("id"),alerts.get("items").get(0).get("id"));
    }

    @Test void ticketReplaySurvivesServiceDeactivation() throws Exception {
        assertEquals(200,post("login",Map.of("username","admin","password","test-admin-secret")));
        long serviceId=json.readTree(request("services",Map.of("name","replay-service","description","test","resource","http")).body()).get("id").asLong();
        UUID requestId=UUID.randomUUID();
        var original=Map.of("serviceId",serviceId,"title","replay after retirement","requestId",requestId,"priority","P1");
        var first=request("tickets",original);
        assertEquals(200,first.statusCode(),first.body());
        long id=json.readTree(first.body()).get("id").asLong();
        assertEquals(200,post("services/"+serviceId,Map.of("name","replay-service","description","test","resource","http",
            "environment","test","active",false,"version",0)));
        var replay=request("tickets",original);
        assertEquals(200,replay.statusCode(),replay.body());
        assertEquals(id,json.readTree(replay.body()).get("id").asLong());
        assertEquals(1,json.readTree(replay.body()).get("timeline").size());
        assertEquals(409,post("tickets",Map.of("serviceId",serviceId,"title","changed payload","requestId",requestId)));
        assertEquals(409,post("tickets",Map.of("serviceId",serviceId,"title","new request","requestId",UUID.randomUUID())));
        assertEquals(200,post("login",Map.of("username","operator","password","test-operator-secret")));
        assertEquals(409,post("tickets",original));
    }

    @Test void simultaneousWritesAndCrossSiteRequests() throws Exception {
        assertEquals(200,post("login",Map.of("username","admin","password","test-admin-secret")));
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/logout"))
            .header("Origin","https://another.invalid").header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(403,http.send(req,HttpResponse.BodyHandlers.discarding()).statusCode());
        var ticket=Map.of("title","parallel","serviceId",1,"requestId",UUID.randomUUID());
        var created=request("tickets",ticket);
        long id=json.readTree(created.body()).get("id").asLong();
        var race=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/tickets/"+id+"/actions"))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(
                json.writeValueAsString(Map.of("action","ASSIGN","assigneeId",2,"version",0,"note","on call")))).build();
        var a=http.sendAsync(race,HttpResponse.BodyHandlers.ofString());
        var b=http.sendAsync(race,HttpResponse.BodyHandlers.ofString());
        assertEquals(java.util.Set.of(200,409),java.util.Set.of(a.join().statusCode(),b.join().statusCode()));
        var updated=json.readTree(request("tickets/"+id,null).body());
        assertEquals("ASSIGNED",updated.get("status").asString());
        assertEquals(1,updated.get("version").asLong());
        assertEquals(2,updated.get("timeline").size());
    }

    @Test void loginPermissionsAndTickets() throws Exception {
        assertEquals(401,request("tickets",null).statusCode());
        assertEquals(401,post("login",Map.of("username","admin","password","wrong")));
        assertEquals(200,post("login",Map.of("username","operator","password","test-operator-secret")));
        assertEquals(403,post("services",Map.of("name","x","description","x","resource","x")));
        assertEquals(200,post("login",Map.of("username","admin","password","test-admin-secret")));
        var alert=Map.of("serviceId",1,"source","probe","externalId","test-1","severity","CRITICAL","title","upstream timeout","occurredAt","2026-07-01T00:00:00Z");
        var first=request("alerts",alert);
        assertEquals(200,first.statusCode(),first.body());
        long alertId=json.readTree(first.body()).get("id").asLong();
        assertEquals(alertId,json.readTree(request("alerts",alert).body()).get("id").asLong());
        var ticket=Map.of("serviceId",1,"requestId",UUID.randomUUID(),"title","diagnose","alertId",alertId);
        var created=request("tickets",ticket);
        assertEquals(200,created.statusCode(),created.body());
        long id=json.readTree(created.body()).get("id").asLong();
        assertEquals(id,json.readTree(request("tickets",ticket).body()).get("id").asLong());
        String path="tickets/"+id+"/actions";
        assertEquals(409,post(path,Map.of("version",0,"action","CLOSE","note","skip")));
        assertEquals(200,post(path,Map.of("version",0,"action","ASSIGN","assigneeId",2,"note","on call")));
        assertEquals(409,post(path,Map.of("version",0,"action","START","note","stale write")));
        assertEquals(200,post(path,Map.of("version",1,"action","START","note","check service")));
        assertEquals(200,post(path,Map.of("version",2,"action","VERIFY","note","probe recovered")));
        var closed=request(path,Map.of("version",3,"action","CLOSE","note","review complete"));
        assertEquals(200,closed.statusCode(),closed.body());
        var result=json.readTree(closed.body());
        assertEquals("CLOSED",result.get("status").asString());
        assertEquals("review complete",result.get("review").asString());
        assertEquals(5,result.get("timeline").size());
    }
}
