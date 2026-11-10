package io.causelume;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ManagementAccess extends OncePerRequestFilter {
    private final Environment env;
    public ManagementAccess(Environment env) { this.env=env; }

    @Override protected boolean shouldNotFilter(HttpServletRequest req) {
        // Match the container-decoded path used by endpoint routing.
        return !req.getServletPath().startsWith("/actuator");
    }

    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
            throws IOException,ServletException {
        String token=env.getProperty("MANAGEMENT_TOKEN","");
        String supplied=req.getHeader("Authorization");
        if(token.isBlank() || supplied==null || !MessageDigest.isEqual(
                ("Bearer "+token).getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))) {
            res.setStatus(401);
            return;
        }
        chain.doFilter(req,res);
    }
}
