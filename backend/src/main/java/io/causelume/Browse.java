package io.causelume;

import jakarta.servlet.http.HttpServletRequest;
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
    private static final String WORK_ORDER = "k.priority asc,k.created_at asc,k.id asc";
    static final Set<String> TICKET_STATES = Set.of("OPEN", "ASSIGNED", "PROCESSING", "VERIFIED", "CLOSED");
    static final Set<String> TASK_STATES = Set.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED");
    static final Set<String> SEVERITIES = Set.of("INFO", "WARNING", "CRITICAL");
    private static final String TICKET_FROM = "tickets k join services s on s.id=k.service_id left join accounts u on u.id=k.assignee_id";
    private static final String TICKET_FIELDS = "k.id,k.service_id,k.alert_id,k.title,k.status,k.priority,k.verification,k.assignee_id,k.version,k.created_at,s.name as service_name,s.team_id,u.username as assignee_name";
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

        Filters scoped(Actor actor,Long team) {
            var scope=TeamAccess.scope(actor,"s.team_id",team);
            clauses.add(scope.sql());
            values.addAll(scope.args());
            return this;
        }

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
        var items = store.rows("select " + fields + from + " order by " + order + " limit ? offset ?", filters.values.toArray());
        return new Page(items, total, page, size);
    }

    Page tickets(int page,int size,String status,Long serviceId,Long assigneeId,String q) {
        return tickets(page,size,status,serviceId,assigneeId,q,null,new Filters(),null);
    }

    @GetMapping("/tickets/page")
    public Page tickets(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size,
                        @RequestParam(required=false) String status, @RequestParam(required=false) Long serviceId,
                        @RequestParam(required=false) Long assigneeId, @RequestParam(required=false) String q,
                        @RequestParam(required=false) String priority, @RequestParam(required=false) String scope,
                        @RequestParam(required=false) Long teamId,
                        HttpServletRequest req) {
        var actor=Access.actor(req);
        return tickets(page,size,status,serviceId,assigneeId,q,priority,
                (scope==null ? new Filters() : workItems(scope,actor)).scoped(actor,teamId),scope);
    }

    private Page tickets(int page,int size,String status,Long serviceId,Long assigneeId,String q,
                         String priority,Filters filters,String scope) {
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
        return query(TICKET_FIELDS, TICKET_FROM, scope==null ? "k.id desc" : WORK_ORDER, filters, page, size);
    }

    private Filters workItems(String scope,Actor actor) {
        var filters=new Filters();
        switch(scope) {
            case "mine" -> {
                filters.id("k.assignee_id",actor.id());
                filters.clauses.add("k.status in ('ASSIGNED','PROCESSING','VERIFIED')");
            }
            case "unassigned" -> filters.clauses.add("k.status<>'CLOSED' and (k.assignee_id is null or u.active=false)");
            case "recovery" -> {
                if(!TeamAccess.platform(actor)){
                    filters.clauses.add("(k.assignee_id=? or exists(select 1 from team_members m where m.team_id=s.team_id and m.account_id=? and m.team_role='MANAGER'))");
                    filters.values.add(actor.id());
                    filters.values.add(actor.id());
                }
                filters.clauses.add("""
                    k.status in ('OPEN','ASSIGNED','PROCESSING')
                    and (k.verification is null or k.verification<>'PASSED')
                    and exists (select 1 from alerts a where a.id=k.alert_id and a.lifecycle_status='RESOLVED'
                        and a.resolved_at is not null and not exists
                            (select 1 from timeline h where h.ticket_id=k.id and h.action='REOPEN' and h.created_at>=a.resolved_at))
                    """);
            }
            default -> throw new Problem(400,"未知的待办范围");
        }
        return filters;
    }

    @GetMapping("/alerts/page")
    public Page alerts(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size,
                       @RequestParam(required=false) String severity, @RequestParam(required=false) Long serviceId,
                       @RequestParam(required=false) Long teamId,HttpServletRequest req) {
        var filters = new Filters().scoped(Access.actor(req),teamId);
        filters.id("a.service_id", serviceId);
        filters.code("a.severity", severity, SEVERITIES);
        return query(ALERT_FIELDS, ALERT_FROM, "a.id desc", filters, page, size);
    }

    @GetMapping("/tasks/page")
    public Page tasks(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size,
                      @RequestParam(required=false) String status, @RequestParam(required=false) Long ticketId,
                      @RequestParam(required=false) Long serviceId,@RequestParam(required=false) Long teamId,HttpServletRequest req) {
        var filters = new Filters().scoped(Access.actor(req),teamId);
        filters.id("t.ticket_id", ticketId);
        filters.id("k.service_id", serviceId);
        filters.code("t.status", status, TASK_STATES);
        return query(TASK_FIELDS, TASK_FROM, "t.id desc", filters, page, size);
    }

    private Map<String, Long> counts(String table, String field, Set<String> keys,TeamAccess.Scope scope) {
        var result = new LinkedHashMap<String, Long>();
        keys.forEach(key -> result.put(key, 0L));
        for (var row : store.rows("select " + field + " as code,count(*) as n from " + table + " where "+scope.sql()+" group by " + field,scope.with())) {
            result.put((String)row.get("code"), (Long)row.get("n"));
        }
        return result;
    }

    @GetMapping("/overview")
    public Map<String,Object> overview(@RequestParam(required=false) Long teamId,HttpServletRequest req) {
        var actor=Access.actor(req);
        var scope=TeamAccess.scope(actor,"s.team_id",teamId);
        String where=" where "+scope.sql();
        var alerts = counts(ALERT_FROM, "a.severity", SEVERITIES,scope);
        var tasks = counts(TASK_FROM, "t.status", TASK_STATES,scope);
        return Map.of("openTickets", store.db.queryForObject("select count(*) from "+TICKET_FROM+where+" and k.status<>'CLOSED'", Long.class,scope.with()),
            "criticalAlerts", alerts.get("CRITICAL"), "activeTasks", tasks.get("PENDING") + tasks.get("RUNNING"),
            "services", store.db.queryForObject("select count(*) from services s"+where, Long.class,scope.with()),
            "alertCounts", alerts, "taskCounts", tasks,
            "recentTickets", store.rows("select " + TICKET_FIELDS + " from " + TICKET_FROM + where+" order by k.id desc limit 5",scope.with()),
            "recentAlerts", store.rows("select " + ALERT_FIELDS + " from " + ALERT_FROM + where+" order by a.id desc limit 5",scope.with()),
            "workItems", Map.of(
                "mine",query(TICKET_FIELDS,TICKET_FROM,WORK_ORDER,workItems("mine",actor).scoped(actor,teamId),1,5),
                "unassigned",query(TICKET_FIELDS,TICKET_FROM,WORK_ORDER,workItems("unassigned",actor).scoped(actor,teamId),1,5),
                "recovery",query(TICKET_FIELDS,TICKET_FROM,WORK_ORDER,workItems("recovery",actor).scoped(actor,teamId),1,5)));
    }
}
