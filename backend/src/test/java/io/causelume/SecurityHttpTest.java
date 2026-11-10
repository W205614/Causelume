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
    "APP_ADMIN_PASSWORD=test-admin-secret","APP_OPERATOR_PASSWORD=test-operator-secret",
    "MANAGEMENT_TOKEN=test-management-token"})
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
    @Autowired Accounts accounts;
    @Autowired Access access;
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
    @Test void managementTokenCoversEncodedAndMatrixPaths() throws Exception {
        for (String prefix : new String[]{"/actuator","/actuat%6fr","/%61ctuator","/actuator;ignored","/actuat%6fr;ignored"}) {
            for (String endpoint : new String[]{"/health","/prometheus"}) {
                URI uri=URI.create("http://127.0.0.1:"+port+prefix+endpoint);
                assertEquals(401,send(HttpRequest.newBuilder(uri).GET().build()).statusCode(),uri.toString());
                assertEquals(401,send(HttpRequest.newBuilder(uri).header("Authorization","Bearer invalid").GET().build()).statusCode(),uri.toString());
                var authorized=send(HttpRequest.newBuilder(uri).header("Authorization","Bearer test-management-token").GET().build());
                assertEquals(200,authorized.statusCode(),uri.toString());
                assertTrue(authorized.body().contains(endpoint.equals("/health") ? "\"status\":\"UP\"" : "jvm_"),authorized.body());
            }
        }
    }
    @Test void sessionExemptionsFollowResolvedEndpoints() throws Exception {
        for (String prefix : new String[]{"/api","/a%70i","/api;ignored"}) {
            URI health=URI.create("http://127.0.0.1:"+port+prefix+"/health");
            assertEquals(200,send(HttpRequest.newBuilder(health).GET().build()).statusCode());
            URI users=URI.create("http://127.0.0.1:"+port+prefix+"/users");
            assertEquals(401,send(HttpRequest.newBuilder(users).GET().build()).statusCode());
        }
    }
    @Test @org.springframework.transaction.annotation.Transactional
    void revokedAdminCannotCompleteAnAlreadyAdmittedAccountWrite() {
        long id=store.insert("insert into accounts(username,password_hash,role) values ('race-admin','unused','ADMIN')");
        var req=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/users");
        req.setContentType("application/json");
        long version=store.db.queryForObject("select auth_version from accounts where id=?",Long.class,id);
        req.getSession().setAttribute("actor",new Actor(id,"race-admin","ADMIN",null,false,false,version));
        assertTrue(access.preHandle(req,new org.springframework.mock.web.MockHttpServletResponse(),accounts));
        // Model a revocation committing after interceptor admission and before the handler obtains its locks.
        store.db.update("update accounts set active=false,auth_version=auth_version+1 where id=?",id);
        assertEquals(401,assertThrows(Problem.class,()->accounts.user(
            new Accounts.Account("unauthorized","secret-password","ADMIN",null),req)).status);
        assertEquals(401,assertThrows(Problem.class,()->accounts.reset(1,new Accounts.Reset("secret-password"),req)).status);
        assertEquals(0L,store.db.queryForObject("select count(*) from accounts where username='unauthorized'",Long.class));
    }
    @Test void proxyCookieBodyLimitsAdmissionAndConcurrentRequests() throws Exception {
        var login=Map.of("username","admin","password","test-admin-secret");
        var denied=send(post("login",login).header("Origin","https://evil.example")
                .header("X-Forwarded-Proto","https").header("X-Forwarded-Host","evil.example").build());
        assertEquals(403,denied.statusCode());
        var success=send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/a%70i/login"))
            .header("Content-Type","application/json").header("Origin","https://ops.example")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(login))).build());
        assertEquals(200,success.statusCode(),success.body());
        String cookie=success.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookie.contains("Secure") && cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"),cookie);
        String session=cookie.split(";",2)[0];
        var payload=new byte[ApiBodyLimit.MAX_BYTES+1];
        for (String prefix : new String[]{"/api","/a%70i","/api;ignored","/a%70i;ignored"}) {
            for (var body : new HttpRequest.BodyPublisher[]{HttpRequest.BodyPublishers.ofByteArray(payload),
                    HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(payload))}) {
                URI uri=URI.create("http://127.0.0.1:"+port+prefix+"/login");
                assertEquals(413,send(HttpRequest.newBuilder(uri).header("Content-Type","application/json")
                    .header("Cookie",session).POST(body).build()).statusCode(),uri.toString());
            }
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

        var other=send(post("login",login).build());
        String otherSession=other.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
        assertEquals(200,send(post("account/profile",Map.of("email","test-oncall@example.test")).header("Cookie",session).build()).statusCode());
        var me=send(route("me").header("Cookie",otherSession).GET().build());
        assertEquals("test-oncall@example.test",json.readTree(me.body()).get("email").asString());
        for(int i=0;i<5;i++){
            assertEquals(403,send(post("account/mfa/enroll",Map.of("password","wrong")).header("Cookie",session).build()).statusCode());
            assertEquals(403,send(post("account/password",Map.of("currentPassword","wrong","newPassword","new-secret-password"))
                .header("Cookie",otherSession).build()).statusCode());
        }
        for(String sid:new String[]{session,otherSession})
            assertEquals(429,send(post("account/mfa/enroll",Map.of("password","test-admin-secret")).header("Cookie",sid).build()).statusCode());
        assertEquals(10L,store.db.queryForObject("select count(*) from audit where action='REAUTH_DENIED' and target_id=1",Long.class));
        assertEquals(1L,store.db.queryForObject("select count(*) from audit where action='REAUTH_LIMITED' and target_id=1",Long.class));

        var badKnown=send(post("login",Map.of("username","admin","password","wrong")).build());
        var badUnknown=send(post("login",Map.of("username","missing","password","wrong")).build());
        assertEquals(401,badKnown.statusCode());
        assertEquals(badKnown.body(),badUnknown.body());
        for(int i=0;i<6;i++) assertEquals(401,send(post("login",Map.of("username","missing","password","wrong")).build()).statusCode());
        assertEquals(429,send(post("login",login).header("X-Forwarded-For","203.0.113.12").build()).statusCode());
        assertEquals(1L,store.db.queryForObject("select count(*) from audit where action='LOGIN_LIMITED'",Long.class));
    }
}
