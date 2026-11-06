package io.causelume;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Offline administrator entry points. No web listener, worker or bootstrap account initializer is started. */
class OfflineStorage {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z_0-9]{0,62}");
    private record Table(String name,List<String> columns,List<String> keys,Set<String> parents,String identity) {}
    private record Check(long count,String sha256) {}

    static void run(String[] args) throws Exception {
        if (args.length==4 && args[1].equals("export-h2")) {
            Path base=Path.of(args[2]).toAbsolutePath().normalize();
            if(base.toString().contains(";") || !Files.isRegularFile(Path.of(base+".mv.db")))
                throw new IllegalArgumentException("需要已存在的 H2 数据库基路径");
            String user=secret("APP_SOURCE_DB_USER"), password=secret("APP_SOURCE_DB_PASSWORD");
            try(var db=DriverManager.getConnection("jdbc:h2:file:"+base+";IFEXISTS=TRUE;ACCESS_MODE_DATA=r",
                    user==null?"":user,password==null?"":password)) {
                export(db,Path.of(args[3]));
            }
        } else if (args.length==2 && args[1].equals("migrate-pg")) {
            var pg=postgres(true);
            Flyway.configure().dataSource(pg)
                    .locations("classpath:db/postgresql", "classpath:db/common").load().migrate();
            // The schema owner's default grants also apply to Flyway's own history on first install.
            try(var db=pg.getConnection();var sql=db.createStatement()) {
                sql.execute("revoke insert,update,delete on public.flyway_schema_history from causelume_app");
            }
        } else if (args.length==3 && args[1].equals("import-pg")) {
            var pg=postgres(true);
            load(pg,Path.of(args[2]),secret("APP_DB_USER"));
        } else if (args.length==3 && args[1].equals("recover-admin")) {
            String password=secret("APP_RECOVERY_PASSWORD");
            if(password==null) {
                var console=System.console();
                if(console==null) throw new IllegalArgumentException("交互式控制台或 APP_RECOVERY_PASSWORD 为必需");
                char[] input=console.readPassword("New administrator password: ");
                if(input==null) throw new IllegalArgumentException("没有读取到新密码");
                password=new String(input);
                Arrays.fill(input,'\0');
            }
            try(var db=postgres(false).getConnection()) { recoverAdmin(db,args[2],password); }
        } else throw new IllegalArgumentException("storage export-h2 <base> <new.jsonl> | migrate-pg | import-pg <file.jsonl> | recover-admin <username>");
        System.out.println("Offline storage operation completed.");
    }
    private static String secret(String name) throws IOException {
        String value=System.getenv(name);
        if(value!=null && !value.isEmpty()) return value;
        String override=System.getenv(name+"_FILE");
        if(override!=null && !override.isBlank()) return Files.readString(Path.of(override)).stripTrailing();
        Path file=Path.of("/run/secrets",name);
        return Files.isRegularFile(file) ? Files.readString(file).stripTrailing() : null;
    }
    private static DriverManagerDataSource postgres(boolean migration) throws IOException {
        String url=secret("APP_DB_URL");
        if(url==null || !url.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("需要 PostgreSQL APP_DB_URL");
        return new DriverManagerDataSource(url,migration ? "causelume_migrator" : Objects.requireNonNull(secret("APP_DB_USER"),"APP_DB_USER required"),
                Objects.requireNonNull(secret(migration ? "APP_MIGRATOR_PASSWORD" : "APP_DB_PASSWORD"),"database credential required"));
    }
    static void recoverAdmin(Connection db,String name,String password) throws SQLException {
        Accounts.validatePassword(password);
        db.setAutoCommit(false);
        long id;
        try(var select=db.prepareStatement("select id from accounts where username=? and role='ADMIN' for update")) {
            select.setString(1,name);
            try(var row=select.executeQuery()) {
                if(!row.next()) throw new IllegalArgumentException("未找到指定管理员");
                id=row.getLong(1);
            }
        }
        try(var reset=db.prepareStatement("update accounts set password_hash=?,active=true,mfa_seed=null,last_totp_step=-1,auth_version=auth_version+1 where id=?");
            var clear=db.prepareStatement("delete from recovery_codes where account_id=?");
            var audit=db.prepareStatement("insert into audit(actor,action,target_id) values ('local-cli','RECOVER_ADMIN',?)")) {
            reset.setString(1,new BCryptPasswordEncoder().encode(password));
            reset.setLong(2,id);
            reset.executeUpdate();
            clear.setLong(1,id);
            clear.executeUpdate();
            audit.setLong(1,id);
            audit.executeUpdate();
        }
        db.commit();
    }

    static void export(Connection db,Path file) throws Exception {
        db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        db.setAutoCommit(false);
        int version;
        try(var sql=db.createStatement();var row=sql.executeQuery("select \"version\" from \"flyway_schema_history\" where \"success\"=true order by \"installed_rank\" desc fetch first 1 row only")) {
            if(!row.next() || (version=Integer.parseInt(row.getString(1)))<5) throw new IllegalStateException("需先升级至 H2 V5");
        }
        var tables=describe(db,"PUBLIC");
        try(var out=newExportWriter(file)) {
            line(out,Map.of("format","causelume-jsonl-1","version",version));
            var pending=new LinkedHashMap<>(tables);
            var done=new HashSet<String>();
            while(!pending.isEmpty()) {
                var table=pending.values().stream().filter(t->done.containsAll(t.parents)).findFirst()
                        .orElseThrow(()->new IllegalStateException("发现无法按外键顺序导出的表"));
                Map<String,Object> header=new LinkedHashMap<>();
                header.put("table",table.name);
                header.put("columns",table.columns);
                if(table.identity!=null) {
                    try(var q=db.prepareStatement("select identity_base from information_schema.columns where table_schema='PUBLIC' and lower(table_name)=? and lower(column_name)=?")) {
                        q.setString(1,table.name); q.setString(2,table.identity);
                        try(var row=q.executeQuery()) { row.next(); header.put("nextId",row.getLong(1)); }
                    }
                }
                line(out,header);
                line(out,scan(db,table,out));
                pending.remove(table.name);
                done.add(table.name);
            }
            line(out,Map.of("complete",true));
        }
        db.commit();
    }
    private static BufferedWriter newExportWriter(Path file) throws IOException {
        if (!file.getFileSystem().supportedFileAttributeViews().contains("posix"))
            return Files.newBufferedWriter(file,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        var ownerOnly=PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
        var channel=Files.newByteChannel(file,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),ownerOnly);
        return new BufferedWriter(Channels.newWriter(channel,StandardCharsets.UTF_8));
    }
    private static void line(BufferedWriter out,Object value) throws IOException {
        out.write(JSON.writeValueAsString(value));
        out.write('\n');
    }
    private static String id(String identifier) {
        String name=identifier.toLowerCase(Locale.ROOT);
        if(!IDENTIFIER.matcher(name).matches()) throw new IllegalArgumentException("数据库标识符无效");
        return name;
    }
    private static Map<String,Table> describe(Connection db,String schema) throws SQLException {
        Map<String,Table> tables=new TreeMap<>();
        var meta=db.getMetaData();
        try(var list=meta.getTables(null,schema,"%",new String[]{"TABLE","BASE TABLE"})) {
            while(list.next()) {
                if(!schema.equals(list.getString("TABLE_SCHEM"))) continue;
                String physical=list.getString("TABLE_NAME");
                String name=id(physical);
                if(name.equals("flyway_schema_history")) continue;
                List<String> cols=new ArrayList<>();
                var keys=new TreeMap<Integer,String>();
                Set<String> parents=new HashSet<>();
                String identity=null;
                try(var fields=meta.getColumns(null,schema,physical,"%")) {
                    while(fields.next()) {
                        String col=id(fields.getString("COLUMN_NAME")); cols.add(col);
                        if("YES".equalsIgnoreCase(fields.getString("IS_AUTOINCREMENT"))) identity=col;
                    }
                }
                try(var pk=meta.getPrimaryKeys(null,schema,physical)) {
                    while(pk.next()) keys.put(pk.getInt("KEY_SEQ"),id(pk.getString("COLUMN_NAME")));
                }
                try(var fk=meta.getImportedKeys(null,schema,physical)) {
                    while(fk.next()) parents.add(id(fk.getString("PKTABLE_NAME")));
                }
                if(keys.isEmpty()) throw new IllegalArgumentException("缺少主键 "+name);
                tables.put(name,new Table(name,cols,List.copyOf(keys.values()),parents,identity));
            }
        }
        return tables;
    }
    private static Check scan(Connection db,Table table,BufferedWriter out) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        long count=0;
        String sql="select "+String.join(",",table.columns)+" from "+table.name+" order by "+String.join(",",table.keys);
        try(var q=db.createStatement()) {
            q.setFetchSize(256);
            try(var row=q.executeQuery(sql)) {
                var meta=row.getMetaData();
                while(row.next()) {
                    var values=new ArrayList<String>();
                    for(int i=1;i<=meta.getColumnCount();i++) {
                        String type=meta.getColumnTypeName(i).toLowerCase(Locale.ROOT);
                        String value=row.getString(i);
                        if(value!=null) value=switch(type) {
                            case "timestamptz","timestamp with time zone" -> row.getObject(i,OffsetDateTime.class).toInstant().toString();
                            case "bool","boolean" -> Boolean.toString(row.getBoolean(i));
                            case "numeric","decimal" -> row.getBigDecimal(i).stripTrailingZeros().toPlainString();
                            default -> value;
                        };
                        values.add(value);
                    }
                    String encoded=JSON.writeValueAsString(values)+"\n";
                    digest.update(encoded.getBytes(StandardCharsets.UTF_8));
                    if(out!=null) out.write(encoded);
                    count++;
                }
            }
        }
        return new Check(count,HexFormat.of().formatHex(digest.digest()));
    }

    private static Flyway flyway(DriverManagerDataSource pg,String schema,String target) {
        return Flyway.configure().dataSource(pg).locations("classpath:db/postgresql","classpath:db/common")
                .defaultSchema(schema).schemas(schema).target(target).load();
    }
    static void load(DriverManagerDataSource pg,Path file,String runtimeRole) throws Exception {
        String staging="causelume_import_"+UUID.randomUUID().toString().replace("-","");
        try(var in=Files.newBufferedReader(file,StandardCharsets.UTF_8);var db=pg.getConnection();var sql=db.createStatement()) {
            var header=next(in);
            if(!"causelume-jsonl-1".equals(header.path("format").asString("")) || !header.path("version").isIntegralNumber())
                throw new IllegalArgumentException("迁移文件头无效");
            int version=header.get("version").asInt();
            if(version<5) throw new IllegalArgumentException("仅接受 V5 及之后的数据库");
            requireEmptyTarget(db,staging);
            sql.execute("create schema "+staging);
            try {
                var initial=flyway(pg,staging,Integer.toString(version));
                initial.migrate();
                if(!Integer.toString(version).equals(initial.info().current().getVersion().toString()))
                    throw new IllegalArgumentException("此程序不支持来源数据库版本");
                db.setSchema(staging);
                db.setAutoCommit(false);
                var remaining=describe(db,staging);
                sql.execute("truncate table "+String.join(",",remaining.keySet()));
                for(JsonNode item=next(in);!item.path("complete").asBoolean(false);item=next(in)) {
                    var table=remaining.remove(item.path("table").asString(""));
                    if(table==null || !JSON.valueToTree(table.columns).equals(item.get("columns")))
                        throw new IllegalArgumentException("快照表或列与目标版本不一致");
                    readTable(in,db,table,item);
                }
                if(!remaining.isEmpty() || in.readLine()!=null) throw new IllegalArgumentException("快照不完整或尾部有额外数据");
                db.commit();
                flyway(pg,staging,"latest").migrate();
                // Only these newly created tables can be moved. Their owned sequences, constraints and indexes move with them.
                requireEmptyTarget(db,staging);
                var names=describe(db,staging).keySet();
                if(runtimeRole!=null) {
                    String role=id(runtimeRole);
                    sql.execute("grant select,insert,update,delete on "+String.join(",",names)+" to "+role);
                    sql.execute("grant usage,select on all sequences in schema "+staging+" to "+role);
                }
                for(String name:names) sql.execute("alter table "+staging+"."+name+" set schema public");
                sql.execute("alter table "+staging+".flyway_schema_history set schema public");
                sql.execute("drop schema "+staging);
                db.commit();
            } catch(Exception failure) {
                if(!db.getAutoCommit()) db.rollback();
                db.setAutoCommit(true);
                sql.execute("drop schema "+staging+" cascade");
                throw failure;
            }
        }
    }
    private static JsonNode next(BufferedReader in) throws IOException {
        String line=in.readLine();
        if(line==null) throw new EOFException("迁移文件未完整写入");
        return JSON.readTree(line);
    }
    private static void requireEmptyTarget(Connection db,String staging) throws SQLException {
        String check="""
                select exists(select 1 from pg_namespace where nspname not in ('public','information_schema',?)
                        and left(nspname,3)<>'pg_')
                    or exists(select 1 from pg_class where relnamespace='public'::regnamespace)
                    or exists(select 1 from pg_proc where pronamespace='public'::regnamespace)
                    or exists(select 1 from pg_type where typnamespace='public'::regnamespace)""";
        try(var sql=db.prepareStatement(check)) {
            sql.setString(1,staging);
            try(var row=sql.executeQuery()) {
            row.next();
            if(row.getBoolean(1)) throw new IllegalStateException("目标数据库已有业务对象，拒绝导入");
            }
        }
    }
    private static void readTable(BufferedReader in,Connection db,Table table,JsonNode header) throws Exception {
        String placeholders=String.join(",",Collections.nCopies(table.columns.size(),"?"));
        JsonNode footer;
        try(var insert=db.prepareStatement("insert into "+table.name+" ("+String.join(",",table.columns)+") values ("+placeholders+")")) {
            int batch=0;
            for(footer=next(in);footer.isArray();footer=next(in)) {
                if(footer.size()!=table.columns.size()) throw new IllegalArgumentException("快照行宽无效");
                for(int i=0;i<footer.size();i++) {
                    JsonNode value=footer.get(i);
                    if(!value.isNull() && !value.isString()) throw new IllegalArgumentException("快照列格式无效");
                    insert.setObject(i+1,value.isNull()?null:value.asString(),Types.OTHER);
                }
                insert.addBatch();
                if(++batch%256==0) insert.executeBatch();
            }
            insert.executeBatch();
        }
        var actual=scan(db,table,null);
        if(actual.count!=footer.path("count").asLong(-1) || !actual.sha256.equals(footer.path("sha256").asString("")))
            throw new IllegalArgumentException(table.name+" 行数或内容 SHA-256 不一致");
        if(table.identity!=null) {
            long next=header.path("nextId").asLong(-1);
            try(var sql=db.createStatement();var row=sql.executeQuery("select coalesce(max("+table.identity+"),0) from "+table.name)) {
                row.next();
                if(next<=row.getLong(1)) throw new IllegalArgumentException(table.name+" 序列边界不安全");
            }
            try(var sql=db.createStatement()) {
                sql.execute("alter table "+table.name+" alter column "+table.identity+" restart with "+next);
                try(var seq=sql.executeQuery("select pg_get_serial_sequence('"+table.name+"','"+table.identity+"')")) {
                    seq.next();
                    String sequence=seq.getString(1);
                    try(var check=db.createStatement();var state=check.executeQuery("select last_value,is_called from "+sequence)) {
                        state.next();
                        if(state.getLong(1)!=next || state.getBoolean(2)) throw new IllegalStateException("序列校验失败 "+table.name);
                    }
                }
            }
        }
    }
}
