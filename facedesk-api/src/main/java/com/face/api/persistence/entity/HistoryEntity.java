package com.face.api.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

/** 识别现场快照保存为 JSON，筛选及可变任务状态使用独立列。 */
@TableName("recognition_history")
public class HistoryEntity {
    @TableId(value="id", type=IdType.INPUT)
    private String id;
    private Long timeMs;
    private String source;
    private String status;
    private String kind;
    private String taskId;
    private String taskState;
    private String taskError;
    private String name;
    private String code;
    private String sourceName;
    private String personRole;
    private String nodeId;
    private String payload;

    public String getId() { return id; }
    public void setId(String value) { this.id = value; }
    public Long getTimeMs() { return timeMs; }
    public void setTimeMs(Long value) { this.timeMs = value; }
    public String getSource() { return source; }
    public void setSource(String value) { this.source = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public String getKind() { return kind; }
    public void setKind(String value) { this.kind = value; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String value) { this.taskId = value; }
    public String getTaskState() { return taskState; }
    public void setTaskState(String value) { this.taskState = value; }
    public String getTaskError() { return taskError; }
    public void setTaskError(String value) { this.taskError = value; }
    public String getName() { return name; }
    public void setName(String value) { this.name = value; }
    public String getCode() { return code; }
    public void setCode(String value) { this.code = value; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String value) { this.sourceName = value; }
    public String getPersonRole() { return personRole; }
    public void setPersonRole(String value) { this.personRole = value; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String value) { this.nodeId = value; }
    public String getPayload() { return payload; }
    public void setPayload(String value) { this.payload = value; }
}
