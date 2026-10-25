package com.face.api.repository;

import com.face.api.cluster.NodeLease;
import com.face.api.model.RecognitionHistory;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.*;

/** 共享关系库历史；不执行SQLite启动时的全库RUNNING中断逻辑。 */
public final class JdbcRecognitionHistoryRepository extends RecognitionHistoryRepository {
    private final JdbcDatabase db;
    private final ObjectMapper mapper;
    private final NodeLease lease;
    // 父类此构造器仅设置序列化器，不打开 SQLite，也不清理其他节点的任务。
    public JdbcRecognitionHistoryRepository(JdbcDatabase db,ObjectMapper mapper,NodeLease lease){super(mapper);this.db=db;this.mapper=mapper;this.lease=lease;}
    @Override public void save(RecognitionHistory record){
        if(record.id==null)record.id=UUID.randomUUID().toString();if(record.createdAt==null)record.createdAt=Instant.now().toString();
        final String payload;try{payload=mapper.writeValueAsString(record);}catch(Exception e){throw new IllegalStateException("历史序列化失败",e);}
        // 历史写入与租约检查共用事务，防止过期节点继续提交结果。
        db.transaction(()->{lease.requireWritable();db.jdbc.update("INSERT INTO recognition_history(id,time_ms,source,status,kind,task_id,task_state,task_error,name,code,source_name,person_role,node_id,payload) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",record.id,Instant.parse(record.createdAt).toEpochMilli(),record.source,record.status,record.kind,record.taskId,record.taskState,record.taskError,record.name,record.code,record.sourceName,record.role,lease.id(),payload);return null;});
    }
    // 仅结束本节点仍在运行的任务，避免覆盖租约回收已写入的中断状态。
    @Override public void finishVideo(String taskId,String state,String error,boolean noFaces){
        db.transaction(()->{lease.requireWritable();
            db.jdbc.update("UPDATE recognition_history SET task_state=?,task_error=? WHERE task_id=? AND node_id=? AND task_state='RUNNING'",state,error,taskId,lease.id());
            db.jdbc.update("UPDATE recognition_history SET status=? WHERE task_id=? AND node_id=? AND kind='TASK' AND task_state=?","FAILED".equals(state)?"ERROR":"CANCELLED".equals(state)?"CANCELLED":noFaces?"NO_FACE":"COMPLETED",taskId,lease.id(),state);return null;
        });
    }
    // 抓拍数据来自 payload，任务状态则以关系列中的最新值为准。
    private RecognitionHistory read(ResultSet rs)throws java.sql.SQLException{
        try{RecognitionHistory record=mapper.readValue(rs.getString("payload"),RecognitionHistory.class);record.status=rs.getString("status");record.taskState=rs.getString("task_state");record.taskError=rs.getString("task_error");return record;}
        catch(Exception e){throw new java.sql.SQLException("历史内容读取失败",e);}
    }
    @Override public RecognitionHistory get(String id){List<RecognitionHistory> found=db.jdbc.query("SELECT * FROM recognition_history WHERE id=?",(rs,n)->read(rs),id);if(found.isEmpty())throw new IllegalArgumentException("识别记录不存在");return found.get(0);}
    // 使用 CASE 聚合兼容 MySQL 和达梦，避免依赖布尔表达式求和。
    private Map<String,Object> summary(Filter f){return db.jdbc.queryForObject("SELECT COUNT(*),COALESCE(SUM(CASE WHEN status='MATCHED' THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN status IN ('UNKNOWN','TOO_SMALL','NO_FACE') THEN 1 ELSE 0 END),0),COALESCE(SUM(CASE WHEN status='ERROR' THEN 1 ELSE 0 END),0) FROM recognition_history"+f.relationalWhere(),(rs,n)->{Map<String,Object> out=new LinkedHashMap<>();out.put("total",rs.getLong(1));out.put("matched",rs.getLong(2));out.put("unmatched",rs.getLong(3));out.put("errors",rs.getLong(4));return out;},f.values.toArray());}
    // 达梦绑定 offset、size；MySQL 绑定 size、offset，顺序须与分页 SQL 对应。
    private List<RecognitionHistory> select(Filter f,int size,long offset){List<Object> args=new ArrayList<>(f.values);if("dm".equals(db.type)){args.add(offset);args.add(size);}else{args.add(size);args.add(offset);}return db.jdbc.query(db.pageSql("SELECT * FROM recognition_history"+f.relationalWhere()+" ORDER BY time_ms DESC,id DESC"),(rs,n)->read(rs),args.toArray());}
    @Override public Map<String,Object> page(Filter f,int page,int size){
        if(page<1||size<1||size>100)throw new IllegalArgumentException("历史分页参数无效");
        Map<String,Object> out=summary(f);long total=((Number)out.get("total")).longValue();int pages=(int)Math.max(1,(total+size-1)/size);page=Math.min(page,pages);out.put("page",page);out.put("pages",pages);out.put("items",select(f,size,(long)(page-1)*size));return out;
    }
    // 超限时明确报错，避免静默截断后被误认为已导出全部记录。
    @Override public List<RecognitionHistory> export(Filter f){if(((Number)summary(f).get("total")).longValue()>10000)throw new IllegalArgumentException("导出最多10000条，请缩小筛选范围");return select(f,10000,0);}
    @Override public void close(){/* 连接由共享DataSource池管理。 */}
}
