package io.causelume;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

@Component
class Store {
    final JdbcTemplate db;
    private final org.springframework.transaction.support.TransactionTemplate nested;
    Store(JdbcTemplate db) {
        this.db = db;
        nested = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.support.JdbcTransactionManager(db.getDataSource()));
        nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
    }

    long insert(String sql, Object... args) {
        var keys = new GeneratedKeyHolder();
        db.update(connection -> {
            String id = connection.getMetaData().storesUpperCaseIdentifiers() ? "ID" : "id";
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{id});
            for (int i = 0; i < args.length; i++)
                ps.setObject(i + 1, args[i] instanceof Instant time ? time.atOffset(ZoneOffset.UTC) : args[i]);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    // PostgreSQL aborts the current transaction on a uniqueness violation. Callers
    // that inspect the original event after a duplicate need an actual savepoint.
    long insertIdempotent(String sql,Object... args) { return nested.execute(status -> insert(sql,args)); }

    List<Map<String,Object>> rows(String sql, Object... args) {
        return db.query(sql, (rs, row) -> {
            Map<String,Object> result = new LinkedHashMap<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                String[] parts = rs.getMetaData().getColumnLabel(i).toLowerCase(java.util.Locale.ROOT).split("_");
                StringBuilder key = new StringBuilder(parts[0]);
                for (int j = 1; j < parts.length; j++)
                    key.append(Character.toUpperCase(parts[j].charAt(0))).append(parts[j].substring(1));
                String type = rs.getMetaData().getColumnTypeName(i);
                Object value = rs.getObject(i);
                if (value != null && (type.equalsIgnoreCase("timestamptz") || type.equalsIgnoreCase("timestamp with time zone")))
                    value = rs.getObject(i,OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC);
                result.put(key.toString(), value);
            }
            return result;
        }, args);
    }

    Map<String,Object> one(String sql, Object... args) {
        return rows(sql,args).stream().findFirst().orElseThrow(() -> new Problem(404,"记录不存在"));
    }

    void audit(String actor, String action, long id) {
        db.update("insert into audit(actor,action,target_id) values (?,?,?)",actor,action,id);
    }
}
