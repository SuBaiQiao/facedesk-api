package com.face.api.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

/** 每次进程启动使用独立节点令牌，心跳作为历史写入的租约凭据。 */
@TableName("facedesk_nodes")
public class NodeEntity {
    @TableId(value="node_id", type=IdType.INPUT)
    private String nodeId;
    private Long heartbeatMs;
    private String featureSpaceId;

    public String getNodeId() { return nodeId; }
    public void setNodeId(String value) { this.nodeId = value; }
    public Long getHeartbeatMs() { return heartbeatMs; }
    public void setHeartbeatMs(Long value) { this.heartbeatMs = value; }
    public String getFeatureSpaceId() { return featureSpaceId; }
    public void setFeatureSpaceId(String value) { this.featureSpaceId = value; }
}
