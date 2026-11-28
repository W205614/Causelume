package io.causelume;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/tasks")
class Investigations {
    private static final String SELECT = "select id,ticket_id,status,mode,created_at,started_at,finished_at,error_code,analysis,error,feedback,feedback_rating,source_id,window_start,window_end,team_label from tasks";
    final Store store;
    final ObjectMapper json;
    final ModelBudgets budgets;
    final Environment env;
    final TeamAccess access;
    Investigations(Store store, ObjectMapper json, ModelBudgets budgets, Environment env,TeamAccess access) {
        this.store = store;
        this.json = json;
        this.budgets = budgets;
        this.env = env;
        this.access = access;
    }
    record NewTask(long ticketId, UUID requestId, Instant windowStart, Instant windowEnd, Long serviceVersion) {
        NewTask {
            // Match database rounding so pre-existing requests also remain replayable.
            if(windowStart!=null) windowStart=windowStart.plusNanos(500).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            if(windowEnd!=null) windowEnd=windowEnd.plusNanos(500).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        }
    }
    record Feedback(String feedback, String rating) {}
    record Window(Instant start, Instant end) {}

    @GetMapping
    List<Map<String,Object>> list(@RequestParam(required=false) Long ticketId,@RequestParam(required=false) Long teamId,HttpServletRequest req) {
        var scope=TeamAccess.scope(Access.actor(req),"s.team_id",teamId);
        String fields = """
            select t.id,t.ticket_id,t.status,t.mode,t.created_at,t.started_at,t.finished_at,t.error_code,coalesce(t.summary,left(t.error,240)) as summary
            from tasks t join tickets k on k.id=t.ticket_id join services s on s.id=k.service_id where
            """;
        String sorted=" order by t.id desc limit 100";
        return ticketId == null ? store.rows(fields+scope.sql()+sorted,scope.with())
                : store.rows(fields+scope.sql()+" and t.ticket_id=?"+sorted,scope.with(ticketId));
    }
    private Map<String,Object> result(Map<String,Object> task) {
        String analysis = (String)task.remove("analysis");
        task.put("result", analysis == null ? null : json.readTree(analysis));
        return task;
    }
    @GetMapping("/{id}") @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> get(@PathVariable long id,HttpServletRequest req) {
        var actor=Access.actor(req);
        if(!access.internal(actor,access.taskTeam(id))) {
            return result(store.one("""
                select t.id,t.ticket_id,t.status,t.created_at,t.finished_at,t.analysis from tasks t
                join ticket_shared_tasks x on x.task_id=t.id join ticket_invites i on i.id=x.invite_id
                where t.id=? and t.status='SUCCEEDED' and i.ticket_id=t.ticket_id and i.account_id=? and i.revoked_at is null
                  and exists(select 1 from team_members m where m.account_id=i.account_id)
                """,id,actor.id()));
        }
        return internalResult(id);
    }

    private Map<String,Object> internalResult(long id) {
        return result(store.one(SELECT+" where id=?",id));
    }

    private boolean shared(long id,Actor actor) {
        return !store.rows("""
            select 1 from ticket_shared_tasks x join ticket_invites i on i.id=x.invite_id
            where x.task_id=? and i.account_id=? and i.revoked_at is null
            """,id,actor.id()).isEmpty();
    }

    @GetMapping("/preview") @Transactional(readOnly=true)
    Map<String,Object> preview(@RequestParam long ticketId,HttpServletRequest req) {
        var actor=Access.actor(req);
        long team=access.ticketTeam(ticketId);
        if(!access.internal(actor,team) && access.invited(actor.id(),ticketId)) throw new Problem(403,"协作者不能发起调查");
        access.requireTeam(actor,team);
        var ticket = store.one("select status,assignee_id,service_id,alert_id from tickets where id=?",ticketId);
        IncidentRecords.requireOwner(actor,ticket);
        requireInvestigable(ticket);
        var source = investigationSource(ticket);
        boolean recentSnapshot = "checkout-demo".equals(source.get("sourceId"));
        var view = new LinkedHashMap<String,Object>();
        view.put("service",source.get("name"));
        view.put("serviceLabel",source.get("serviceLabel"));
        view.put("environment",source.get("environment"));
        view.put("sourceId",source.get("sourceId"));
        view.put("serviceVersion",source.get("version"));
        view.put("mode",env.getProperty("AGENT_MODE","offline"));
        view.put("recentSnapshot",recentSnapshot);
        if (!recentSnapshot) {
            var window = defaultWindow(ticket);
            view.put("windowStart",window.start());
            view.put("windowEnd",window.end());
        }
        return view;
    }

