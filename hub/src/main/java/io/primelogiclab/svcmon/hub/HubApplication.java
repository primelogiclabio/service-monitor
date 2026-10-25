package io.primelogiclab.svcmon.hub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class HubApplication {

    public static void main(String[] args) {
        // "init" runs before anything starts: it writes a settings template and exits.
        if (args.length > 0 && args[0].equals("init")) {
            System.exit(HubInit.run(args, System.out, System.err));
        }
        SpringApplication.run(HubApplication.class, args);
    }
}
