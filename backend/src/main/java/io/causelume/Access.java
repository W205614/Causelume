package io.causelume;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.Serializable;
import java.net.URI;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.boot.web.servlet.ServletContextInitializer;

record Actor(long id,String username,String role,String email,boolean mfaEnabled,boolean enrollmentRequired,
             @com.fasterxml.jackson.annotation.JsonIgnore long authVersion) implements Serializable {
    void admin() {
        if (!role.equals("ADMIN")) throw new Problem(403,"需要管理员权限");
    }
}

@Configuration
class Access implements WebMvcConfigurer, HandlerInterceptor {
    private final String publicOrigin;
    private final Store store;

    Access(Environment environment,Store store) {
        this.store=store;
        publicOrigin=environment.getProperty("APP_PUBLIC_ORIGIN","");
        if (!publicOrigin.isEmpty()) {
            URI uri=URI.create(publicOrigin);
            if (!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                    || !publicOrigin.equals("https://"+uri.getRawAuthority()))
                throw new IllegalArgumentException("APP_PUBLIC_ORIGIN 需要 HTTPS origin，仅含协议、主机和可选端口");
        }
    }

    @Bean ServletContextInitializer sessionTransport() {
        return context -> {
            if (!publicOrigin.isEmpty()) context.getSessionCookieConfig().setSecure(true);
        };
    }
    static Actor actor(HttpServletRequest req) {
        var session=req.getSession(false);
        if (session == null || !(session.getAttribute("actor") instanceof Actor actor)) throw new Problem(401,"请先登录");
        return actor;
    }

    public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(this).addPathPatterns("/api/**"); }

    public boolean preHandle(HttpServletRequest req,HttpServletResponse res,Object handler) {
        res.setHeader("Cache-Control","no-store");
        if (!req.getMethod().equals("GET") && !req.getMethod().equals("HEAD")) {
            String origin=req.getHeader("Origin");
            URI local=URI.create(req.getRequestURL().toString());
            String expected=publicOrigin.isEmpty() ? local.getScheme()+"://"+local.getRawAuthority() : publicOrigin;
            if (origin != null && !origin.equals(expected))
                throw new Problem(403,"跨站写入被拒绝");
            String media=req.getContentType();
            if (media==null || !media.split(";",2)[0].trim().equalsIgnoreCase("application/json"))
                throw new Problem(415,"写入请求必须使用 application/json");
        }
        String path=req.getRequestURI();
        if (path.equals("/api/health") || path.equals("/api/login")) return true;
        Actor actor=actor(req);
        var versions=store.rows("select auth_version from accounts where id=? and active=true",actor.id());
        if (versions.isEmpty() || actor.authVersion()!=(long)versions.getFirst().get("authVersion")) {
            req.getSession().invalidate();
            throw new Problem(401,"账户状态已变更，请重新登录");
        }
        if (actor.enrollmentRequired() && !java.util.Set.of("/api/me","/api/logout","/api/account/mfa/enroll","/api/account/mfa/confirm").contains(path))
            throw new Problem(403,"请先完成双因素认证绑定");
        return true;
    }
}
