package com.face.api.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

/** 档案使用数据库既有 role/code 联合主键，所有定位更新必须同时限定两列。 */
@TableName("face_records")
public class FaceEntity {
    private String role;
    private String code;
    private String name;
    private String imageBase64;
    private byte[] feature;
    private String featureSpaceId;
    private String createdAt;
    private String updatedAt;

    public String getRole() { return role; }
    public void setRole(String value) { this.role = value; }
    public String getCode() { return code; }
    public void setCode(String value) { this.code = value; }
    public String getName() { return name; }
    public void setName(String value) { this.name = value; }
    public String getImageBase64() { return imageBase64; }
    public void setImageBase64(String value) { this.imageBase64 = value; }
    public byte[] getFeature() { return feature; }
    public void setFeature(byte[] value) { this.feature = value; }
    public String getFeatureSpaceId() { return featureSpaceId; }
    public void setFeatureSpaceId(String value) { this.featureSpaceId = value; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String value) { this.createdAt = value; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String value) { this.updatedAt = value; }
}
