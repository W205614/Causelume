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
    ServiceSettings(Store store,InternalClient client){this.store=store;this.client=client;}
    record Settings(String name,String description,String resource,String environment,Long ownerId,
                    String runbook,Boolean active,String sourceId,String serviceLabel,Long version){}

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
        var actor=Access.actor(req);actor.admin();
        var prior=store.one("select * from services where id=?",id);
        if(in.version()==null || !in.version().equals(prior.get("version")))throw new Problem(409,"服务已更新，请刷新");
        if(in.active()==null)throw new Problem(400,"缺少服务启停状态");
        if(in.ownerId()!=null)store.one("select id from accounts where id=? and active=true",in.ownerId());
        String runbook=null;
        if(in.runbook()!=null && !in.runbook().isBlank()){
            runbook=Problem.text(in.runbook(),1000,"runbook");
            try{
                var link=URI.create(runbook);
                if(!("https".equals(link.getScheme())||"http".equals(link.getScheme())) || link.getHost()==null || link.getUserInfo()!=null)
                    throw new IllegalArgumentException();
            }catch(IllegalArgumentException invalid){throw new Problem(400,"Runbook 需要无内嵌认证的 HTTP(S) 链接");}
        }
        String source=in.sourceId()==null||in.sourceId().isBlank()?null:Problem.text(in.sourceId(),64,"sourceId");
        String serviceLabel=source==null?null:Problem.text(in.serviceLabel(),100,"serviceLabel");
        String environment=Problem.text(in.environment(),64,"environment");
        if(source!=null){
            if(!source.matches("[A-Za-z0-9_.-]{1,64}")) throw new Problem(400,"无效数据源编号");
            if(!serviceLabel.matches("[A-Za-z0-9_.-]{1,80}") || !environment.matches("[A-Za-z0-9_.-]{1,64}")) throw new Problem(400,"数据源服务标签或环境无效");
            if(!Objects.equals(source,prior.get("sourceId"))){
                boolean approved=false;
                for(var entry:catalog().path("sources")) if(source.equals(entry.path("id").asString("")))approved=true;
                if(!approved)throw new Problem(400,"仅允许绑定服务端目录中的数据源");
            }
        }
        int changed=store.db.update("""
            update services set name=?,description=?,resource=?,environment=?,owner_id=?,runbook=?,active=?,
                 source_id=?,service_label=?,version=version+1 where id=? and version=?
            """,Problem.text(in.name(),100,"name"),Problem.text(in.description(),500,"description"),Problem.text(in.resource(),200,"resource"),
            environment,in.ownerId(),runbook,in.active(),source,serviceLabel,id,in.version());
        if(changed==0)throw new Problem(409,"服务已被其他操作者更新");
        store.audit(actor.username(),"UPDATE_SERVICE",id);
        return store.one("select * from services where id=?",id);
    }

    @GetMapping("/services/{id}/source-check") Object check(@PathVariable long id,HttpServletRequest req){
        Access.actor(req).admin();
        var source=store.one("select source_id,service_label,environment from services where id=?",id);
        if(source.get("sourceId")==null)throw new Problem(422,"服务尚未绑定数据源");
        String path="/sources/"+source.get("sourceId")+"/check?service="+encode(source.get("serviceLabel"))+"&environment="+encode(source.get("environment"));
        return client.call("AGENT_URL","AGENT_TOKEN",path,null,12);
    }
    private String encode(Object value){return URLEncoder.encode((String)value,StandardCharsets.UTF_8);}
}
