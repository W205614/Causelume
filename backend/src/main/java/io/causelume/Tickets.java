package io.causelume;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tickets")
class Tickets {
    final Store store;
    private final Notifications notifications;
    Tickets(Store store,Notifications notifications) { this.store=store; this.notifications=notifications; }

    static final String COLUMNS="id,service_id,alert_id,title,status,assignee_id,version,created_at,review,priority,verification,impact,incident_started_at,incident_ended_at,cause,uncertainty";
    static final java.util.Set<String> PRIORITIES=java.util.Set.of("P1","P2","P3","P4");
    record NewTicket(long serviceId,Long alertId,String title,UUID requestId,String priority) {}
    record Postmortem(String impact,java.time.Instant startedAt,java.time.Instant endedAt,String cause,String uncertainty) {}
    record Action(Long version,String action,Long assigneeId,String note,String priority,String verification,Postmortem postmortem) {}

    @GetMapping Object list() { return store.rows("select "+COLUMNS+" from tickets order by id desc limit 100"); }
    @GetMapping("/{id}") @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(@PathVariable long id) {
        var row=store.one("select "+COLUMNS+" from tickets where id=?",id);
        row.put("timeline",store.rows("""
            select * from (select id,action,note,actor,created_at,outcome from timeline where ticket_id=? order by id desc limit 50) recent order by id
            """,id));
        row.put("timelineTotal",store.db.queryForObject("select count(*) from timeline where ticket_id=?",Long.class,id));
        row.put("improvements",store.rows("select id,title,owner_id,due_date,status from improvement_items where ticket_id=? order by id",id));
        return row;
    }
    @PostMapping @Transactional
    public Object create(@RequestBody NewTicket input,HttpServletRequest req) {
        var actor=Access.actor(req);
        String title=Problem.text(input.title,200,"title");
        if(input.requestId==null) throw new Problem(400,"需要 requestId");
        String priority=input.priority==null ? "P3" : Problem.choice(input.priority,PRIORITIES,"priority");
        var service=store.one("select active from services where id=? for update",input.serviceId);
        var prior=store.rows("select id,service_id,alert_id,title,created_priority from tickets where creator_id=? and request_id=?",actor.id(),input.requestId);
        if(!prior.isEmpty()) return replay(input,title,priority,prior.getFirst());
        if(!Boolean.TRUE.equals(service.get("active"))) throw new Problem(409,"服务已停用");
        if(input.alertId!=null) {
            var alert=store.one("select service_id from alerts where id=?",input.alertId);
            if(!Objects.equals(alert.get("serviceId"),input.serviceId)) throw new Problem(400,"告警属于其他服务");
        }
        long id;
        try {
            id=store.insertIdempotent("insert into tickets(service_id,alert_id,title,status,creator_id,request_id,priority,created_priority) values (?,?,?,'OPEN',?,?,?,?)",
                input.serviceId,input.alertId,title,actor.id(),input.requestId,priority,priority);
        } catch (DuplicateKeyException duplicate) {
            var matches=store.rows("select id,service_id,alert_id,title,created_priority from tickets where creator_id=? and request_id=?",actor.id(),input.requestId);
            if(matches.isEmpty()) throw new Problem(409,"该告警已经关联工单");
            return replay(input,title,priority,matches.getFirst());
        }
        record(id,"CREATE",title,actor,null);
        return detail(id);
    }

    private Map<String,Object> replay(NewTicket input,String title,String priority,Map<String,Object> old) {
        if(!Objects.equals(old.get("serviceId"),input.serviceId) || !Objects.equals(old.get("alertId"),input.alertId) || !title.equals(old.get("title")) || !priority.equals(old.get("createdPriority")))
            throw new Problem(409,"同一 requestId 的请求内容冲突");
        return detail((long)old.get("id"));
    }

