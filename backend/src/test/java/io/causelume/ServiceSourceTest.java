package io.causelume;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:source-settings;DB_CLOSE_DELAY=-1","APP_ADMIN_PASSWORD=test-admin-secret",
    "APP_OPERATOR_PASSWORD=test-operator-secret","AGENT_TOKEN=local-read-only"})
class ServiceSourceTest {
    static final AtomicInteger reads=new AtomicInteger();
    static final AtomicReference<String> last=new AtomicReference<>();
    static final HttpServer registry;
    static {
        try {
            registry=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            registry.createContext("/sources",exchange->{
                boolean allowed="Bearer local-read-only".equals(exchange.getRequestHeaders().getFirst("Authorization"));
                last.set(exchange.getRequestURI().toString());reads.incrementAndGet();
                var response=(last.get().equals("/sources")
                    ? "{\"sources\":[{\"id\":\"prod-metrics\",\"name\":\"Production\",\"type\":\"observability\"}]}"
                    : "{\"sourceId\":\"prod-metrics\",\"available\":true,\"checks\":[{\"source\":\"prometheus\",\"status\":\"ok\",\"detail\":\"ok\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(allowed?200:403,response.length);
                try(var stream=exchange.getResponseBody()){stream.write(response);}
            });
            registry.start();
        } catch(IOException e){throw new UncheckedIOException(e);}
    }
    @DynamicPropertySource static void settings(DynamicPropertyRegistry env){env.add("AGENT_URL",()->"http://127.0.0.1:"+registry.getAddress().getPort());}
    @AfterAll static void stop(){registry.stop(0);}
    @LocalServerPort int port;
    final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
    final ObjectMapper json=new ObjectMapper();
    HttpResponse<String> call(String path,Object input)throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/"+path));
        if(input!=null)req.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input)));
        return http.send(req.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void configuredCatalogOnlyAndServerSideChecks()throws Exception{
        assertEquals(200,call("login",Map.of("username","operator","password","test-operator-secret")).statusCode());
        assertEquals(403,call("sources",null).statusCode());assertEquals(0,reads.get());
        assertEquals(200,call("login",Map.of("username","admin","password","test-admin-secret")).statusCode());
        assertEquals(200,call("sources",null).statusCode());
        var settings=new java.util.HashMap<String,Object>(Map.of("version",0,"active",true,
            "name","checkout","description","test","resource","HTTP","sourceId","unapproved",
            "serviceLabel","payment.api","environment","prod"));
        assertEquals(400,call("services/1",settings).statusCode());settings.put("sourceId","prod-metrics");
        assertEquals(200,call("services/1",settings).statusCode());
        var check=call("services/1/source-check",null);assertEquals(200,check.statusCode());assertTrue(json.readTree(check.body()).get("available").asBoolean());
        assertEquals("/sources/prod-metrics/check?service=payment.api&environment=prod",last.get());
        assertEquals(422,call("services/1/metrics",null).statusCode());
    }
}
