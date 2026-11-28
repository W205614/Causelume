package io.causelume;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping(Alertmanager.PATH)
class Alertmanager {
    static final String PATH="/api/integrations/alertmanager";
    private final Store store;
    private final Notifications notifications;
    private final TeamAccess access;
    private final Map<String,Integration> integrations;
    private final Counter rejectedAlerts;
    private final Counter truncatedAlerts;
    record Integration(String slug,String token,boolean legacy) {}

    Alertmanager(Store store,Notifications notifications,Environment env,MeterRegistry metrics,TeamAccess access,ObjectMapper json) {
        this.store=store; this.notifications=notifications; this.access=access;
        integrations=loadIntegrations(env,json);
        rejectedAlerts=metrics.counter("causelume.alertmanager.dropped","reason","rejected");
        truncatedAlerts=metrics.counter("causelume.alertmanager.dropped","reason","truncated");
    }

    private static Map<String,Integration> loadIntegrations(Environment env,ObjectMapper json) {
        var registry=new HashMap<String,Integration>();
        var tokens=new HashSet<String>();
        String legacyToken=env.getProperty("ALERTMANAGER_TOKEN","");
        String legacyId=env.getProperty("ALERTMANAGER_INTEGRATION_ID","primary");
        if(!legacyToken.isBlank()){
            registry.put(legacyId,new Integration("default",legacyToken,true));
            tokens.add(legacyToken);
        }
        String file=env.getProperty("ALERTMANAGER_REGISTRY_FILE","");
        if(file.isBlank()) return Map.copyOf(registry);
        JsonNode entries;
        try {
            entries=json.readTree(Files.readString(Path.of(file)));
        }catch(IOException | tools.jackson.core.JacksonException e) {
            throw new IllegalArgumentException("Unable to read Alertmanager integration registry");
        }
        if(!entries.isArray()) throw new IllegalArgumentException("Expected an Alertmanager integration array");
        for(var entry:entries) {
            String id=entry.path("id").asString("");
            String slug=entry.path("team").asString("");
            String token=entry.path("token").asString("");
            if(!id.matches("[a-z0-9][a-z0-9_-]{0,63}") || id.equals(legacyId)
                || !slug.matches("[a-z0-9][a-z0-9_-]{0,63}") || !token.matches("[!-~]{24,256}")
                || !tokens.add(token) || registry.containsKey(id))
                throw new IllegalArgumentException("Invalid or duplicate Alertmanager integration");
            registry.put(id,new Integration(slug,token,false));
        }
        return Map.copyOf(registry);
    }

    @SessionIndependent @PostMapping("/{integrationId}") @Transactional
    public Object receive(@PathVariable String integrationId,@RequestBody JsonNode payload,HttpServletRequest req) {
        var integration=integrations.get(integrationId);
        String provided=req.getHeader("Authorization");
        if(integration==null || provided==null ||
            !MessageDigest.isEqual(("Bearer "+integration.token()).getBytes(StandardCharsets.UTF_8),provided.getBytes(StandardCharsets.UTF_8)))
            throw new Problem(401,"机器凭据无效或集成未启用");
        var batch=payload.path("alerts");
        if(!batch.isArray() || batch.isEmpty() || batch.size()>100) throw new Problem(400,"每次应发送 1 至 100 条告警");
        var truncated=payload.get("truncatedAlerts");
        long truncatedCount=truncated==null ? 0 : truncated.asLong(-1);
        if(truncatedCount<0 || (truncated!=null && !truncated.isIntegralNumber()))
            throw new Problem(400,"truncatedAlerts 必须为非负整数");
        long team=(long)store.one("select id from teams where slug=?",integration.slug()).get("id");
        access.lock(team);
        var ids=new ArrayList<Long>();
        var rejected=new ArrayList<Map<String,Object>>();
        Problem firstRejection=null;
        for(int index=0;index<batch.size();index++) {
            var event=batch.get(index);
            try {
                ids.add(store.withSavepoint(() -> ingest(integrationId,integration,team,event)));
            } catch(Problem problem) {
                if(problem.status<400 || problem.status>=500) throw problem;
                if(firstRejection==null) firstRejection=problem;
                rejected.add(Map.of("index",index,"status",problem.status,"reason",problem.getMessage()));
            }
        }
        if(!rejected.isEmpty()) rejectedAlerts.increment(rejected.size());
        if(truncatedCount>0) truncatedAlerts.increment(truncatedCount);
        if(ids.isEmpty()) throw firstRejection;
        return Map.of("alertIds",ids,"rejected",rejected,"truncatedAlerts",truncatedCount);
    }

