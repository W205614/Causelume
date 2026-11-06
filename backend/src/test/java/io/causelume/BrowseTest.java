package io.causelume;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:browse",
    "APP_ADMIN_PASSWORD=test-admin-secret", "APP_OPERATOR_PASSWORD=test-operator-secret"})
@Transactional
class BrowseTest {
    @Autowired Browse browse;
    @Autowired Store store;

    @Test void pageTotalsAndLinksUseTheWholeFilteredDataset() {
        long secondService = store.insert("insert into services(name, description, resource) values (?,?,?)", "payments", "test", "test");
        long alert = store.insert("insert into alerts(service_id,source,external_id,severity,title,occurred_at) values (1,'probe','a','CRITICAL','slow',current_timestamp)");
        long first = ticket("Pool 100% unavailable", 1, alert);
        long second = ticket("Pool busy", 1, null);
        ticket("Other service", secondService, null);

        var page = browse.tickets(2, 1, "OPEN", 1L, 1L, "pool");
        assertEquals(2, page.total());
        assertEquals(first, page.items().getFirst().get("id"));
        assertEquals(second, browse.tickets(1, 1, null, null, null, "#" + second).items().getFirst().get("id"));
        assertEquals(1, browse.tickets(1, 20, null, null, null, "%").total());
        assertTrue(browse.tickets(3, 1, null, 1L, null, null).items().isEmpty());
        var linked = browse.alerts(1, 1, "CRITICAL", 1L).items().getFirst();
        assertEquals(first, linked.get("ticketId"));
        assertEquals("checkout", linked.get("serviceName"));

        store.insert("insert into tasks(ticket_id,creator_id,request_id,status) values (?,1,?,'PENDING')", first, UUID.randomUUID());
        store.insert("insert into tasks(ticket_id,creator_id,request_id,status,summary,analysis) values (?,1,?,'SUCCEEDED',?,?)",
            second, UUID.randomUUID(), "saved summary", "{\"evidence\":[]}");
        var item = browse.tasks(1, 1, "SUCCEEDED", second, 1L).items().getFirst();
        assertEquals("saved summary", item.get("summary"));
        assertFalse(item.containsKey("analysis"));
        assertFalse(item.containsKey("result"));
        var overview = browse.overview();
        assertEquals(3L, overview.get("openTickets"));
        assertEquals(1L, overview.get("activeTasks"));
        assertEquals(1L, ((Map<?,?>)overview.get("taskCounts")).get("PENDING"));
    }

    @Test void invalidFiltersAndPageSizesFailAtTheBoundary() {
        assertThrows(Problem.class, () -> browse.tickets(1, 101, null, null, null, null));
        assertThrows(Problem.class, () -> browse.alerts(0, 20, "CRITICAL", null));
        assertThrows(Problem.class, () -> browse.tasks(1, 20, "WRONG", null, null));
        assertThrows(Problem.class, () -> browse.tickets(1, 20, null, -1L, null, null));
    }

    private long ticket(String title, long service, Long alert) {
        return store.insert("insert into tickets(service_id,alert_id,title,status,creator_id,request_id,assignee_id) values (?,?,?,'OPEN',1,?,1)",
            service, alert, title, UUID.randomUUID());
    }
}
