package io.causelume;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/services")
class Observations {
    private final Store store;
    private final InternalClient upstream;
    Observations(Store store, InternalClient upstream) {
        this.store = store;
        this.upstream = upstream;
    }
    @GetMapping("/{id}/{kind:metrics|logs}")
    Object observe(@PathVariable long id, @PathVariable String kind) {
        var service = store.one("select name from services where id=?",id);
        if (!"checkout".equals(service.get("name"))) throw new Problem(422,"该服务尚未登记证据来源");
        var data = upstream.call("DEMO_URL","EVIDENCE_TOKEN","/"+kind,null,5);
        if (!"checkout".equals(data.path("service").asString(""))) throw new Problem(502,"证据来源无效");
        if (kind.equals("metrics") && (!data.path("requests").isIntegralNumber() || !data.path("errors").isIntegralNumber()
                || !data.path("observedAt").isString())) throw new Problem(502,"指标字段无效");
        if (kind.equals("logs") && !data.path("entries").isArray()) throw new Problem(502,"日志字段无效");
        return data;
    }
}
