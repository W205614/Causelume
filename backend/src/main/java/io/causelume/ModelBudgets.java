package io.causelume;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/budget")
class ModelBudgets {
    private final Store store;
    private final Environment env;
    private final ObjectMapper json;

    ModelBudgets(Store store, Environment env, ObjectMapper json) {
        this.store=store;
        this.env=env;
        this.json=json;
    }

    private BigDecimal amount(String name) {
        String raw=env.getProperty(name);
        if(raw==null || raw.isBlank()) throw new Problem(503,"真实调查需要配置 "+name);
        try {
            var value=new BigDecimal(raw);
            if(value.signum()<=0 || value.scale()>6 || value.compareTo(new BigDecimal("10000"))>0)
                throw new NumberFormatException();
            return value;
        } catch(NumberFormatException invalid) {
            throw new Problem(503,name+" 必须为正数，最多六位小数且不超过 10000");
        }
    }

    void reserve(long task) {
        if(!env.getProperty("AGENT_MODE","offline").equals("holmes")) return;
        BigDecimal daily=amount("MODEL_DAILY_BUDGET_USD"), reserve=amount("MODEL_RESERVATION_USD");
        store.one("select id from model_budget_lock where id=1 for update");
        LocalDate today=LocalDate.now(ZoneOffset.UTC);
        BigDecimal committed=store.db.queryForObject(
            "select coalesce(sum(case when state='SETTLED' then actual_usd else reserved_usd end),0) from model_reservations where budget_date=?",
            BigDecimal.class,today);
        if(committed.add(reserve).compareTo(daily)>0) throw new Problem(429,"今日模型预留额度不足，请核对未结算调查或调整预算");
        store.db.update("insert into model_reservations(task_id,reserved_usd,state,budget_date) values (?,?,'HELD',?)",task,reserve,today);
    }

    @Scheduled(fixedDelay=2000)
    @Transactional
    public void reconcileFinished() {
        store.one("select id from model_budget_lock where id=1 for update");
        for(var row:store.rows("select r.task_id,t.analysis from model_reservations r join tasks t on t.id=r.task_id where r.state='HELD' and t.status in ('SUCCEEDED','FAILED')")) {
            BigDecimal actual=null;
            String analysis=(String)row.get("analysis");
            if(analysis!=null) {
                var usage=json.readTree(analysis).path("usage");
                if(usage.path("costUsd").isNumber() && validAmount(usage.path("costUsd").decimalValue()))
                    actual=usage.get("costUsd").decimalValue();
            }
            store.db.update("update model_reservations set state=?,actual_usd=? where task_id=? and state='HELD'",
                actual==null ? "UNKNOWN" : "SETTLED",actual,row.get("taskId"));
        }
    }

    @GetMapping Object overview(HttpServletRequest req) {
        Access.actor(req).admin();
        return Map.of("mode",env.getProperty("AGENT_MODE","offline"),
            "dailyBudgetUsd",env.getProperty("MODEL_DAILY_BUDGET_USD",""),
            "reservationUsd",env.getProperty("MODEL_RESERVATION_USD",""),
            "reservations",store.rows("select * from model_reservations order by task_id desc limit 100"));
    }

    private boolean validAmount(BigDecimal value) {
        return value!=null && value.signum()>=0 && value.scale()<=6 && value.compareTo(new BigDecimal("99999999"))<=0;
    }

    record Settlement(BigDecimal actualCostUsd,String reason) {}
    @PostMapping("/{taskId}/reconcile") @Transactional
    public Object settle(@PathVariable long taskId,@RequestBody Settlement input,HttpServletRequest req) {
        var actor=Access.actor(req);
        actor.admin();
        String reason=Problem.text(input.reason,2000,"reason");
        store.one("select id from model_budget_lock where id=1 for update");
        if(!validAmount(input.actualCostUsd))
            throw new Problem(400,"actualCostUsd 需要非负金额，最多六位小数");
        if(store.db.update("update model_reservations set state='SETTLED',actual_usd=?,reason=? where task_id=? and state='UNKNOWN'",
                input.actualCostUsd,reason,taskId)==0) throw new Problem(409,"仅可核对结果未知的预留");
        store.audit(actor.username(),"BUDGET_RECONCILE",taskId);
        return Map.of("ok",true);
    }
}
