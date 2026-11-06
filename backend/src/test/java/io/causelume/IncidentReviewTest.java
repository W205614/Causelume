package io.causelume;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:incident-review;DB_CLOSE_DELAY=-1",
    "APP_ADMIN_PASSWORD=test-admin-secret","APP_OPERATOR_PASSWORD=test-operator-secret"})
class IncidentReviewTest {
    @LocalServerPort int port;
    @Autowired Store store;
    final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
    final ObjectMapper json=new ObjectMapper();
    HttpResponse<String> call(String path,Object data) throws Exception{
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/"+path));
        if(data!=null)req.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data)));
        return http.send(req.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(String path,Object data)throws Exception{
        var response=call(path,data);assertEquals(200,response.statusCode(),response.body());return json.readTree(response.body());
    }
    void login(String user)throws Exception{ok("login",Map.of("username",user,"password","test-"+user+"-secret"));}
    String path;
    JsonNode act(String action,Map<String,Object> extras)throws Exception{
        var body=new HashMap<>(extras);
        body.put("action",action);body.put("version",ok(path,null).get("version").asLong());body.put("note","operator observed a probe");
        return ok(path+"/actions",body);
    }
    @Test void incidentReviewAccessAndImmutableHistory()throws Exception{
        login("admin");
        var service=ok("services",Map.of("name","review-test","description","test","resource","http"));
        long sid=service.get("id").asLong();
        var settings=new HashMap<String,Object>(Map.of("version",0,"active",true,"ownerId",2,"environment","test",
            "name","review-test","description","test","resource","http","runbook","https://example.invalid/runbook"));
        assertEquals(1,ok("services/"+sid,settings).get("version").asLong());
        assertEquals(409,call("services/"+sid,settings).statusCode());
        settings.put("version",1);settings.put("runbook","javascript:alert(1)");
        assertEquals(400,call("services/"+sid,settings).statusCode());

        var created=ok("tickets",Map.of("serviceId",sid,"requestId",UUID.randomUUID(),"title","checkout failed","priority","P1"));
        path="tickets/"+created.get("id").asLong();
        login("operator");assertEquals(403,call(path+"/export",null).statusCode());
        login("admin");act("ASSIGN",Map.of("assigneeId",2));
        login("operator");act("START",Map.of());
        for(String result:new String[]{"UNKNOWN","FAILED"})
            assertEquals("PROCESSING",act("VERIFY",Map.of("verification",result)).get("status").asString());
        assertEquals("VERIFIED",act("VERIFY",Map.of("verification","PASSED")).get("status").asString());
        var review=Map.of("impact","payment backlog","startedAt","2026-05-01T10:00:00Z","endedAt","2026-05-01T10:15:00Z",
            "cause","saturated upstream pool","uncertainty","diagnosis pending traffic replay");
        assertEquals("CLOSED",act("CLOSE",Map.of("postmortem",review)).get("status").asString());
        var item=ok(path+"/improvements",Map.of("ownerId",2,"dueDate","2026-07-10","title","monitor upstream capacity"));
        ok(path+"/improvements/"+item.get("id").asLong(),Map.of("status","DONE"));
        assertEquals(1,ok("tickets/page?q=saturated&priority=P1",null).get("total").asLong());
        assertEquals(2,ok(path+"/timeline?page=2&size=2",null).get("items").size());
        store.insert("insert into tasks(ticket_id,creator_id,request_id,status,analysis) values (?,?,?,'SUCCEEDED',?)",
            created.get("id").asLong(),2,UUID.randomUUID(),"{\"privateTrace\":\"excluded-sample\"}");
        var report=ok(path+"/export?format=json",null);
        assertEquals("DONE",report.get("improvements").get(0).get("status").asString());
        assertEquals(1,report.get("investigations").size());
        assertFalse(report.toString().contains("excluded-sample"));
        var markdown=call(path+"/export?format=markdown",null);assertEquals(200,markdown.statusCode());assertTrue(markdown.body().contains("saturated upstream pool"));
        assertEquals(403,call("retention/preview",null).statusCode());
        assertEquals("PROCESSING",act("REOPEN",Map.of()).get("status").asString());
        assertEquals("P2",act("PRIORITY",Map.of("priority","P2")).get("priority").asString());
        login("admin");settings.put("active",false);settings.put("runbook","https://example.invalid/guide");ok("services/"+sid,settings);
        assertEquals(409,call("tickets",Map.of("serviceId",sid,"requestId",UUID.randomUUID(),"title","new")).statusCode());
        assertTrue(ok("retention/preview",null).get("previewOnly").asBoolean());
        var deletion=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/retention/preview"))
            .header("Content-Type","application/json").DELETE().build();
        assertEquals(405,http.send(deletion,HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals("P2",ok(path,null).get("priority").asString());
    }
}
