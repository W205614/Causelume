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
    Tickets(Store store) { this.store=store; }

    static final String COLUMNS="id,service_id,alert_id,title,status,assignee_id,version,created_at,review";
    record NewTicket(long serviceId,Long alertId,String title,UUID requestId) {}
    record Action(Long version,String action,Long assigneeId,String note) {}

    @GetMapping Object list() { return store.rows("select "+COLUMNS+" from tickets order by id desc limit 100"); }
    @GetMapping("/{id}") @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(@PathVariable long id) {
        var row=store.one("select "+COLUMNS+" from tickets where id=?",id);
        row.put("timeline",store.rows("select action,note,actor,created_at from timeline where ticket_id=? order by id",id));
        return row;
    }
    @PostMapping @Transactional
    public Object create(@RequestBody NewTicket input,HttpServletRequest req) {
        var actor=Access.actor(req);
        String title=Problem.text(input.title,200,"title");
        if(input.requestId==null) throw new Problem(400,"需要 requestId");
        store.one("select id from services where id=?",input.serviceId);
        if(input.alertId!=null) {
            var alert=store.one("select service_id from alerts where id=?",input.alertId);
            if(!Objects.equals(alert.get("serviceId"),input.serviceId)) throw new Problem(400,"告警属于其他服务");
        }
        long id;
        try {
            id=store.insertIdempotent("insert into tickets(service_id,alert_id,title,status,creator_id,request_id) values (?,?,?,'OPEN',?,?)",
                input.serviceId,input.alertId,title,actor.id(),input.requestId);
        } catch (DuplicateKeyException duplicate) {
            var matches=store.rows("select id,service_id,alert_id,title from tickets where creator_id=? and request_id=?",actor.id(),input.requestId);
            if(matches.isEmpty()) throw new Problem(409,"该告警已经关联工单");
            var old=matches.getFirst();
            if(!Objects.equals(old.get("serviceId"),input.serviceId) || !Objects.equals(old.get("alertId"),input.alertId) || !title.equals(old.get("title")))
                throw new Problem(409,"同一 requestId 的请求内容冲突");
            return detail((long)old.get("id"));
        }
        record(id,"CREATE",title,actor);
        return detail(id);
    }

    @PostMapping("/{id}/actions") @Transactional
    public Object action(@PathVariable long id,@RequestBody Action input,HttpServletRequest req) {
        var actor=Access.actor(req);
        var ticket=store.one("select status,assignee_id,version from tickets where id=?",id);
        if(input.version==null || input.version<0) throw new Problem(400,"需要有效的工单版本");
        if(!input.version.equals(ticket.get("version"))) throw new Problem(409,"工单已更新，请刷新");
        String note=Problem.text(input.note,2000,"note");
        String status=(String)ticket.get("status");
        Long assignee=(Long)ticket.get("assigneeId");
        String action=Problem.text(input.action,16,"action");
        if(!action.equals("ASSIGN") && !action.equals("NOTE") && !actor.role().equals("ADMIN") && !Objects.equals(assignee,actor.id()))
            throw new Problem(403,"需要工单负责人或管理员权限");
        String next=switch(action) {
            case "ASSIGN" -> {
                actor.admin();
                if(!status.equals("OPEN") && !status.equals("ASSIGNED")) throw new Problem(409,"当前状态不能派工");
                if(input.assigneeId==null) throw new Problem(400,"请选择负责人");
                store.one("select id from accounts where id=?",input.assigneeId);
                assignee=input.assigneeId;
                yield "ASSIGNED";
            }
            case "START" -> transition(status,"ASSIGNED","PROCESSING");
            case "VERIFY" -> transition(status,"PROCESSING","VERIFIED");
            case "CLOSE" -> transition(status,"VERIFIED","CLOSED");
            case "NOTE" -> {
                if(status.equals("CLOSED")) throw new Problem(409,"已关闭工单不能追加记录");
                yield status;
            }
            default -> throw new Problem(400,"未知工单操作");
        };
        int changed=store.db.update("update tickets set status=?,assignee_id=?,version=version+1,review=case when ?='CLOSE' then ? else review end where id=? and version=?",
            next,assignee,action,note,id,input.version);
        if(changed==0) throw new Problem(409,"工单已更新，请刷新");
        record(id,action,note,actor);
        return detail(id);
    }

    private String transition(String current,String required,String next) {
        if(!current.equals(required)) throw new Problem(409,"当前工单状态不允许此操作");
        return next;
    }

    private void record(long id,String action,String note,Actor actor) {
        store.db.update("insert into timeline(ticket_id,action,note,actor) values (?,?,?,?)",id,action,note,actor.username());
        store.audit(actor.username(),action,id);
    }
}
