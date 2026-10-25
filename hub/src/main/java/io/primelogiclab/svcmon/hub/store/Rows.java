package io.primelogiclab.svcmon.hub.store;

import java.sql.ResultSet;
import java.sql.SQLException;

final class Rows {

    private Rows() {
    }

    static Double dbl(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    static Long lng(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
}
