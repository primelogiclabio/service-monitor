package dev.svcmon.hub.security;

import java.util.UUID;

import dev.svcmon.hub.store.MetaRepo;
import org.springframework.stereotype.Component;

/** Stable ID of this hub, so a console can tell hubs apart across renames and address changes. */
@Component
public class HubIdentity {

    private final MetaRepo meta;
    private volatile String id;

    public HubIdentity(MetaRepo meta) {
        this.meta = meta;
    }

    public String id() {
        String current = id;
        if (current == null) {
            synchronized (this) {
                if (id == null) {
                    id = meta.get("hub_id").orElseGet(() -> {
                        String created = UUID.randomUUID().toString();
                        meta.set("hub_id", created);
                        return created;
                    });
                }
                current = id;
            }
        }
        return current;
    }
}