    private long ingest(String integration,Integration binding,long team,JsonNode event) {
        String state=Problem.choice(event.path("status").asString(""),java.util.Set.of("firing","resolved"),"status");
        String fingerprint=Problem.text(event.path("fingerprint").asString(""),100,"fingerprint");
        OffsetDateTime start=timestamp(event,"startsAt");
        OffsetDateTime end=state.equals("resolved") ? timestamp(event,"endsAt") : null;
        if(end!=null && end.isBefore(start)) throw new Problem(400,"endsAt 早于 startsAt");
        var labels=event.path("labels");
        if((!binding.legacy() || labels.has("team")) && !binding.slug().equals(labels.path("team").asString("")))
            throw new Problem(403,"告警团队与接入授权不一致");
        var old=existing(integration,fingerprint,start);
        if(old!=null) return updateExisting(old,end,team);
        String name=Problem.text(labels.path("service").asString(""),100,"labels.service");
        String environment=labels.has("environment")
            ? Problem.text(labels.path("environment").asString(""),64,"labels.environment") : null;
        String query="select id,owner_id,active from services where team_id=? and coalesce(service_label,name)=?";
        var matches=environment==null
            ? store.rows(query+" order by id limit 2 for update",team,name)
            : store.rows(query+" and environment=? order by id limit 2 for update",team,name,environment);
        if(matches.isEmpty()) throw new Problem(404,"没有服务匹配告警的 service/environment 标签");
        if(matches.size()>1) throw new Problem(409,"告警标签匹配多个服务，请提供 environment 并确保服务标签与环境组合唯一");
        var service=matches.getFirst();
        long serviceId=(long)service.get("id");
        old=existing(integration,fingerprint,start);
        if(old!=null) return updateExisting(old,end,team);
        if(!Boolean.TRUE.equals(service.get("active")) && end==null)throw new Problem(409,"服务已停用");
        String title=Problem.text(event.path("annotations").path("summary").asString(labels.path("alertname").asString("")),200,"告警标题");
        String severity=switch(labels.path("severity").asString("info").toLowerCase(Locale.ROOT)) {
            case "critical","fatal","high" -> "CRITICAL";
            case "warning","warn" -> "WARNING";
            case "info","none" -> "INFO";
            default -> throw new Problem(400,"未知 severity");
        };
        String source="am/"+integration;
        long id;
        try {
            id=store.insertIdempotent("""
                insert into alerts(service_id,source,external_id,severity,title,occurred_at,integration_id,fingerprint,lifecycle_status,resolved_at)
                values (?,?,?,?,?,?,?,?,?,?)
                """,serviceId,source,fingerprint+"@"+start,severity,title,start,integration,fingerprint,state.toUpperCase(Locale.ROOT),end);
        } catch(DuplicateKeyException duplicate) {
            old=existing(integration,fingerprint,start);
            if(old==null) throw duplicate;
            return updateExisting(old,end,team);
        }
        String machine="alertmanager";
        store.audit(machine,end==null?"ALERT_FIRING":"ALERT_RESOLVED",id);
        if(end==null) {
            var users=store.rows("""
                select a.id from accounts a join team_members m on m.account_id=a.id
                where a.active=true and a.id=? and m.team_id=?
                """,service.get("ownerId"),team);
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

    private Map<String,Object> existing(String integration,String fingerprint,OffsetDateTime start) {
        return store.rows("""
            select a.id,a.lifecycle_status,s.team_id from alerts a join services s on s.id=a.service_id
            where a.integration_id=? and a.fingerprint=? and a.occurred_at=? for update
            """,integration,fingerprint,start).stream().findFirst().orElse(null);
    }

    private long updateExisting(Map<String,Object> alert,OffsetDateTime end,long team) {
        if(!Long.valueOf(team).equals(alert.get("teamId"))) throw new Problem(403,"告警原归属与接入授权不一致");
        long id=(long)alert.get("id");
        if(end==null || !"FIRING".equals(alert.get("lifecycleStatus"))) return id;
        store.db.update("update alerts set lifecycle_status='RESOLVED',resolved_at=? where id=?",end,id);
        store.audit("alertmanager","ALERT_RESOLVED",id);
        var tickets=store.rows("select id from tickets where alert_id=?",id);
        if(!tickets.isEmpty()) {
            long ticketId=(long)tickets.getFirst().get("id");
            store.db.update("update tickets set version=version+1 where id=?",ticketId);
            store.db.update("insert into timeline(ticket_id,action,actor,note) values (?,'ALERT_RESOLVED',?,?)",
                ticketId,"alertmanager","监控已恢复，仍需人工验证工单");
            notifications.notifyTicket(ticketId,"ALERT_RESOLVED","监控收到恢复信号，请人工核验工单 #"+ticketId);
        }
        return id;
    }

    private static OffsetDateTime timestamp(JsonNode node,String key) {
        try { return Instant.parse(node.path(key).asString("")).truncatedTo(ChronoUnit.MICROS).atOffset(java.time.ZoneOffset.UTC); }
        catch(DateTimeParseException invalid) {throw new Problem(400,key+" 需要带时区的时间");}
    }
}