    @PostMapping @Transactional
    public Object create(@RequestBody NewTask input, HttpServletRequest req) {
        var actor = Access.actor(req);
        if (input.requestId == null) throw new Problem(400,"需要 requestId");
        access.ticketMutation(actor,input.ticketId,access.ticketTeam(input.ticketId));
        // Serialize requests for this ticket, including a concurrent verification or close.
        var ticket = store.one("select status,assignee_id,service_id,alert_id from tickets where id=? for update",input.ticketId);
        IncidentRecords.requireOwner(actor,ticket);
        var old = store.rows("select id,ticket_id,window_start,window_end from tasks where creator_id=? and request_id=?",actor.id(),input.requestId);
        if (!old.isEmpty()) {
            if (!Long.valueOf(input.ticketId).equals(old.getFirst().get("ticketId")))
                throw new Problem(409,"同一 requestId 的工单不一致");
            var original=old.getFirst();
            if(input.windowStart!=null || input.windowEnd!=null) {
                if(input.windowStart==null || input.windowEnd==null || original.get("windowStart")==null || original.get("windowEnd")==null ||
                   !input.windowStart.equals(((java.time.OffsetDateTime)original.get("windowStart")).toInstant()) ||
                   !input.windowEnd.equals(((java.time.OffsetDateTime)original.get("windowEnd")).toInstant()))
                    throw new Problem(409,"同一 requestId 的调查时间范围冲突");
            }
            return internalResult((long)original.get("id"));
        }
        requireInvestigable(ticket);
        if (!store.rows("select id from tasks where ticket_id=? and status in ('PENDING','RUNNING')",input.ticketId).isEmpty())
            throw new Problem(409,"此工单已有调查在进行");
        // Admissions on different tickets compete for the same finite worker queue.
        store.one("select id from task_admission where id=1 for update");
        if (store.db.queryForObject("select count(*) from tasks where status in ('PENDING','RUNNING')",Long.class) >= 32)
            throw new Problem(429,"待处理调查已达上限，请稍后再试");
        var service=investigationSource(ticket);
        if (input.serviceVersion!=null && !input.serviceVersion.equals(service.get("version")))
            throw new Problem(409,"服务配置已更新，请重新打开调查确认");
        Instant end=input.windowEnd, start=input.windowStart;
        if((start==null)!=(end==null)) throw new Problem(400,"时间范围需要同时填写起止时间");
        if(start==null) {
            var window=defaultWindow(ticket);
            start=window.start();
            end=window.end();
        }
        if(!start.isBefore(end) || Duration.between(start,end).compareTo(Duration.ofHours(1))>0)
            throw new Problem(400,"调查时间范围必须大于零且不超过一小时");
        long id = store.insert("insert into tasks(ticket_id,creator_id,request_id,status,source_id,service_label,environment,window_start,window_end,team_label) values (?,?,?,'PENDING',?,?,?,?,?,?)",
                input.ticketId,actor.id(),input.requestId,service.get("sourceId"),service.get("serviceLabel"),service.get("environment"),
                start.atOffset(java.time.ZoneOffset.UTC),end.atOffset(java.time.ZoneOffset.UTC),ServiceSettings.teamLabel(service));
        budgets.reserve(id);
        store.audit(actor.username(),"CREATE_TASK",id);
        return internalResult(id);
    }
    private void requireInvestigable(Map<String,Object> ticket) {
        if (!List.of("ASSIGNED","PROCESSING").contains(ticket.get("status")))
            throw new Problem(409,"调查需要已分派或处理中的工单");
    }

    private Map<String,Object> investigationSource(Map<String,Object> ticket) {
        var service=store.one("""
            select s.name,s.source_id,s.service_label,s.environment,s.active,s.version,s.team_id,s.source_team_scoped,t.slug
            from services s join teams t on t.id=s.team_id where s.id=?
            """,ticket.get("serviceId"));
        if(!Boolean.TRUE.equals(service.get("active"))) throw new Problem(409,"服务已停用");
        if(service.get("sourceId")==null) throw new Problem(422,"服务尚未绑定批准的数据源");
        ServiceSettings.teamLabel(service);
        return service;
    }

    private Window defaultWindow(Map<String,Object> ticket) {
        if(ticket.get("alertId")!=null) {
            var alert=store.one("select occurred_at from alerts where id=?",ticket.get("alertId"));
            var occurred=((java.time.OffsetDateTime)alert.get("occurredAt")).toInstant();
            return new Window(occurred.minusSeconds(300),occurred.plusSeconds(300));
        }
        Instant end=Instant.now();
        return new Window(end.minusSeconds(600),end);
    }

    @PostMapping("/{id}/feedback") @Transactional
    public Object feedback(@PathVariable long id,@RequestBody Feedback input,HttpServletRequest req) {
        var actor = Access.actor(req);
        long ticket=(long)store.one("select ticket_id from tasks where id=?",id).get("ticketId");
        long team=access.taskTeam(id);
        access.lock(team);
        if(!access.internal(actor,team) && !shared(id,actor)) throw new Problem(404,"记录不存在");
        access.ticketMutation(actor,ticket,team);
        var task = store.one("select t.status,k.assignee_id from tasks t join tickets k on k.id=t.ticket_id where t.id=? for update",id);
        if (!actor.role().equals("ADMIN") && !Long.valueOf(actor.id()).equals(task.get("assigneeId")))
            throw new Problem(403,"需要工单负责人或管理员权限");
        if (!List.of("FAILED","SUCCEEDED").contains(task.get("status"))) throw new Problem(409,"请等待任务结束");
        String feedback = Problem.text(input.feedback,2000,"feedback");
        String rating=input.rating==null ? null : Problem.choice(input.rating,java.util.Set.of("HELPFUL","PARTIAL","UNHELPFUL"),"rating");
        if (store.db.update("update tasks set feedback=?,feedback_rating=? where id=? and feedback is null",feedback,rating,id)==0)
            throw new Problem(409,"此任务已提交人工反馈");
        store.audit(actor.username(),"TASK_FEEDBACK",id);
        return internalResult(id);
    }
}
