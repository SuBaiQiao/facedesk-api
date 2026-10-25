package com.face.api.repository;

import com.face.api.model.RecognitionHistory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** 与人员档案共用 SQLite 文件；独立连接、参数化查询，保留完整识别快照。 */
public class RecognitionHistoryRepository implements AutoCloseable {
    private Connection db;
    private final ObjectMapper json;
    public RecognitionHistoryRepository(String path, ObjectMapper json) {
        this.json=json;
        try {
            Path file=Paths.get(path).toAbsolutePath().normalize(); Files.createDirectories(file.getParent());
            db=DriverManager.getConnection("jdbc:sqlite:"+file);
            try(Statement s=db.createStatement()) {
                s.execute("PRAGMA busy_timeout=5000"); s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA synchronous=FULL");
                s.execute("CREATE TABLE IF NOT EXISTS recognition_history (id TEXT PRIMARY KEY,time_ms INTEGER NOT NULL,source TEXT NOT NULL,status TEXT NOT NULL,kind TEXT NOT NULL,task_id TEXT,task_state TEXT,task_error TEXT,name TEXT,code TEXT,source_name TEXT,payload TEXT NOT NULL)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_history_time ON recognition_history(time_ms DESC,id DESC)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_history_task ON recognition_history(task_id)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_history_filter ON recognition_history(source,status,time_ms DESC)");
                // 服务重启后原任务无法继续，将遗留RUNNING标为中断；已记录的人脸判定保持原样。
                s.executeUpdate("UPDATE recognition_history SET status=CASE WHEN kind='TASK' THEN 'ERROR' ELSE status END,task_state='INTERRUPTED',task_error='服务重启，采集或视频任务未完成' WHERE task_state='RUNNING'");
            }
        } catch(Exception e) { if(db!=null)try{db.close();}catch(SQLException ignored){} throw new IllegalStateException("初始化识别历史失败",e); }
    }
    public synchronized void save(RecognitionHistory record) {
        if(record.id==null)record.id=UUID.randomUUID().toString();
        if(record.createdAt==null)record.createdAt=Instant.now().toString();
        try(PreparedStatement s=db.prepareStatement("INSERT INTO recognition_history VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setString(1,record.id);s.setLong(2,Instant.parse(record.createdAt).toEpochMilli());s.setString(3,record.source);s.setString(4,record.status);s.setString(5,record.kind);
            s.setString(6,record.taskId);s.setString(7,record.taskState);s.setString(8,record.taskError);s.setString(9,record.name);s.setString(10,record.code);s.setString(11,record.sourceName);
            s.setString(12,json.writeValueAsString(record));s.executeUpdate();
        } catch(Exception e) { throw new IllegalStateException("识别历史保存失败，请检查数据库磁盘空间",e); }
    }
    // 视频任务和摄像头会话共用终态更新；状态字段单独存列，以事务更新全部关联记录。
    public synchronized void finishVideo(String taskId,String state,String error,boolean noFaces) {
        try {
            db.setAutoCommit(false);
            try(PreparedStatement s=db.prepareStatement("UPDATE recognition_history SET task_state=?,task_error=? WHERE task_id=?")) {
                s.setString(1,state);s.setString(2,error);s.setString(3,taskId);s.executeUpdate();
            }
            try(PreparedStatement s=db.prepareStatement("UPDATE recognition_history SET status=? WHERE task_id=? AND kind='TASK'")) {
                s.setString(1,"FAILED".equals(state)?"ERROR":"CANCELLED".equals(state)?"CANCELLED":noFaces?"NO_FACE":"COMPLETED");s.setString(2,taskId);s.executeUpdate();
            }
            db.commit();
        } catch(SQLException e) {
            try { db.rollback(); } catch(SQLException ignored) { }
            throw new IllegalStateException("视频识别历史终态保存失败",e);
        } finally { try { db.setAutoCommit(true); } catch(SQLException ignored) { } }
    }
    // payload保留识别现场快照，任务终态以独立列为准；读取时覆盖以反映后续结束/中断。
    private RecognitionHistory read(ResultSet rows) throws Exception {
        RecognitionHistory r=json.readValue(rows.getString("payload"),RecognitionHistory.class);
        r.status=rows.getString("status");r.taskState=rows.getString("task_state");r.taskError=rows.getString("task_error");return r;
    }
    public synchronized RecognitionHistory get(String id) {
        try(PreparedStatement s=db.prepareStatement("SELECT * FROM recognition_history WHERE id=?")) {
            s.setString(1,id);try(ResultSet rows=s.executeQuery()){if(rows.next())return read(rows);}
        } catch(Exception e) { throw new IllegalStateException("读取识别历史失败",e); }
        throw new IllegalArgumentException("识别记录不存在");
    }
    public synchronized Map<String,Object> page(Filter filter,int page,int size) {
        if(page<1 || size<1 || size>100)throw new IllegalArgumentException("历史分页参数无效");
        try {
            Map<String,Object> result=summary(filter); long total=((Number)result.get("total")).longValue();
            int pages=(int)Math.max(1,(total+size-1)/size);page=Math.min(page,pages);
            result.put("page",page);result.put("pages",pages);result.put("items",select(filter,size,(long)(page-1)*size));return result;
        } catch(Exception e) { throw new IllegalStateException("查询识别历史失败",e); }
    }
    private Map<String,Object> summary(Filter filter) throws SQLException {
        try(PreparedStatement s=db.prepareStatement("SELECT COUNT(*),COALESCE(SUM(status='MATCHED'),0),COALESCE(SUM(status IN ('UNKNOWN','TOO_SMALL','NO_FACE')),0),COALESCE(SUM(status='ERROR'),0) FROM recognition_history"+filter.where)) {
            filter.bind(s);try(ResultSet rows=s.executeQuery()) {
                rows.next();Map<String,Object> m=new LinkedHashMap<>();m.put("total",rows.getLong(1));m.put("matched",rows.getLong(2));m.put("unmatched",rows.getLong(3));m.put("errors",rows.getLong(4));return m;
            }
        }
    }
    private List<RecognitionHistory> select(Filter filter,int size,long offset) throws Exception {
        try(PreparedStatement s=db.prepareStatement("SELECT * FROM recognition_history"+filter.where+" ORDER BY time_ms DESC,id DESC LIMIT ? OFFSET ?")) {
            int n=filter.bind(s);s.setInt(n,size);s.setLong(n+1,offset);
            List<RecognitionHistory> list=new ArrayList<>();try(ResultSet rows=s.executeQuery()){while(rows.next())list.add(read(rows));}return list;
        }
    }
    // 显式限制导出条数，超过上限要求缩小范围，不静默截断导致用户误以为数据完整。
    public synchronized List<RecognitionHistory> export(Filter filter) {
        try {
            if(((Number)summary(filter).get("total")).longValue()>10000)throw new IllegalArgumentException("导出最多 10000 条，请缩小筛选范围");
            return select(filter,10000,0);
        } catch(IllegalArgumentException e) { throw e; } catch(Exception e) { throw new IllegalStateException("导出识别历史失败",e); }
    }
    @Override public synchronized void close() { try{db.close();}catch(SQLException ignored){} }
    // 筛选值统一绑定参数；姓名检索转义LIKE通配符，人员详情按role/code精确关联。
    public static final class Filter {
        final String where; final List<Object> values=new ArrayList<>();
        public Filter(String source,String status,String q,String from,String to,String taskId) {
            this(source,status,q,from,to,taskId,null,null);
        }
        public static Filter person(String role,String code){return new Filter("","","","","","",role,code);}
        private Filter(String source,String status,String q,String from,String to,String taskId,String role,String code) {
            StringBuilder sql=new StringBuilder(" WHERE 1=1");
            if(source!=null&&!source.isEmpty()){if(!Arrays.asList("IMAGE","VIDEO","USB","RTSP").contains(source))throw new IllegalArgumentException("无效的识别来源");sql.append(" AND source=?");values.add(source);}
            if(status!=null&&!status.isEmpty()){if(!Arrays.asList("MATCHED","UNKNOWN","TOO_SMALL","NO_FACE","ERROR","CANCELLED","COMPLETED","PROCESSING").contains(status))throw new IllegalArgumentException("无效的识别状态");sql.append(" AND status=?");values.add(status);}
            if(q!=null&&!q.trim().isEmpty()){if(q.length()>128)throw new IllegalArgumentException("搜索内容最多 128 字符");sql.append(" AND (name LIKE ? ESCAPE '\\' OR code LIKE ? ESCAPE '\\' OR source_name LIKE ? ESCAPE '\\')");String term="%"+q.trim().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";values.add(term);values.add(term);values.add(term);}
            Long start=parse(from),end=parse(to);if(start!=null&&end!=null&&start>=end)throw new IllegalArgumentException("开始时间必须早于结束时间");
            if(start!=null){sql.append(" AND time_ms>=?");values.add(start);}if(end!=null){sql.append(" AND time_ms<?");values.add(end);}
            if(taskId!=null&&!taskId.isEmpty()){if(taskId.length()>128)throw new IllegalArgumentException("无效的视频任务 ID");sql.append(" AND task_id=?");values.add(taskId);}
            if(role!=null){sql.append(" AND code=? AND json_extract(payload,'$.role')=?");values.add(code);values.add(role);}
            where=sql.toString();
        }
        private static Long parse(String value){try{return value==null||value.isEmpty()?null:Instant.parse(value).toEpochMilli();}catch(RuntimeException e){throw new IllegalArgumentException("时间参数必须是含时区的 ISO 时间");}}
        int bind(PreparedStatement s)throws SQLException{int i=1;for(Object value:values)s.setObject(i++,value);return i;}
    }
}
