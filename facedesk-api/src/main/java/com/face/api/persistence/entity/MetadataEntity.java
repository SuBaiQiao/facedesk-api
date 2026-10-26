package com.face.api.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

/** 全库单行元数据，通过行锁串行化模型校验和档案版本变更。 */
@TableName("face_metadata")
public class MetadataEntity {
    @TableId(value="id", type=IdType.INPUT)
    private Integer id;
    private Integer schemaVersion;
    private String featureSpaceId;
    private Long revision;

    public Integer getId() { return id; }
    public void setId(Integer value) { this.id = value; }
    public Integer getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(Integer value) { this.schemaVersion = value; }
    public String getFeatureSpaceId() { return featureSpaceId; }
    public void setFeatureSpaceId(String value) { this.featureSpaceId = value; }
    public Long getRevision() { return revision; }
    public void setRevision(Long value) { this.revision = value; }
}
