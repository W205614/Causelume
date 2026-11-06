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
                json.writeValueAsString(Map.of("action","NOTE","version",0,"note","new observation")))).build();
        var a=http.sendAsync(race,HttpResponse.BodyHandlers.ofString());
        var b=http.sendAsync(race,HttpResponse.BodyHandlers.ofString());
        assertEquals(java.util.Set.of(200,409),java.util.Set.of(a.join().statusCode(),b.join().statusCode()));
        assertEquals(2,json.readTree(request("tickets/"+id,null).body()).get("timeline").size());
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
