package io.causelume;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import java.util.Objects;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api")
class ServiceSettings {
    private final Store store;
    private final InternalClient client;
    private final TeamAccess access;
    ServiceSettings(Store store,InternalClient client,TeamAccess access){this.store=store;this.client=client;this.access=access;}
    record Settings(String name,String description,String resource,String environment,Long ownerId,
                    String runbook,Boolean active,String sourceId,String serviceLabel,Long version,Boolean rebindSource){}

    @GetMapping("/sources") Object sources(HttpServletRequest req){
        Access.actor(req).admin();
        return catalog();
    }
    private JsonNode catalog(){
        var response=client.call("AGENT_URL","AGENT_TOKEN","/sources",null,5);
        if(!response.path("sources").isArray())throw new Problem(502,"Agent 数据源目录格式无效");
        return response;
    }

    @PostMapping("/services/{id}") @Transactional
    public Object update(@PathVariable long id,@RequestBody Settings in,HttpServletRequest req){
        var actor=Access.actor(req);
        long team=access.serviceTeam(id);
        access.writer(actor,team);
        access.requireManager(actor,team);
        var prior=store.one("select * from services where id=?",id);
        if(in.version()==null || !in.version().equals(prior.get("version")))throw new Problem(409,"服务已更新，请刷新");
        if(in.active()==null)throw new Problem(400,"缺少服务启停状态");
        if(in.ownerId()!=null)access.assignee(team,in.ownerId());
        String runbook=null;
        if(in.runbook()!=null && !in.runbook().isBlank()){
            runbook=Problem.text(in.runbook(),1000,"runbook");
            try{
                var link=URI.create(runbook);
                if(!("https".equals(link.getScheme())||"http".equals(link.getScheme())) || link.getHost()==null || link.getUserInfo()!=null)
                    throw new IllegalArgumentException();
            }catch(IllegalArgumentException invalid){throw new Problem(400,"Runbook 需要无内嵌认证的 HTTP(S) 链接");}
        }
        boolean platform=TeamAccess.platform(actor);
        if(!platform && (Boolean.TRUE.equals(in.rebindSource()) ||
                in.sourceId()!=null && !Objects.equals(in.sourceId(),prior.get("sourceId")) ||
                in.serviceLabel()!=null && !Objects.equals(in.serviceLabel(),prior.get("serviceLabel")) ||
                prior.get("sourceId")!=null && in.environment()!=null && !Objects.equals(in.environment(),prior.get("environment"))))
            throw new Problem(403,"仅平台管理员可以变更取证绑定");
        String source=platform?(in.sourceId()==null||in.sourceId().isBlank()?null:Problem.text(in.sourceId(),64,"sourceId")):(String)prior.get("sourceId");
        String serviceLabel=platform?(source==null?null:Problem.text(in.serviceLabel(),100,"serviceLabel")):(String)prior.get("serviceLabel");
        String environment=platform||source==null?Problem.text(in.environment(),64,"environment"):(String)prior.get("environment");
        boolean scoped=Boolean.TRUE.equals(prior.get("sourceTeamScoped"));
        if(source!=null){
            if(!source.matches("[A-Za-z0-9_.-]{1,64}")) throw new Problem(400,"无效数据源编号");
            if(!serviceLabel.matches("[A-Za-z0-9_.-]{1,80}") || !environment.matches("[A-Za-z0-9_.-]{1,64}")) throw new Problem(400,"数据源服务标签或环境无效");
            if(platform && (!Objects.equals(source,prior.get("sourceId")) || Boolean.TRUE.equals(in.rebindSource()))) {
                JsonNode approved=null;
                for(var entry:catalog().path("sources")) if(source.equals(entry.path("id").asString("")))approved=entry;
                if(approved==null)throw new Problem(400,"仅允许绑定服务端目录中的数据源");
                scoped=approved.path("teamScoped").asBoolean(false);
            }
            if(team!=TeamAccess.DEFAULT_TEAM && !scoped)throw new Problem(422,"此团队必须使用按团队隔离的数据源");
        }else{
            scoped=false;
        }
        int changed=store.db.update("""
            update services set name=?,description=?,resource=?,environment=?,owner_id=?,runbook=?,active=?,
                 source_id=?,service_label=?,source_team_scoped=?,version=version+1 where id=? and version=?
            """,Problem.text(in.name(),100,"name"),Problem.text(in.description(),500,"description"),Problem.text(in.resource(),200,"resource"),
            environment,in.ownerId(),runbook,in.active(),source,serviceLabel,scoped,id,in.version());
        if(changed==0)throw new Problem(409,"服务已被其他操作者更新");
        store.audit(actor.username(),"UPDATE_SERVICE",id);
        return store.one("select * from services where id=?",id);
    }

    @GetMapping("/services/{id}/source-check") Object check(@PathVariable long id,HttpServletRequest req){
        var actor=Access.actor(req);
        access.requireManager(actor,access.serviceTeam(id));
        var source=store.one("""
            select s.source_id,s.service_label,s.environment,s.source_team_scoped,s.team_id,t.slug from services s join teams t on t.id=s.team_id where s.id=?
            """,id);
        if(source.get("sourceId")==null)throw new Problem(422,"服务尚未绑定数据源");
        String path="/sources/"+source.get("sourceId")+"/check?service="+encode(source.get("serviceLabel"))+"&environment="+encode(source.get("environment"));
        String team=teamLabel(source);
        if(team!=null) path+="&team="+encode(team);
        return client.call("AGENT_URL","AGENT_TOKEN",path,null,12);
    }
    static String teamLabel(java.util.Map<String,Object> service) {
        if(Boolean.TRUE.equals(service.get("sourceTeamScoped"))) return (String)service.get("slug");
        if(!Long.valueOf(TeamAccess.DEFAULT_TEAM).equals(service.get("teamId"))) throw new Problem(422,"数据源缺少有效团队范围");
        return null;
    }
    private String encode(Object value){return URLEncoder.encode((String)value,StandardCharsets.UTF_8);}
}
