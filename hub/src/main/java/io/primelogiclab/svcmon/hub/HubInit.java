package io.primelogiclab.svcmon.hub;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code java -jar svcmon-hub.jar init [DIR] [--force] [--stdout]}: writes application.yml, the hub's settings with
 * every one commented out at its default, so a download can be configured by editing a file and nothing else. It
 * runs before the hub starts, touches nothing but that one file, and never replaces an existing one unless asked.
 */
final class HubInit {

    static final String TEMPLATE = "/init/application.yml";
    static final String FILE_NAME = "application.yml";

    private HubInit() {
    }

    /** Runs the command. Returns the process exit code: 0 done, 1 refused or failed, 2 bad arguments. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        Path dir = Path.of(".");
        boolean force = false;
        boolean stdout = false;
        boolean haveDir = false;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--force" -> force = true;
                case "--stdout" -> stdout = true;
                case "-h", "--help" -> {
                    out.println(usage());
                    return 0;
                }
                default -> {
                    if (a.startsWith("-") || haveDir) {
                        err.println("Unknown argument: " + a + "\n" + usage());
                        return 2;
                    }
                    dir = Path.of(a);
                    haveDir = true;
                }
            }
        }
        String text;
        try (InputStream in = HubInit.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                err.println("This jar has no settings template (" + TEMPLATE + ").");
                return 1;
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            err.println("Cannot read the settings template: " + e.getMessage());
            return 1;
        }
        if (stdout) {
            out.print(text);
            return 0;
        }
        Path target = dir.resolve(FILE_NAME);
        try {
            Files.createDirectories(dir);
            if (Files.exists(target) && !force) {
                err.println(target.toAbsolutePath().normalize() + " already exists; it was not changed. "
                        + "Use --force to replace it, or --stdout to print the template.");
                return 1;
            }
            Files.writeString(target, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            err.println("Cannot write " + target + ": " + e.getMessage());
            return 1;
        }
        out.println("Wrote " + target.toAbsolutePath().normalize() + ": every setting at its default, commented out.");
        out.println("Edit it, then start the hub from " + dir.toAbsolutePath().normalize() + ":  java -jar <this jar>");
        return 0;
    }

    static String usage() {
        return "Usage: java -jar svcmon-hub.jar init [DIR] [--force] [--stdout]\n"
                + "  Writes application.yml (all settings commented out at their defaults) into DIR, default the current folder.\n"
                + "  --force   replace an existing application.yml\n"
                + "  --stdout  print it instead of writing a file";
    }
}
