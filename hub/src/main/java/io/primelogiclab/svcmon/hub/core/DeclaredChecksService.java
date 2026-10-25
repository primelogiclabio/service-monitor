package io.primelogiclab.svcmon.hub.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import io.primelogiclab.svcmon.hub.config.HubProperties;
import io.primelogiclab.svcmon.hub.model.CheckDef;
import io.primelogiclab.svcmon.hub.model.CheckSource;
import io.primelogiclab.svcmon.hub.model.CheckType;
import io.primelogiclab.svcmon.hub.model.RunFrom;
import io.primelogiclab.svcmon.hub.model.ServerInfo;
import io.primelogiclab.svcmon.hub.model.TokenRecord;
import io.primelogiclab.svcmon.hub.store.CheckRepo;
import io.primelogiclab.svcmon.hub.store.ServerRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Checks an agent declares in the checks file beside its config. An agent states the whole list for its own
 * server; the hub creates, updates and switches off checks to match. When the file and the hub disagree about a
 * check, the file wins. Off unless {@code svcmon.agent-declared-checks} is set.
 */
@Service
public class DeclaredChecksService {

    private static final Logger log = LoggerFactory.getLogger(DeclaredChecksService.class);
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}");

    /** One entry of the file. {@code id} is optional: without it a check is told apart by its type and target. */
    public record Declared(String id, String name, CheckType type, String target, Map<String, Object> config,
                           Integer intervalSeconds, Integer timeoutMs, Integer failuresBeforeDown) {
    }

    public record DeclareRequest(List<Declared> checks) {
    }

    /** What happened to one entry: created, updated, unchanged, taken over (a hub check became declared) or rejected. */
    public record Outcome(int index, String checkId, String result, String error) {
    }

    public record DeclareResponse(List<Outcome> results, List<String> removed) {
    }

    private final CheckService checkService;
    private final CheckRepo checks;
    private final ServerRepo servers;
    private final HubProperties props;

    public DeclaredChecksService(CheckService checkService, CheckRepo checks, ServerRepo servers, HubProperties props) {
        if (props.agentDeclaredChecksLimit() < 1 || props.agentDeclaredChecksLimit() > 1000) {
            throw new IllegalStateException("svcmon.agent-declared-checks-limit must be between 1 and 1000");
        }
        this.checkService = checkService;
        this.checks = checks;
        this.servers = servers;
        this.props = props;
    }

    public DeclareResponse declare(TokenRecord agent, DeclareRequest req) {
        if (!props.agentDeclaredChecks()) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Agent-declared checks are turned off on this hub (svcmon.agent-declared-checks)");
        }
        ServerInfo server = servers.find(agent.serverId()).orElseThrow(() -> ApiException.notFound("Server"));
        if (req == null || req.checks() == null) {
            throw ApiException.badRequest("checks is required (an empty list declares that there are none)");
        }
        List<Declared> list = req.checks();
        if (list.size() > props.agentDeclaredChecksLimit()) {
            throw ApiException.badRequest("At most " + props.agentDeclaredChecksLimit()
                    + " checks per server may be declared");
        }

        List<CheckRepo.Declared> known = checks.findDeclared(server.id());
        Map<String, CheckRepo.Declared> byKey = new HashMap<>();
        for (CheckRepo.Declared k : known) {
            byKey.put(k.key(), k);
        }
        // Checks made in the hub for this server that a declaration without an id may take over, oldest first.
        List<CheckDef> hubMade = new ArrayList<>(checks.findByServer(server.id()).stream()
                .filter(c -> c.source() == CheckSource.HUB && c.runFrom() == RunFrom.AGENT)
                .sorted(Comparator.comparingLong(CheckDef::createdAt))
                .toList());

        // Every entry's key counts as present, rejected ones too, so a typo does not switch a working check off.
        Set<String> present = new HashSet<>();
        List<Outcome> outcomes = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Declared d = list.get(i);
            if (d == null) {
                outcomes.add(new Outcome(i, null, "rejected", "the entry is empty"));
                continue;
            }
            try {
                String key = keyOf(d);
                if (!present.add(key)) {
                    throw ApiException.badRequest("the same check is declared twice (give one an id to tell them apart)");
                }
                outcomes.add(apply(i, d, key, server, byKey, hubMade));
            } catch (ApiException e) {
                outcomes.add(new Outcome(i, null, "rejected", e.getMessage()));
            }
        }

        List<String> removed = new ArrayList<>();
        for (CheckRepo.Declared k : known) {
            if (!present.contains(k.key()) && !k.def().removedFromFile()) {
                checks.update(k.def().withEnabled(false).withSource(CheckSource.AGENT, true));
                removed.add(k.def().name());
            }
        }

        log.info("Agent '{}' declared {} check(s): {} created, {} taken over, {} updated, {} unchanged, {} rejected,"
                        + " {} switched off", server.name(), list.size(), count(outcomes, "created"),
                count(outcomes, "taken over"), count(outcomes, "updated"), count(outcomes, "unchanged"),
                count(outcomes, "rejected"), removed.size());
        return new DeclareResponse(outcomes, removed);
    }

    private Outcome apply(int index, Declared d, String key, ServerInfo server,
                          Map<String, CheckRepo.Declared> byKey, List<CheckDef> hubMade) {
        CheckRequest request = new CheckRequest(nameOf(d), d.type(), d.target(), d.config(), d.intervalSeconds(),
                d.timeoutMs(), d.failuresBeforeDown(), RunFrom.AGENT, server.id(), null);

        CheckRepo.Declared known = byKey.get(key);
        if (known != null) {
            CheckDef old = known.def();
            // The hub keeps the on/off switch (muting); a check that came back after being removed switches on.
            CheckDef def = checkService.build(old.id(), request, old.createdAt())
                    .withSource(CheckSource.AGENT, false)
                    .withEnabled(old.removedFromFile() || old.enabled());
            if (!old.removedFromFile() && old.sameDefinition(def)) {
                return new Outcome(index, old.id(), "unchanged", null);
            }
            checks.update(def);
            return new Outcome(index, old.id(), "updated", null);
        }

        if (d.id() == null || d.id().isBlank()) {
            String target = d.target() == null ? "" : d.target().trim();
            CheckDef mine = hubMade.stream()
                    .filter(c -> c.type() == d.type() && (c.target() == null ? "" : c.target()).equals(target))
                    .findFirst()
                    .orElse(null);
            if (mine != null) {
                hubMade.remove(mine);
                CheckDef def = checkService.build(mine.id(), request, mine.createdAt())
                        .withSource(CheckSource.AGENT, false)
                        .withEnabled(mine.enabled());
                checks.update(def);
                checks.setDeclaredKey(def.id(), key);
                return new Outcome(index, def.id(), "taken over", null);
            }
        }

        CheckDef def = checkService.build(UUID.randomUUID().toString(), request, System.currentTimeMillis())
                .withSource(CheckSource.AGENT, false);
        checks.insert(def);
        checks.setDeclaredKey(def.id(), key);
        return new Outcome(index, def.id(), "created", null);
    }

    /** The id the file gives, or the type and target. The prefix keeps the two kinds from colliding. */
    private static String keyOf(Declared d) {
        if (d.id() != null && !d.id().isBlank()) {
            String id = d.id().trim();
            if (!ID.matcher(id).matches()) {
                throw ApiException.badRequest("id must be 1-100 characters: letters, digits, '_', '.', ':' or '-'");
            }
            return "id:" + id;
        }
        if (d.type() == null) {
            throw ApiException.badRequest("type is required");
        }
        return "type:" + d.type().name() + ":" + (d.target() == null ? "" : d.target().trim());
    }

    private static String nameOf(Declared d) {
        if (d.name() != null && !d.name().isBlank()) {
            return d.name();
        }
        String type = d.type() == null ? "check" : d.type().name().toLowerCase();
        return d.target() == null || d.target().isBlank() ? type : type + " " + d.target().trim();
    }

    private static long count(List<Outcome> outcomes, String result) {
        return outcomes.stream().filter(o -> o.result().equals(result)).count();
    }
}
