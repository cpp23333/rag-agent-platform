package io.kyligence.ragagent.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication(scanBasePackages = "io.kyligence.ragagent")
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
