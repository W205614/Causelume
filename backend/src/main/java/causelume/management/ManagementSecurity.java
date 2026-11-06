package causelume.management;

import io.causelume.ManagementAccess;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@ManagementContextConfiguration
public class ManagementSecurity {
    @Bean FilterRegistrationBean<ManagementAccess> managementTokenFilter(Environment environment) {
        var registration=new FilterRegistrationBean<>(new ManagementAccess(environment));
        registration.addUrlPatterns("/actuator/*");
        registration.setOrder(-100);
        return registration;
    }
}
