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
        // "users reset NAME" reaches only the database: the way back in when no administrator knows a password.
        if (args.length > 0 && args[0].equals("users")) {
            System.exit(UsersCommand.run(args, System.out, System.err));
        }
        // "oidc key|rotate-key" reaches only the hub's key file, for setting up the identity provider.
        if (args.length > 0 && args[0].equals("oidc")) {
            System.exit(OidcCommand.run(args, System.out, System.err));
        }
        SpringApplication.run(HubApplication.class, args);
    }
}
