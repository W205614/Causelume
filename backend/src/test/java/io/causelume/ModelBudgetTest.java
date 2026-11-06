package io.causelume;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:budget",
    "APP_ADMIN_PASSWORD=test-admin-secret","APP_OPERATOR_PASSWORD=test-operator-secret",
    "AGENT_MODE=holmes","MODEL_DAILY_BUDGET_USD=1.00","MODEL_RESERVATION_USD=0.60"})
@Transactional
class ModelBudgetTest {
    @Autowired ModelBudgets budgets;
    @Autowired Store store;

    @Test void unknownCallsKeepTheirReservationAndReportedUsageSettles() {
        long ticket=store.insert("insert into tickets(service_id,title,status,creator_id,request_id) values(1,'budget','ASSIGNED',1,?)",UUID.randomUUID());
        long first=task(ticket),second=task(ticket);
        budgets.reserve(first);
        var rejected=assertThrows(Problem.class,()->budgets.reserve(second));
        assertEquals(429,rejected.status);
        store.db.update("update tasks set status='FAILED' where id=?",first);
        budgets.reconcileFinished();
        assertEquals("UNKNOWN",store.one("select state from model_reservations where task_id=?",first).get("state"));
        assertThrows(Problem.class,()->budgets.reserve(second));
        store.db.update("update model_reservations set state='SETTLED',actual_usd=0.1 where task_id=?",first);
        budgets.reserve(second);
        store.db.update("update tasks set status='SUCCEEDED',analysis=? where id=?","{\"usage\":{\"costUsd\":0.2}}",second);
        budgets.reconcileFinished();
        assertEquals(0,new BigDecimal("0.2").compareTo((BigDecimal)store.one("select actual_usd from model_reservations where task_id=?",second).get("actualUsd")));
    }

    private long task(long ticket) {
        return store.insert("insert into tasks(ticket_id,creator_id,request_id,status) values(?,1,?,'PENDING')",ticket,UUID.randomUUID());
    }
}
