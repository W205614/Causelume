package io.causelume;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:investigation;DB_CLOSE_DELAY=-1",
    "APP_ADMIN_PASSWORD=test-admin-secret","APP_OPERATOR_PASSWORD=test-operator-secret","AGENT_TOKEN=test-token"})
class InvestigationTest {
    static final ObjectMapper json = new ObjectMapper();
    static HttpServer upstream;
    static final AtomicInteger calls = new AtomicInteger();
    static volatile boolean broken;
    @LocalServerPort int port;
    final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/investigate",exchange -> {
            var request = json.readTree(exchange.getRequestBody().readAllBytes());
            calls.incrementAndGet();
            int status = "Bearer test-token".equals(exchange.getRequestHeaders().getFirst("Authorization")) ? 200 : 401;
            var body = json.writeValueAsBytes(Map.of("taskId",request.get("taskId"), "mode","offline",
                "evidence",new Object[]{Map.of("source","probe","detail","HTTP 503")},
                "possibleCause","test cause","uncertainty","limited samples","recommendation","verify"));
            if (broken) status = 504;
            exchange.sendResponseHeaders(status,body.length);
            try (var output=exchange.getResponseBody()) { output.write(body); }
        });
        upstream.start();
        properties.add("AGENT_URL",() -> "http://127.0.0.1:"+upstream.getAddress().getPort());
    }
    @AfterAll static void shutdown() { if(upstream!=null) upstream.stop(0); }

    HttpResponse<String> call(String path,Object body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/"+path));
        if (body!=null) req.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(req.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(String path,Object body) throws Exception {
        var response=call(path,body);
        assertEquals(200,response.statusCode(),response.body());
        return json.readTree(response.body());
    }
    JsonNode await(long ticketId,long taskId) throws Exception {
        for(int i=0;i<100;i++) {
            var task = ok("tasks/"+taskId,null);
            assertEquals(ticketId,task.get("ticketId").asLong());
            if (java.util.Set.of("SUCCEEDED","FAILED").contains(task.get("status").asString())) return task;
            Thread.sleep(100);
        }
        throw new AssertionError("investigation did not finish");
    }
    @Test void permissionsCorrelationAndFailure() throws Exception {
        ok("login",Map.of("username","admin","password","test-admin-secret"));
        long id=ok("tickets",Map.of("serviceId",1,"title","investigate","requestId",UUID.randomUUID())).get("id").asLong();
        var body=Map.of("ticketId",id,"requestId",UUID.randomUUID());
        assertEquals(409,call("tasks",body).statusCode());
        ok("tickets/"+id+"/actions",Map.of("version",0,"action","ASSIGN","assigneeId",1,"note","on call"));
        ok("login",Map.of("username","operator","password","test-operator-secret"));
        assertEquals(403,call("tasks",body).statusCode());
        ok("login",Map.of("username","admin","password","test-admin-secret"));
        long task=ok("tasks",body).get("id").asLong();
        assertEquals(task,ok("tasks",body).get("id").asLong());
        var success=await(id,task);
        assertEquals("SUCCEEDED",success.get("status").asString());
        assertEquals("probe",success.get("result").get("evidence").get(0).get("source").asString());
        ok("tasks/"+task+"/feedback",Map.of("feedback","checked"));
        assertEquals(409,call("tasks/"+task+"/feedback",Map.of("feedback","overwrite")).statusCode());
        broken=true;
        long failed=ok("tasks",Map.of("ticketId",id,"requestId",UUID.randomUUID())).get("id").asLong();
        var record=await(id,failed);
        assertEquals("FAILED",record.get("status").asString());
        assertTrue(record.get("error").asString().contains("超时"));
        assertTrue(record.get("result").isNull());
        assertEquals(2,calls.get());
    }
}
