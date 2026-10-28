package io.primelogiclab.svcmon.hub.config;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.stereotype.Component;

/**
 * Stops the hub when a svcmon.* setting is not one it reads. Spring binds the names it knows and skips the rest, so
 * without this a typo, or a setting that was renamed, would be ignored without a word. It runs before any other bean
 * is made, so nothing happens (not even the database migration) with a mistyped file.
 * <p>
 * Settings from files, the command line and -D system properties are checked. Environment variables are checked only
 * for removed settings (SVCMON_JWT_ISSUER, say), not for unknown ones: the CLI's SVCMON_HUB and SVCMON_TOKEN may be
 * set on the same machine and would read as svcmon.hub and svcmon.token.
 */
@Component
public class SettingsGuard implements BeanFactoryPostProcessor {

    private static final String PREFIX = "svcmon.";

    /** Settings read with @Value rather than through HubProperties, and where. */
    static final Set<String> READ_ELSEWHERE = Set.of(
            "version",                    // HubController, StartupSummary
            "alert-retry-delays-ms",      // AlertDispatcher
            "watchdog-initial-delay-ms",  // ServerWatchdog
            "watchdog-interval-ms");      // ServerWatchdog

    /**
     * Settings that no longer exist, by the name people used, with what to do instead. A key under one of these
     * names (or under it, for a group) stops the hub with that advice.
     */
    static final Map<String, String> REMOVED = Map.of(
            "local-admin", "people now sign in as users; use svcmon.users.enabled, svcmon.users.initial-admin-username"
                    + " and svcmon.users.initial-admin-password, and svcmon.sessions.max-hours for session-hours"
                    + " (an existing local admin keeps its password as user 'admin', or the initial-admin-username)",
            "jwt", "the hub no longer accepts a sign-in provider's tokens; people sign in with their hub account, and a"
                    + " gateway in front of the hub (Keycloak through nginx, say) is only a gate: see 'Behind a sign-in"
                    + " gateway' in docs/service-monitor-reference.md",
            "ui.proxy", "the dashboard always signs in with the hub's own accounts, also behind a sign-in gateway: see"
                    + " 'Behind a sign-in gateway' in docs/service-monitor-reference.md");

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        List<String> problems = problems(beanFactory.getBean(ConfigurableEnvironment.class), REMOVED);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join("\n", problems)
                    + "\nThe settings the hub reads are in the template from 'java -jar svcmon-hub.jar init'"
                    + " and in docs/service-monitor-reference.md.");
        }
    }

    /** One line per unknown or removed setting, in the order the sources are searched. Empty means all is well. */
    static List<String> problems(ConfigurableEnvironment env, Map<String, String> removed) {
        Known known = known();
        Set<String> seen = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        for (PropertySource<?> source : env.getPropertySources()) {
            if (source instanceof SystemEnvironmentPropertySource variables) {
                removedVariables(variables, removed, seen, problems);
                continue;
            }
            if (!(source instanceof EnumerablePropertySource<?> names)) {
                continue;
            }
            for (String name : names.getPropertyNames()) {
                if (!name.startsWith(PREFIX) || !seen.add(uniform(name))) {
                    continue;
                }
                String advice = removedAdvice(name, removed);
                if (advice != null) {
                    problems.add("Setting " + name + " (" + where(source, name) + ") was removed: " + advice);
                } else if (!known.matches(name)) {
                    problems.add("Unknown setting " + name + " (" + where(source, name) + ")");
                }
            }
        }
        return problems;
    }

    /**
     * Environment variables for removed settings, such as SVCMON_JWT_ISSUER or SVCMON_LOCALADMIN_PASSWORD. Spring
     * reads a variable with its underscores as dots or dropped, so the name is compared with neither.
     */
    private static void removedVariables(SystemEnvironmentPropertySource variables, Map<String, String> removed,
                                         Set<String> seen, List<String> problems) {
        for (String variable : variables.getPropertyNames()) {
            if (!variable.toUpperCase(Locale.ROOT).startsWith("SVCMON_")) {
                continue;
            }
            String name = uniform(variable.substring("SVCMON_".length()));
            for (Map.Entry<String, String> e : removed.entrySet()) {
                if (name.startsWith(uniform(e.getKey()).replace(".", "")) && seen.add("env:" + variable)) {
                    problems.add("Setting svcmon." + e.getKey() + " (environment variable " + variable + ") was removed: "
                            + e.getValue());
                }
            }
        }
    }

    /** Every name HubProperties binds, walked through its nested records; a map takes any key below its name. */
    static Known known() {
        Set<String> exact = new LinkedHashSet<>();
        Set<String> mapPrefixes = new LinkedHashSet<>();
        collect(HubProperties.class, "", exact, mapPrefixes);
        READ_ELSEWHERE.forEach(name -> exact.add(uniform(name)));
        return new Known(exact, mapPrefixes);
    }

    private static void collect(Class<?> type, String prefix, Set<String> exact, Set<String> mapPrefixes) {
        for (RecordComponent c : type.getRecordComponents()) {
            String name = prefix + uniform(c.getName());
            if (c.getType().isRecord()) {
                collect(c.getType(), name + ".", exact, mapPrefixes);
            } else {
                exact.add(name);
                if (Map.class.isAssignableFrom(c.getType())) {
                    mapPrefixes.add(name + ".");
                }
            }
        }
    }

    record Known(Set<String> exact, Set<String> mapPrefixes) {

        boolean matches(String fullName) {
            String name = uniform(fullName.substring(PREFIX.length()));
            return exact.contains(name) || mapPrefixes.stream().anyMatch(name::startsWith);
        }
    }

    private static String removedAdvice(String fullName, Map<String, String> removed) {
        String name = uniform(fullName.substring(PREFIX.length()));
        for (Map.Entry<String, String> e : removed.entrySet()) {
            String old = uniform(e.getKey());
            if (name.equals(old) || name.startsWith(old + ".")) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * The form Spring's relaxed binding compares: list indexes dropped, no dashes or underscores, lower case. So
     * data-dir, dataDir and data_dir are one name, as they are to the binder.
     */
    static String uniform(String name) {
        return name.replaceAll("\\[[^\\]]*\\]", "").replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static String where(PropertySource<?> source, String name) {
        Origin origin = source instanceof OriginLookup<?> lookup ? ((OriginLookup<String>) lookup).getOrigin(name) : null;
        return origin != null ? origin.toString() : source.getName();
    }
}
