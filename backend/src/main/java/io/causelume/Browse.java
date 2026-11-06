package io.causelume;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class Browse {
    static final Set<String> TICKET_STATES = Set.of("OPEN", "ASSIGNED", "PROCESSING", "VERIFIED", "CLOSED");
    static final Set<String> TASK_STATES = Set.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED");
    static final Set<String> SEVERITIES = Set.of("INFO", "WARNING", "CRITICAL");
    private static final String TICKET_FROM = "tickets k join services s on s.id=k.service_id left join accounts u on u.id=k.assignee_id";
    private static final String TICKET_FIELDS = "k.id,k.service_id,k.alert_id,k.title,k.status,k.priority,k.verification,k.assignee_id,k.version,k.created_at,s.name as service_name,u.username as assignee_name";
    private static final String ALERT_FROM = "alerts a join services s on s.id=a.service_id left join tickets k on k.alert_id=a.id";
    private static final String ALERT_FIELDS = "a.*,s.name as service_name,k.id as ticket_id";
    private static final String TASK_FROM = "tasks t join tickets k on k.id=t.ticket_id join services s on s.id=k.service_id";
    private static final String TASK_FIELDS = "t.id,t.ticket_id,t.status,t.mode,t.created_at,t.started_at,t.finished_at,t.error_code,coalesce(t.summary,left(t.error,240)) as summary,k.title as ticket_title,s.name as service_name";
    private final Store store;

    Browse(Store store) { this.store = store; }

    record Page(List<Map<String,Object>> items, long total, int page, int size) {}

    private static class Filters {
        final List<String> clauses = new ArrayList<>();
        final List<Object> values = new ArrayList<>();

        void id(String column, Long value) {
            if (value != null) {
                if (value < 1) throw new Problem(400, "筛选编号必须为正整数");
                clauses.add(column + "=?");
                values.add(value);
            }
        }

        void code(String column, String value, Set<String> choices) {
            if (value != null) {
                clauses.add(column + "=?");
                values.add(Problem.choice(value, choices, "筛选条件"));
            }
        }
    }

    private Page query(String fields, String source, String order, Filters filters, int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new Problem(400, "页码从 1 开始，每页 1 至 100 条");
        String from = " from " + source;
        if (!filters.clauses.isEmpty()) from += " where " + String.join(" and ", filters.clauses);
        long total = store.db.queryForObject("select count(*)" + from, Long.class, filters.values.toArray());
        filters.values.add(size);
        filters.values.add((long) (page - 1) * size);
        var items = store.rows("select " + fields + from + " order by " + order + " desc limit ? offset ?", filters.values.toArray());
        return new Page(items, total, page, size);
    }

    Page tickets(int page,int size,String status,Long serviceId,Long assigneeId,String q) {
        return tickets(page,size,status,serviceId,assigneeId,q,null);
    }

    @GetMapping("/tickets/page")
    public Page tickets(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size,
                        @RequestParam(required=false) String status, @RequestParam(required=false) Long serviceId,
                        @RequestParam(required=false) Long assigneeId, @RequestParam(required=false) String q,
                        @RequestParam(required=false) String priority) {
        var filters = new Filters();
        filters.id("k.service_id", serviceId);
        filters.id("k.assignee_id", assigneeId);
        filters.code("k.status", status, TICKET_STATES);
        filters.code("k.priority",priority,Tickets.PRIORITIES);
        if (q != null && !q.isBlank()) {
            String term = Problem.text(q, 200, "q");
            filters.clauses.add("""
                (position(lower(?) in lower(concat_ws(' ',k.title,k.review,k.impact,k.cause,k.uncertainty)))>0
                 or cast(k.id as varchar)=?)
                """);
            filters.values.add(term);
            filters.values.add(term.startsWith("#") ? term.substring(1) : term);
        }
        return query(TICKET_FIELDS, TICKET_FROM, "k.id", filters, page, size);
    }

    @GetMapping("/alerts/page")
    public Page alerts(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size,
                       @RequestParam(required=false) String severity, @RequestParam(required=false) Long serviceId) {
        var filters = new Filters();
        filters.id("a.service_id", serviceId);
        filters.code("a.severity", severity, SEVERITIES);
        return query(ALERT_FIELDS, ALERT_FROM, "a.id", filters, page, size);
    }

    @GetMapping("/tasks/page")
    public Page tasks(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size,
                      @RequestParam(required=false) String status, @RequestParam(required=false) Long ticketId,
                      @RequestParam(required=false) Long serviceId) {
        var filters = new Filters();
        filters.id("t.ticket_id", ticketId);
        filters.id("k.service_id", serviceId);
        filters.code("t.status", status, TASK_STATES);
        return query(TASK_FIELDS, TASK_FROM, "t.id", filters, page, size);
    }

    private Map<String, Long> counts(String table, String field, Set<String> keys) {
        var result = new LinkedHashMap<String, Long>();
        keys.forEach(key -> result.put(key, 0L));
        for (var row : store.rows("select " + field + " as code,count(*) as n from " + table + " group by " + field)) {
            result.put((String)row.get("code"), (Long)row.get("n"));
        }
        return result;
    }

    @GetMapping("/overview")
    public Map<String,Object> overview() {
        var alerts = counts("alerts", "severity", SEVERITIES);
        var tasks = counts("tasks", "status", TASK_STATES);
        return Map.of("openTickets", store.db.queryForObject("select count(*) from tickets where status<>'CLOSED'", Long.class),
            "criticalAlerts", alerts.get("CRITICAL"), "activeTasks", tasks.get("PENDING") + tasks.get("RUNNING"),
            "services", store.db.queryForObject("select count(*) from services", Long.class),
            "alertCounts", alerts, "taskCounts", tasks,
            "recentTickets", store.rows("select " + TICKET_FIELDS + " from " + TICKET_FROM + " order by k.id desc limit 5"),
            "recentAlerts", store.rows("select " + ALERT_FIELDS + " from " + ALERT_FROM + " order by a.id desc limit 5"));
    }
}
