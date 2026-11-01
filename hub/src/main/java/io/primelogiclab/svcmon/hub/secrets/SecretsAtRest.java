package io.primelogiclab.svcmon.hub.secrets;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * At start, before the hub serves anything: makes the key for stored secrets on a new hub, refuses to start when
 * sealed secrets are stored that the key file cannot open (a lost or wrong file must not go unnoticed, and a new key
 * would not bring them back), and seals what is still plain: the secrets stored before the hub encrypted them, and
 * any a rotation left under an older key. Not a Flyway migration: it needs the key, and an applied migration must
 * not depend on the hub's code.
 */
@Component
public class SecretsAtRest implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(SecretsAtRest.class);

    private final SecretBox box;
    private final StoredSecrets stored;

    public SecretsAtRest(DataSource ds, JsonMapper json, SecretBox box) {
        this.box = box;
        this.stored = new StoredSecrets(ds, json, box);
    }

    @Override
    public void afterSingletonsInstantiated() {
        SecretKeys keys = box.keys();
        if (!keys.exists()) {
            int sealed = stored.countSealed();
            if (sealed > 0) {
                throw new IllegalStateException(sealed + " stored secret(s) are encrypted with the key in "
                        + keys.file() + ", which is missing. " + recovery());
            }
            keys.create();
            log.info("Made the key for stored secrets: {}", keys.file());
        }
        StoredSecrets.Counts counts = stored.count();
        if (counts.unreadable() > 0) {
            throw new IllegalStateException(counts.unreadable() + " stored secret(s) cannot be decrypted with the"
                    + " keys in " + keys.file() + " (in " + String.join(", ", counts.unreadableIn()) + "). "
                    + recovery());
        }
        int sealed = stored.sealAll();
        if (sealed > 0) {
            stored.compact();
            log.info("Encrypted {} stored secret(s) with the key in {}, and rebuilt the database file so it keeps no"
                    + " copy of them as they were", sealed, keys.file());
        }
    }

    private static String recovery() {
        return "Restore the key file from your backup of the hub's data folder (or point svcmon.secrets.key-file at"
                + " it). If it is lost for good, 'java -jar svcmon-hub.jar secrets forget-unreadable' removes those"
                + " secrets so the hub can start; enter them again afterwards.";
    }
}
