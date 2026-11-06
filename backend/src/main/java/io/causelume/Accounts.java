package io.causelume;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class Accounts {
    final Store store;
    final Environment env;
    final BCryptPasswordEncoder encoder;
    final SecondFactor mfa;
    final LoginAttempts attempts;
    final LoginAttempts passwordChecks=new LoginAttempts();
    final String dummyHash;
    Accounts(Store store, Environment env, BCryptPasswordEncoder encoder,LoginAttempts attempts,SecondFactor mfa) {
        this.store=store; this.env=env; this.encoder=encoder;
        this.attempts=attempts; this.mfa=mfa;
        this.dummyHash=encoder.encode(java.util.UUID.randomUUID().toString());
    }

    @Transactional
    public void bootstrap() {
        if (store.db.queryForObject("select count(*) from accounts", Long.class)>0) return;
        create("admin",env.getRequiredProperty("APP_ADMIN_PASSWORD"),"ADMIN");
        create("operator",env.getRequiredProperty("APP_OPERATOR_PASSWORD"),"OPERATOR");
    }

    private long create(String username,String password,String role) {
        validatePassword(password);
        return store.insert("insert into accounts(username,password_hash,role) values (?,?,?)",
            Problem.text(username,64,"username"),encoder.encode(password),Problem.choice(role,Set.of("ADMIN","OPERATOR"),"role"));
    }
    static void validatePassword(String password) {
        if (password==null || password.length()<12 || password.getBytes(StandardCharsets.UTF_8).length>72)
            throw new Problem(400,"密码至少 12 个字符，最多 72 个 UTF-8 字节");
    }

    record Login(String username,String password,String code) {}
    record Account(String username,String password,String role,String email) {}

    @GetMapping("/health") Object health() {
        store.db.queryForObject("select 1", Integer.class);
        return Map.of("status","UP");
    }
    @PostMapping("/login") @Transactional(noRollbackFor=Problem.class) public Actor login(@RequestBody Login login,HttpServletRequest req) {
        String peer=req.getRemoteAddr();
        var decision=attempts.check(peer);
        if (decision!=LoginAttempts.Decision.ALLOW) {
            if(decision==LoginAttempts.Decision.FIRST_LIMIT) store.audit(peer,"LOGIN_LIMITED",0);
            throw new Problem(429,"登录请求过多，请在一分钟后重试");
        }
        String username=login.username==null ? "" : login.username.strip();
        var rows=username.length()>64 ? java.util.List.<Map<String,Object>>of()
                : store.rows("select * from accounts where username=? for update",username);
        String hash = rows.isEmpty() ? dummyHash : (String)rows.getFirst().get("passwordHash");
        if (login.password==null || login.password.getBytes(StandardCharsets.UTF_8).length>72
            || !encoder.matches(login.password,hash) || rows.isEmpty() || !(boolean)rows.getFirst().get("active")) {
            store.audit(peer,"LOGIN_DENIED",0);
            throw new Problem(401,"用户名或密码错误");
        }
        var row=rows.getFirst();
        if(row.get("mfaSeed")!=null) {
            if(login.code==null || login.code.isBlank()) throw new Problem(401,"请输入动态验证码或恢复码","MFA_REQUIRED");
            if(!mfa.consume(store,row,login.code)) {
                store.audit(peer,"MFA_DENIED",0);
                throw new Problem(401,"验证码无效或已使用","MFA_REQUIRED");
            }
        }
        var actor=identity(row);
        store.audit(actor.username(),"LOGIN",actor.id());
        if (req.getSession(false)!=null) req.getSession(false).invalidate();
        req.getSession(true).setAttribute("actor",actor);
        return actor;
    }

    @GetMapping("/me") Actor me(HttpServletRequest req) { return Access.actor(req); }
    @PostMapping("/logout") Object logout(HttpServletRequest req) {
        var actor=Access.actor(req);
        store.audit(actor.username(),"LOGOUT",actor.id());
        req.getSession().invalidate();
        return Map.of("ok",true);
    }
    @PostMapping("/users") @Transactional
    public Object user(@RequestBody Account input,HttpServletRequest req) {
        Actor actor=adminWriter(req);
        long id=create(input.username,input.password,input.role);
        store.db.update("update accounts set email=? where id=?",email(input.email),id);
        store.audit(actor.username(),"CREATE_USER",id);
        return store.one("select id,username,role from accounts where id=?",id);
    }
    record Password(String currentPassword,String newPassword) {}
    record Reset(String newPassword) {}
    record Status(Boolean active) {}
    record Profile(String email) {}
    record Enroll(String password) {}
    record Confirm(String code) {}

    private Actor identity(Map<String,Object> row) {
        boolean enabled=row.get("mfaSeed")!=null;
        return new Actor((long)row.get("id"),(String)row.get("username"),(String)row.get("role"),
                (String)row.get("email"),enabled,mfa.required && !enabled,(long)row.get("authVersion"));
    }
    private Map<String,Object> own(HttpServletRequest req) {
        var actor=Access.actor(req);
        var row=store.one("select * from accounts where id=? for update",actor.id());
        if (!(boolean)row.get("active") || actor.authVersion()!=(long)row.get("authVersion")) throw new Problem(401,"请重新登录");
        return row;
    }
    private Actor adminWriter(HttpServletRequest req) {
        var actor=Access.actor(req);
        actor.admin();
        // A concurrent revocation and a privileged account write must see the same ordering.
        store.rows("select id from accounts where role='ADMIN' order by id for update");
        own(req);
        return actor;
    }

    static class PasswordDenied extends Problem {
        PasswordDenied(int status,String message) { super(status,message); }
    }

    private void verifyPassword(Map<String,Object> row,String supplied) {
        long id=(long)row.get("id");
        String username=(String)row.get("username");
        var decision=passwordChecks.check(Long.toString(id));
        if(decision!=LoginAttempts.Decision.ALLOW) {
            if(decision==LoginAttempts.Decision.FIRST_LIMIT) store.audit(username,"REAUTH_LIMITED",id);
            throw new PasswordDenied(429,"密码验证请求过多，请在一分钟后重试");
        }
        if(supplied==null || supplied.getBytes(StandardCharsets.UTF_8).length>72 || !encoder.matches(supplied,(String)row.get("passwordHash"))) {
            store.audit(username,"REAUTH_DENIED",id);
            throw new PasswordDenied(403,"当前密码错误");
        }
    }
    private String email(String value) {
        if(value==null || value.isBlank()) return null;
        String addr=value.strip();
        if(addr.length()>254 || !addr.matches("[^\\s@,;<>]+@[^\\s@,;<>]+\\.[^\\s@,;<>]+"))
            throw new Problem(400,"邮箱格式无效");
        return addr;
    }

    @PostMapping("/account/profile") @Transactional
    public Object profile(@RequestBody Profile input,HttpServletRequest req) {
        var row=own(req);
        String value=email(input.email);
        store.db.update("update accounts set email=? where id=?",value,row.get("id"));
        row.put("email",value);
        var actor=identity(row);
        req.getSession().setAttribute("actor",actor);
        store.audit(actor.username(),"UPDATE_PROFILE",actor.id());
        return actor;
    }

    @PostMapping("/account/password") @Transactional(noRollbackFor=PasswordDenied.class)
    public Object changePassword(@RequestBody Password input,HttpServletRequest req) {
        var row=own(req);
        verifyPassword(row,input.currentPassword);
        validatePassword(input.newPassword);
        store.db.update("update accounts set password_hash=?,auth_version=auth_version+1 where id=?",encoder.encode(input.newPassword),row.get("id"));
        store.audit((String)row.get("username"),"CHANGE_PASSWORD",(long)row.get("id"));
        req.getSession().invalidate();
        return Map.of("ok",true);
    }
    @PostMapping("/users/{id}/password") @Transactional
    public Object reset(@PathVariable long id,@RequestBody Reset input,HttpServletRequest req) {
        var actor=adminWriter(req);
        validatePassword(input.newPassword);
        if(store.db.update("update accounts set password_hash=?,auth_version=auth_version+1 where id=?",encoder.encode(input.newPassword),id)==0)
            throw new Problem(404,"账户不存在");
        store.audit(actor.username(),"RESET_PASSWORD",id);
        return Map.of("ok",true);
    }
    @PostMapping("/users/{id}/status") @Transactional
    public Object status(@PathVariable long id,@RequestBody Status input,HttpServletRequest req) {
        var actor=adminWriter(req);
        if(input.active==null) throw new Problem(400,"需要 active");
        var target=store.one("select role,active from accounts where id=? for update",id);
        if(input.active.equals(target.get("active"))) return Map.of("ok",true);
        if(!input.active && "ADMIN".equals(target.get("role")) &&
            store.db.queryForObject("select count(*) from accounts where role='ADMIN' and active=true",Long.class)<=1)
            throw new Problem(409,"必须保留至少一名启用的管理员");
        store.db.update("update accounts set active=?,auth_version=auth_version+1 where id=?",input.active,id);
        store.audit(actor.username(),input.active ? "ENABLE_ACCOUNT" : "DISABLE_ACCOUNT",id);
        return Map.of("ok",true);
    }

    @PostMapping("/account/mfa/enroll") @Transactional(noRollbackFor=PasswordDenied.class)
    public Object enroll(@RequestBody Enroll input,HttpServletRequest req) {
        var row=own(req);
        verifyPassword(row,input.password);
        if(row.get("mfaSeed")!=null) throw new Problem(409,"账户已经绑定 MFA");
        byte[] seed=mfa.newSeed();
        String secret=mfa.base32(seed);
        req.getSession().setAttribute("pendingSeed",mfa.encrypt((long)row.get("id"),seed));
        String username=java.net.URLEncoder.encode((String)row.get("username"),StandardCharsets.UTF_8).replace("+","%20");
        return Map.of("secret",secret,"uri","otpauth://totp/Causelume:"+username+"?secret="+secret+"&issuer=Causelume");
    }

    @PostMapping("/account/mfa/confirm") @Transactional
    public Object confirm(@RequestBody Confirm input,HttpServletRequest req) {
        var row=own(req);
        long id=(long)row.get("id");
        String pending=(String)req.getSession().getAttribute("pendingSeed");
        if(pending==null || row.get("mfaSeed")!=null) throw new Problem(409,"请开始新的 MFA 绑定");
        long step=mfa.match(mfa.decrypt(id,pending),input.code,-1);
        if(step<0) throw new Problem(400,"验证码无效");
        store.db.update("update accounts set mfa_seed=?,last_totp_step=?,auth_version=auth_version+1 where id=?",pending,step,id);
        var codes=mfa.issueRecoveryCodes(store,id);
        row.put("mfaSeed",pending);
        row.put("authVersion",(long)row.get("authVersion")+1);
        req.changeSessionId();
        req.getSession().setAttribute("actor",identity(row));
        req.getSession().removeAttribute("pendingSeed");
        store.audit((String)row.get("username"),"MFA_ENROLLED",id);
        return Map.of("recoveryCodes",codes);
    }
}