    @PostMapping("/{id}/actions") @Transactional
    public Object action(@PathVariable long id,@RequestBody Action input,HttpServletRequest req) {
        var actor=Access.actor(req);
        var ticket=store.one("select status,assignee_id,version,priority,verification from tickets where id=?",id);
        if(input.version==null || input.version<0) throw new Problem(400,"需要有效的工单版本");
        if(!input.version.equals(ticket.get("version"))) throw new Problem(409,"工单已更新，请刷新");
        String note=Problem.text(input.note,2000,"note");
        String status=(String)ticket.get("status");
        Long assignee=(Long)ticket.get("assigneeId");
        String action=Problem.text(input.action,16,"action");
        String priority=(String)ticket.get("priority"), verification=(String)ticket.get("verification");
        if(!action.equals("ASSIGN") && !action.equals("NOTE") && !actor.role().equals("ADMIN") && !Objects.equals(assignee,actor.id()))
            throw new Problem(403,"需要工单负责人或管理员权限");
        String next=switch(action) {
            case "ASSIGN" -> {
                actor.admin();
                if(!status.equals("OPEN") && !status.equals("ASSIGNED")) throw new Problem(409,"当前状态不能派工");
                if(input.assigneeId==null) throw new Problem(400,"请选择负责人");
                store.one("select id from accounts where id=? and active=true",input.assigneeId);
                assignee=input.assigneeId;
                yield "ASSIGNED";
            }
            case "START" -> transition(status,"ASSIGNED","PROCESSING");
            case "VERIFY" -> {
                verification=input.verification==null ? "PASSED" : Problem.choice(input.verification,java.util.Set.of("PASSED","FAILED","UNKNOWN"),"verification");
                yield transition(status,"PROCESSING",verification.equals("PASSED") ? "VERIFIED" : "PROCESSING");
            }
            case "CLOSE" -> transition(status,"VERIFIED","CLOSED");
            case "PRIORITY" -> {
                if(status.equals("CLOSED")) throw new Problem(409,"已关闭工单不能修改优先级");
                priority=Problem.choice(input.priority,PRIORITIES,"priority");
                yield status;
            }
            case "REOPEN" -> {
                verification=null;
                boolean staffed=!store.rows("select id from accounts where id=? and active=true",assignee).isEmpty();
                if(!staffed) assignee=null;
                yield transition(status,"CLOSED",staffed?"PROCESSING":"OPEN");
            }
            case "NOTE" -> {
                if(status.equals("CLOSED")) throw new Problem(409,"已关闭工单不能追加记录");
                yield status;
            }
            default -> throw new Problem(400,"未知工单操作");
        };
        int changed=store.db.update("update tickets set status=?,assignee_id=?,priority=?,verification=?,version=version+1,review=case when ?='CLOSE' then ? else review end where id=? and version=?",
            next,assignee,priority,verification,action,note,id,input.version);
        if(changed==0) throw new Problem(409,"工单已更新，请刷新");
        if(action.equals("CLOSE") && input.postmortem!=null) {
            var review=input.postmortem;
            if(review.startedAt==null || review.endedAt==null || review.endedAt.isBefore(review.startedAt))
                throw new Problem(400,"复盘时间范围无效");
            store.db.update("update tickets set impact=?,incident_started_at=?,incident_ended_at=?,cause=?,uncertainty=? where id=?",
                Problem.text(review.impact,2000,"impact"),review.startedAt.atOffset(java.time.ZoneOffset.UTC),review.endedAt.atOffset(java.time.ZoneOffset.UTC),Problem.text(review.cause,2000,"cause"),Problem.text(review.uncertainty,2000,"uncertainty"),id);
        }
        String outcome=switch(action){case "VERIFY" -> verification; case "PRIORITY" -> priority; default -> null;};
        record(id,action,note,actor,outcome);
        if(action.equals("ASSIGN") || action.equals("CLOSE"))
            notifications.notifyTicket(id,action,action.equals("ASSIGN") ? "工单 #"+id+" 已派给你" : "工单 #"+id+" 已关闭");
        return detail(id);
    }

    private String transition(String current,String required,String next) {
        if(!current.equals(required)) throw new Problem(409,"当前工单状态不允许此操作");
        return next;
    }

    private void record(long id,String action,String note,Actor actor,String outcome) {
        store.db.update("insert into timeline(ticket_id,action,note,actor,outcome) values (?,?,?,?,?)",id,action,note,actor.username(),outcome);
        store.audit(actor.username(),action,id);
    }
}
