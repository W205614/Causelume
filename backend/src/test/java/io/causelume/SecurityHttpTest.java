package io.causelume;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:security;DB_CLOSE_DELAY=-1","APP_PUBLIC_ORIGIN=https://ops.example",
    "APP_ADMIN_PASSWORD=test-admin-secret","APP_OPERATOR_PASSWORD=test-operator-secret"})
class SecurityHttpTest {
    static com.sun.net.httpserver.HttpServer peer;
    static final java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
    @org.springframework.test.context.DynamicPropertySource
    static void configure(org.springframework.test.context.DynamicPropertyRegistry props) throws Exception {
        peer=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        peer.createContext("/investigate",exchange -> {
            try { release.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.sendResponseHeaders(504,-1);
            exchange.close();
        });
        peer.start();
        props.add("AGENT_URL",()->"http://127.0.0.1:"+peer.getAddress().getPort());
        props.add("AGENT_TOKEN",()->"test-token");
    }
    @org.junit.jupiter.api.AfterAll static void stop() {
        release.countDown();
        peer.stop(0);
    }
    @LocalServerPort int port;
    @Autowired Store store;
    final HttpClient http=HttpClient.newHttpClient();
    final ObjectMapper json=new ObjectMapper();
    HttpRequest.Builder route(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/"+path)).header("Content-Type","application/json");
    }
    HttpResponse<String> send(HttpRequest req) throws Exception {
        return http.send(req,HttpResponse.BodyHandlers.ofString());
    }
    HttpRequest.Builder post(String path,Object body) {
        return route(path).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    }
    @Test void proxyCookieBodyLimitsAdmissionAndConcurrentRequests() throws Exception {
        var login=Map.of("username","admin","password","test-admin-secret");
        var denied=send(post("login",login).header("Origin","https://evil.example")
                .header("X-Forwarded-Proto","https").header("X-Forwarded-Host","evil.example").build());
        assertEquals(403,denied.statusCode());
        var success=send(post("login",login).header("Origin","https://ops.example").build());
        assertEquals(200,success.statusCode(),success.body());
        String cookie=success.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookie.contains("Secure") && cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"),cookie);
        String session=cookie.split(";",2)[0];
        var payload=new byte[ApiBodyLimit.MAX_BYTES+1];
        for (var body : new HttpRequest.BodyPublisher[]{HttpRequest.BodyPublishers.ofByteArray(payload),
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(payload))}) {
            assertEquals(413,send(route("login").POST(body).build()).statusCode());
        }
        var first=send(route("users?limit=1").header("Cookie",session).GET().build());
        String cursor=first.headers().firstValue("X-Next-Cursor").orElseThrow();
        var second=send(route("users?limit=1&afterId="+cursor).header("Cookie",session).GET().build());
        assertEquals("operator",json.readTree(second.body()).get(0).get("username").asString());
        assertTrue(second.headers().firstValue("X-Next-Cursor").isEmpty());

        var ticket=post("tickets",Map.of("serviceId",1,"title","parallel","requestId",UUID.randomUUID()))
                .header("Cookie",session).build();
        var a=http.sendAsync(ticket,HttpResponse.BodyHandlers.ofString());
        var b=http.sendAsync(ticket,HttpResponse.BodyHandlers.ofString());
        assertEquals(200,a.join().statusCode());
        assertEquals(200,b.join().statusCode());
        long id=json.readTree(a.join().body()).get("id").asLong();
        assertEquals(id,json.readTree(b.join().body()).get("id").asLong());
        var create=post("tickets/"+id+"/actions",Map.of("version",0,"action","ASSIGN","note","work","assigneeId",1))
                .header("Cookie",session).build();
        assertEquals(200,send(create).statusCode());
        var t1=post("tasks",Map.of("ticketId",id,"requestId",UUID.randomUUID())).header("Cookie",session).build();
        var t2=post("tasks",Map.of("ticketId",id,"requestId",UUID.randomUUID())).header("Cookie",session).build();
        var ta=http.sendAsync(t1,HttpResponse.BodyHandlers.ofString());
        var tb=http.sendAsync(t2,HttpResponse.BodyHandlers.ofString());
        assertEquals(Set.of(200,409),Set.of(ta.join().statusCode(),tb.join().statusCode()));

        long fullTicket=store.insert("insert into tickets(service_id,title,status,creator_id,assignee_id,request_id) values (1,'capacity','ASSIGNED',1,1,random_uuid())");
        for(int i=0;i<32;i++)
            store.insert("insert into tasks(ticket_id,creator_id,request_id,status,started_at) values (?,1,random_uuid(),'RUNNING',current_timestamp)",id);
        assertEquals(429,send(post("tasks",Map.of("ticketId",fullTicket,"requestId",UUID.randomUUID())).header("Cookie",session).build()).statusCode());

        var badKnown=send(post("login",Map.of("username","admin","password","wrong")).build());
        var badUnknown=send(post("login",Map.of("username","missing","password","wrong")).build());
        assertEquals(401,badKnown.statusCode());
        assertEquals(badKnown.body(),badUnknown.body());
        for(int i=0;i<7;i++) assertEquals(401,send(post("login",Map.of("username","missing","password","wrong")).build()).statusCode());
        assertEquals(429,send(post("login",login).header("X-Forwarded-For","203.0.113.12").build()).statusCode());
        assertEquals(1L,store.db.queryForObject("select count(*) from audit where action='LOGIN_LIMITED'",Long.class));
    }
}
