package io.causelume;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootApplication
public class Application {
    public static void main(String[] args) throws Exception {
        if(args.length>0 && args[0].equals("storage")) {
            OfflineStorage.run(args);
            return;
        }
        SpringApplication.run(Application.class, args);
    }

    @Bean BCryptPasswordEncoder encoder() { return new BCryptPasswordEncoder(); }

    @Bean SmartInitializingSingleton initialize(Accounts accounts) {
        return accounts::bootstrap;
    }
}
