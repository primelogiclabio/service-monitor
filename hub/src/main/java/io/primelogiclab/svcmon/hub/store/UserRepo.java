package io.primelogiclab.svcmon.hub.store;

import java.util.List;
import java.util.Optional;

import io.primelogiclab.svcmon.hub.model.Role;
import io.primelogiclab.svcmon.hub.model.UserRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Users; names are unique without regard to case. */
@Repository
public class UserRepo {

    private static final RowMapper<UserRecord> MAPPER = (rs, i) -> new UserRecord(
            rs.getString("id"),
            rs.getString("username"),
            Role.valueOf(rs.getString("role")),
            rs.getString("password_hash"),
            rs.getInt("must_change_password") != 0,
            rs.getInt("disabled") != 0,
            rs.getLong("created_at"),
            Rows.lng(rs, "password_changed_at"),
            Rows.lng(rs, "last_sign_in_at"));

    private final JdbcClient jdbc;

    public UserRepo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UserRecord u) {
        jdbc.sql("INSERT INTO users (id, username, role, password_hash, must_change_password, disabled, created_at, "
                        + "password_changed_at, last_sign_in_at) VALUES (:id, :username, :role, :hash, :mustChange, "
                        + ":disabled, :createdAt, :changedAt, :signedInAt)")
                .param("id", u.id())
                .param("username", u.username())
                .param("role", u.role().name())
                .param("hash", u.passwordHash())
                .param("mustChange", u.mustChangePassword() ? 1 : 0)
                .param("disabled", u.disabled() ? 1 : 0)
                .param("createdAt", u.createdAt())
                .param("changedAt", u.passwordChangedAt())
                .param("signedInAt", u.lastSignInAt())
                .update();
    }

    public Optional<UserRecord> findById(String id) {
        return jdbc.sql("SELECT * FROM users WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public Optional<UserRecord> findByUsername(String username) {
        return jdbc.sql("SELECT * FROM users WHERE username = :u").param("u", username).query(MAPPER).optional();
    }

    public List<UserRecord> findAll() {
        return jdbc.sql("SELECT * FROM users ORDER BY username").query(MAPPER).list();
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM users").query(Long.class).single();
    }

    public long countEnabledAdmins() {
        return jdbc.sql("SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND disabled = 0").query(Long.class).single();
    }

    /** {@code changedAt} is null for a password someone else set (initial or temporary), which must be changed. */
    public void setPassword(String id, String hash, boolean mustChange, Long changedAt) {
        jdbc.sql("UPDATE users SET password_hash = :hash, must_change_password = :mustChange, "
                        + "password_changed_at = :changedAt WHERE id = :id")
                .param("id", id)
                .param("hash", hash)
                .param("mustChange", mustChange ? 1 : 0)
                .param("changedAt", changedAt)
                .update();
    }

    public void setRole(String id, Role role) {
        jdbc.sql("UPDATE users SET role = :role WHERE id = :id").param("id", id).param("role", role.name()).update();
    }

    public void setDisabled(String id, boolean disabled) {
        jdbc.sql("UPDATE users SET disabled = :d WHERE id = :id").param("id", id).param("d", disabled ? 1 : 0).update();
    }

    public void signedIn(String id, long now) {
        jdbc.sql("UPDATE users SET last_sign_in_at = :now WHERE id = :id").param("id", id).param("now", now).update();
    }

    public boolean delete(String id) {
        return jdbc.sql("DELETE FROM users WHERE id = :id").param("id", id).update() > 0;
    }
}
