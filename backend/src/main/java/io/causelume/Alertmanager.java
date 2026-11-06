package io.causelume;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/integrations/alertmanager")
class Alertmanager {
    private final Store store;
    private final Notifications notifications;
    private final Environment env;
    Alertmanager(Store store,Notifications notifications,Environment env) {
        this.store=store; this.notifications=notifications; this.env=env;
    }

    @PostMapping("/{integrationId}") @Transactional
    public Object receive(@PathVariable String integrationId,@RequestBody JsonNode payload,HttpServletRequest req) {
        String token=env.getProperty("ALERTMANAGER_TOKEN","");
        String expectedIntegration=env.getProperty("ALERTMANAGER_INTEGRATION_ID","primary");
        String provided=req.getHeader("Authorization");
        if(token.isBlank() || !integrationId.equals(expectedIntegration) || provided==null ||
            !MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),provided.getBytes(StandardCharsets.UTF_8)))
            throw new Problem(401,"机器凭据无效或集成未启用");
        var batch=payload.path("alerts");
        if(!batch.isArray() || batch.isEmpty() || batch.size()>100) throw new Problem(400,"每次应发送 1 至 100 条告警");
        var ids=new ArrayList<Long>();
        for(var event:batch) ids.add(ingest(integrationId,event));
        return Map.of("alertIds",ids);
    }

    private long ingest(String integration,JsonNode event) {
        String state=Problem.choice(event.path("status").asString(""),java.util.Set.of("firing","resolved"),"status");
        var labels=event.path("labels");
        String name=Problem.text(labels.path("service").asString(""),100,"labels.service");
        var service=store.one("select id,owner_id from services where name=? for update",name);
        long serviceId=(long)service.get("id");
        String fingerprint=Problem.text(event.path("fingerprint").asString(""),100,"fingerprint");
        java.time.OffsetDateTime start=timestamp(event,"startsAt");
        java.time.OffsetDateTime end=state.equals("resolved") ? timestamp(event,"endsAt") : null;
        if(end!=null && end.isBefore(start)) throw new Problem(400,"endsAt 早于 startsAt");
        var old=store.rows("""
            select id,service_id,lifecycle_status from alerts where integration_id=? and fingerprint=? and occurred_at=?
            """,integration,fingerprint,start);
        if(!old.isEmpty()) {
            var existing=old.getFirst();
            if((long)existing.get("serviceId")!=serviceId)throw new Problem(409,"同一告警生命周期对应了其他服务");
            long id=(long)existing.get("id");
            if(end!=null && "FIRING".equals(existing.get("lifecycleStatus"))) {
                store.db.update("update alerts set lifecycle_status='RESOLVED',resolved_at=? where id=?",end,id);
                store.audit("alertmanager","ALERT_RESOLVED",id);
                var tickets=store.rows("select id from tickets where alert_id=?",id);
                if(!tickets.isEmpty()) {
                    long ticketId=(long)tickets.getFirst().get("id");
                    store.db.update("insert into timeline(ticket_id,action,actor,note) values (?,'ALERT_RESOLVED',?,?)",
                        ticketId,"alertmanager","监控已恢复，仍需人工验证工单");
                    notifications.notifyTicket(ticketId,"ALERT_RESOLVED","监控收到恢复信号，请人工核验工单 #"+ticketId);
                }
            }
            return id;
        }
        String title=Problem.text(event.path("annotations").path("summary").asString(labels.path("alertname").asString("")),200,"告警标题");
        String severity=switch(labels.path("severity").asString("info").toLowerCase(Locale.ROOT)) {
            case "critical","fatal","high" -> "CRITICAL";
            case "warning","warn" -> "WARNING";
            case "info","none" -> "INFO";
            default -> throw new Problem(400,"未知 severity");
        };
        String source="am/"+integration;
        long id=store.insert("""
            insert into alerts(service_id,source,external_id,severity,title,occurred_at,integration_id,fingerprint,lifecycle_status,resolved_at)
            values (?,?,?,?,?,?,?,?,?,?)
            """,serviceId,source,fingerprint+"@"+start,severity,title,start,integration,fingerprint,state.toUpperCase(Locale.ROOT),end);
        String machine="alertmanager";
        store.audit(machine,end==null?"ALERT_FIRING":"ALERT_RESOLVED",id);
        if(end==null) {
            var users=store.rows("select id from accounts where active=true and id=?",service.get("ownerId"));
            Object owner=users.isEmpty()?null:users.getFirst().get("id");
            long ticket=store.insert("""
                insert into tickets(service_id,alert_id,title,status,assignee_id,request_id)
                values (?,?,?,?,?,?)
                """,serviceId,id,title,owner==null?"OPEN":"ASSIGNED",owner,UUID.randomUUID());
            store.db.update("insert into timeline(ticket_id,action,actor,note) values (?,'CREATE',?,?)",ticket,machine,"告警建单："+title);
            store.audit(machine,"CREATE_TICKET",ticket);
            if(owner!=null) {
                store.db.update("insert into timeline(ticket_id,action,actor,note) values (?,'ASSIGN',?,?)",ticket,machine,"已派给服务默认负责人");
                store.audit(machine,"ASSIGN",ticket);
            }
            notifications.notifyTicket(ticket,"ALERT_FIRING","收到 "+name+" 告警："+title);
        }
        return id;
    }

    private static java.time.OffsetDateTime timestamp(JsonNode node,String key) {
        try { return Instant.parse(node.path(key).asString("")).truncatedTo(ChronoUnit.MICROS).atOffset(java.time.ZoneOffset.UTC); }
        catch(DateTimeParseException invalid) {throw new Problem(400,key+" 需要带时区的时间");}
    }
}
