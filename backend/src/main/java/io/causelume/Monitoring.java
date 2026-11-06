package io.causelume;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class Monitoring {
    final Store store;
    Monitoring(Store store) { this.store=store; }
    record Service(String name,String description,String resource) {}
    record Alert(long serviceId,String source,String externalId,String severity,String title,Instant occurredAt) {}
    record Maintenance(Instant until,String reason) {}

    @PostMapping("/services/{id}/maintenance") @Transactional
    public Object silence(@PathVariable long id,@RequestBody Maintenance input,HttpServletRequest req) {
        var actor=Access.actor(req); actor.admin();
        if(input.until()==null || !input.until().isAfter(Instant.now())) throw new Problem(400,"维护静默必须设置未来的到期时间");
        if(store.db.update("update services set maintenance_until=?,maintenance_reason=? where id=?",input.until().atOffset(java.time.ZoneOffset.UTC),
            Problem.text(input.reason(),500,"reason"),id)==0) throw new Problem(404,"服务不存在");
        store.audit(actor.username(),"MAINTENANCE_START",id);
        return store.one("select * from services where id=?",id);
    }

    @PostMapping("/services/{id}/maintenance/end") @Transactional
    public Object unsilence(@PathVariable long id,HttpServletRequest req) {
        var actor=Access.actor(req); actor.admin();
        if(store.db.update("update services set maintenance_until=null,maintenance_reason=null where id=?",id)==0)
            throw new Problem(404,"服务不存在");
        store.audit(actor.username(),"MAINTENANCE_END",id);
        return store.one("select * from services where id=?",id);
    }

    @PostMapping("/services") @Transactional
    public Object service(@RequestBody Service input,HttpServletRequest req) {
        var actor=Access.actor(req);
        actor.admin();
        long id=store.insert("insert into services(name,description,resource) values (?,?,?)",
            Problem.text(input.name,100,"name"),Problem.text(input.description,500,"description"),Problem.text(input.resource,200,"resource"));
        store.audit(actor.username(),"CREATE_SERVICE",id);
        return store.one("select * from services where id=?",id);
    }
    @GetMapping("/alerts") Object alerts() { return store.rows("select * from alerts order by id desc limit 100"); }
    @PostMapping("/alerts") @Transactional
    public Object alert(@RequestBody Alert input,HttpServletRequest req) {
        store.one("select id from services where id=?",input.serviceId);
        String source=Problem.text(input.source,100,"source");
        if(source.startsWith("am/")) throw new Problem(400,"该来源前缀保留给机器入口");
        String externalId=Problem.text(input.externalId,200,"externalId");
        String severity=Problem.choice(input.severity,Set.of("CRITICAL","WARNING","INFO"),"severity");
        String title=Problem.text(input.title,200,"title");
        if(input.occurredAt==null) throw new Problem(400,"需要 occurredAt");
        try {
            long id=store.insertIdempotent("insert into alerts(service_id,source,external_id,severity,title,occurred_at) values (?,?,?,?,?,?)",
                input.serviceId,source,externalId,severity,title,input.occurredAt);
            store.audit(Access.actor(req).username(),"CREATE_ALERT",id);
            return store.one("select * from alerts where id=?",id);
        } catch (DuplicateKeyException existing) {
            // The upstream event identity is immutable. Re-deliveries return the first observation.
            return store.one("select * from alerts where service_id=? and source=? and external_id=?",input.serviceId,source,externalId);
        }
    }
}
