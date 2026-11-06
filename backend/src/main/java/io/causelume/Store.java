package io.causelume;

import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

@Component
class Store {
    final JdbcTemplate db;
    Store(JdbcTemplate db) { this.db = db; }

    long insert(String sql, Object... args) {
        var keys = new GeneratedKeyHolder();
        db.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"ID"});
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    List<Map<String,Object>> rows(String sql, Object... args) {
        return db.query(sql, (rs, row) -> {
            Map<String,Object> result = new LinkedHashMap<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                String[] parts = rs.getMetaData().getColumnLabel(i).toLowerCase(java.util.Locale.ROOT).split("_");
                StringBuilder key = new StringBuilder(parts[0]);
                for (int j = 1; j < parts.length; j++)
                    key.append(Character.toUpperCase(parts[j].charAt(0))).append(parts[j].substring(1));
                result.put(key.toString(), rs.getObject(i));
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
