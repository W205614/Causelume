package io.causelume;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:delivery-permissions;DB_CLOSE_DELAY=-1","APP_ADMIN_PASSWORD=test-admin-secret","APP_OPERATOR_PASSWORD=test-operator-secret"})
class DeliveryPermissionsTest {
    @LocalServerPort int port;
    @Autowired Store store;
    final ObjectMapper json=new ObjectMapper();
    final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
    HttpResponse<String> call(String path,Object input)throws Exception{
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/"+path));
        if(input!=null)req.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input)));
        return http.send(req.build(),HttpResponse.BodyHandlers.ofString());
    }
    void login(String username)throws Exception{
        assertEquals(200,call("login",Map.of("username",username,"password","test-"+username+"-secret")).statusCode());
    }
    @Test void onlyRecipientReadsAndAdminExplicitlyResendsUnknown()throws Exception{
        long id=store.insert("""
            insert into notifications(recipient_id,event_type,message,mail_to,mail_status)
            values ((select id from accounts where username='operator'),'TEST','human review','ops@example.invalid','UNKNOWN')
            """);
        login("operator");
        assertEquals(1,json.readTree(call("notifications",null).body()).get("items").size());
        assertEquals(403,call("notifications?scope=delivery",null).statusCode());
        assertEquals(200,call("notifications/"+id+"/read",Map.of()).statusCode());
        assertEquals(403,call("notifications/"+id+"/resend",Map.of("reason","try")).statusCode());
        login("admin");
        assertEquals(404,call("notifications/"+id+"/read",Map.of()).statusCode());
        assertEquals(id,json.readTree(call("notifications?scope=delivery",null).body()).get("items").get(0).get("id").asLong());
        assertEquals(400,call("notifications/"+id+"/resend",Map.of("reason","")).statusCode());
        assertEquals(200,call("notifications/"+id+"/resend",Map.of("reason","local sink confirmed no receipt")).statusCode());
        assertEquals(409,call("notifications/"+id+"/resend",Map.of("reason","duplicate")).statusCode());
        assertEquals("admin",store.one("select actor from mail_resends where notification_id=?",id).get("actor"));
    }
}
