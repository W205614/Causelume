package io.causelume;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:label-routing;DB_CLOSE_DELAY=-1",
    "APP_ADMIN_PASSWORD=test-admin-secret","APP_OPERATOR_PASSWORD=test-operator-secret",
    "ALERTMANAGER_TOKEN=test-machine-secret"})
class AlertLabelRoutingTest {
    @LocalServerPort int port;
    @Autowired Store store;
    final HttpClient client=HttpClient.newHttpClient();
    final ObjectMapper json=new ObjectMapper();

    HttpResponse<String> fire(String fingerprint,String service,String environment) throws Exception {
        var labels=new HashMap<>(Map.of("service",service,"alertname","Metrics unavailable"));
        if(environment!=null) labels.put("environment",environment);
        var alert=Map.of("status","firing","fingerprint",fingerprint,"startsAt","2026-01-01T00:00:00Z","labels",labels);
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/integrations/alertmanager/primary"))
            .header("Authorization","Bearer test-machine-secret").header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("alerts",List.of(alert))))).build();
        return client.send(request,HttpResponse.BodyHandlers.ofString());
    }

    @Test void routesByCollectorLabelsAndRejectsAmbiguousBindings() throws Exception {
        String insert="insert into services (name,description,resource,service_label,environment) values (?,'API','metrics','payments',?)";
        long prod=store.insert(insert,"Payment gateway","prod");
        assertEquals(200,fire("legacy","payments",null).statusCode());
        assertEquals(prod,store.one("select service_id from alerts where fingerprint='legacy'").get("serviceId"));
        long stage=store.insert(insert,"Payment staging","stage");
        assertEquals(200,fire("staging","payments","stage").statusCode());
        assertEquals(stage,store.one("select service_id from alerts where fingerprint='staging'").get("serviceId"));
        assertEquals(404,fire("display","Payment gateway","prod").statusCode());
        assertEquals(404,fire("unknown","payments","test").statusCode());
        var missing=fire("missing","payments",null);
        assertEquals(409,missing.statusCode());
        assertTrue(missing.body().contains("environment"));
        store.insert(insert,"Payment mirror","prod");
        assertEquals(409,fire("duplicate","payments","prod").statusCode());
        assertEquals(2L,store.db.queryForObject("select count(*) from alerts",Long.class));
    }
}
