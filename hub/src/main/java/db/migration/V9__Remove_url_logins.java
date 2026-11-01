package db.migration;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * HTTP checks do not sign in, and a check's URL may no longer hold a user name and password: it was shown to anyone
 * who can read checks and printed in alerts. This removes the ones stored before from the URL, from a name made up
 * from the URL ("http https://user:pw@host/"), and from the key of a check an agent declared, which the hub now
 * makes without the login. Self-contained on purpose: an applied migration must not change with the hub's code.
 */
public class V9__Remove_url_logins extends BaseJavaMigration {

    private static final String KEY_PREFIX = "type:HTTP:";

    @Override
    public void migrate(Context context) throws SQLException {
        Connection c = context.getConnection();
        Set<String> keys = new HashSet<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT server_id, declared_key FROM checks WHERE declared_key IS NOT NULL")) {
            while (rs.next()) {
                keys.add(rs.getString(1) + "\n" + rs.getString(2));
            }
        }
        List<Row> rows = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, name, target, server_id, declared_key FROM checks"
                     + " WHERE type = 'HTTP' AND target LIKE '%@%' ORDER BY created_at")) {
            while (rs.next()) {
                rows.add(new Row(rs.getString("id"), rs.getString("name"), rs.getString("target"),
                        rs.getString("server_id"), rs.getString("declared_key")));
            }
        }
        try (PreparedStatement update = c.prepareStatement(
                "UPDATE checks SET name = ?, target = ?, declared_key = ? WHERE id = ?")) {
            for (Row r : rows) {
                String clean = withoutLogin(r.target());
                if (clean.equals(r.target())) {
                    continue;
                }
                String key = r.declaredKey();
                if (key != null && key.equals(KEY_PREFIX + r.target())
                        && keys.add(r.serverId() + "\n" + KEY_PREFIX + clean)) {
                    keys.remove(r.serverId() + "\n" + key);
                    key = KEY_PREFIX + clean;
                }
                // Otherwise two declared checks differed only in their login; the newer keeps its old key (never
                // shown) and is switched off at the agent's next declaration, as the file can only hold one of them.
                update.setString(1, r.name().replace(r.target(), clean));
                update.setString(2, clean);
                update.setString(3, key);
                update.setString(4, r.id());
                update.executeUpdate();
            }
        }
    }

    private record Row(String id, String name, String target, String serverId, String declaredKey) {
    }

    /** The URL without the user name and password before its host, or unchanged when it holds none. */
    static String withoutLogin(String url) {
        String login;
        try {
            login = URI.create(url).getRawUserInfo();
        } catch (IllegalArgumentException e) {
            return url;
        }
        int at = login == null ? -1 : url.indexOf("//" + login + "@");
        return at < 0 ? url : url.substring(0, at + 2) + url.substring(at + 3 + login.length());
    }
}
