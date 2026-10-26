package com.face.api.repository;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.face.api.cluster.NodeLease;
import com.face.api.model.RecognitionHistory;
import com.face.api.persistence.entity.HistoryEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** 共享历史使用 MyBatis-Plus，节点租约和终态更新仍在同一 Spring 事务中。 */
public final class MybatisPlusHistoryRepository extends RecognitionHistoryRepository {
    private final MybatisPlusDatabase db;
    private final ObjectMapper mapper;
    private final NodeLease lease;

    // 父类此构造器不打开 SQLite，也不清理其他实例的运行任务。
    public MybatisPlusHistoryRepository(MybatisPlusDatabase db, ObjectMapper mapper, NodeLease lease) {
        super(mapper); this.db = db; this.mapper = mapper; this.lease = lease;
    }

    @Override public void save(RecognitionHistory record) {
        if (record.id == null) record.id = UUID.randomUUID().toString();
        if (record.createdAt == null) record.createdAt = Instant.now().toString();
        HistoryEntity row = new HistoryEntity();
        row.setId(record.id); row.setTimeMs(Instant.parse(record.createdAt).toEpochMilli());
        row.setSource(record.source); row.setStatus(record.status); row.setKind(record.kind);
        row.setTaskId(record.taskId); row.setTaskState(record.taskState); row.setTaskError(record.taskError);
        row.setName(record.name); row.setCode(record.code); row.setSourceName(record.sourceName);
        row.setPersonRole(record.role); row.setNodeId(lease.id());
        try { row.setPayload(mapper.writeValueAsString(record)); }
        catch (Exception failure) { throw new IllegalStateException("历史序列化失败", failure); }
        db.transaction(() -> { lease.requireWritable(); db.history.insert(row); return null; });
    }

    @Override public void finishVideo(String taskId, String state, String error, boolean noFaces) {
        db.transaction(() -> {
            lease.requireWritable();
            // 显式 set 允许将旧错误清空；仅更新本节点仍在运行的任务。
            db.history.update(null, new UpdateWrapper<HistoryEntity>().eq("task_id", taskId)
                    .eq("node_id", lease.id()).eq("task_state", "RUNNING")
                    .set("task_state", state).set("task_error", error));
            db.history.update(null, new UpdateWrapper<HistoryEntity>().eq("task_id", taskId)
                    .eq("node_id", lease.id()).eq("kind", "TASK").eq("task_state", state)
                    .set("status", "FAILED".equals(state) ? "ERROR" : "CANCELLED".equals(state) ? "CANCELLED" : noFaces ? "NO_FACE" : "COMPLETED"));
            return null;
        });
    }

    // payload 保留抓拍现场，后续任务结束/中断状态以独立列的最新值为准。
    private RecognitionHistory read(HistoryEntity row) {
        try {
            RecognitionHistory record = mapper.readValue(row.getPayload(), RecognitionHistory.class);
            record.status = row.getStatus(); record.taskState = row.getTaskState(); record.taskError = row.getTaskError();
            return record;
        } catch (Exception failure) { throw new IllegalStateException("历史内容读取失败", failure); }
    }

    @Override public RecognitionHistory get(String id) {
        HistoryEntity row = db.history.selectById(id);
        if (row == null) throw new IllegalArgumentException("识别记录不存在");
        return read(row);
    }

    private Map<String, Object> summary(Filter filter) {
        // 固定聚合表达式兼容 MySQL/达梦，不将用户输入作为 SQL 片段拼接。
        List<Map<String, Object>> rows = db.history.selectMaps(filter.mybatisCondition().select(
                "COUNT(*) AS total", "COALESCE(SUM(CASE WHEN status='MATCHED' THEN 1 ELSE 0 END),0) AS matched",
                "COALESCE(SUM(CASE WHEN status IN ('UNKNOWN','TOO_SMALL','NO_FACE') THEN 1 ELSE 0 END),0) AS unmatched",
                "COALESCE(SUM(CASE WHEN status='ERROR' THEN 1 ELSE 0 END),0) AS errors"));
        Map<String, Object> out = new LinkedHashMap<>();
        // 达梦可能将聚合别名转成大写，统一输出接口既有的小写键和 Long 类型。
        for (String key : Arrays.asList("total", "matched", "unmatched", "errors")) {
            Number value = (Number) rows.get(0).entrySet().stream().filter(e -> key.equalsIgnoreCase(e.getKey()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("历史统计列缺失")).getValue();
            out.put(key, value.longValue());
        }
        return out;
    }

    private List<RecognitionHistory> select(Filter filter, int page, int size) {
        // 方言分页由插件生成，不再手工维护 LIMIT/OFFSET 的占位符顺序。
        Page<HistoryEntity> request = new Page<>(page, size, false);
        return db.history.selectPage(request, filter.mybatisCondition().orderByDesc("time_ms", "id"))
                .getRecords().stream().map(this::read).collect(Collectors.toList());
    }

    @Override public Map<String, Object> page(Filter filter, int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("历史分页参数无效");
        Map<String, Object> out = summary(filter);
        long total = ((Number) out.get("total")).longValue();
        int pages = (int) Math.max(1, (total + size - 1) / size);
        page = Math.min(page, pages);
        out.put("page", page); out.put("pages", pages); out.put("items", select(filter, page, size));
        return out;
    }

    @Override public List<RecognitionHistory> export(Filter filter) {
        if (((Number) summary(filter).get("total")).longValue() > 10000)
            throw new IllegalArgumentException("导出最多10000条，请缩小筛选范围");
        return select(filter, 1, 10000);
    }

    @Override public void close() { /* 连接生命周期由共享 DataSource 管理。 */ }
}
