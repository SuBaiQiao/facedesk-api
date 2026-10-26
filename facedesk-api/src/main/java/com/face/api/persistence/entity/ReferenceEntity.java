package com.face.api.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

/** 参考照片单独存储，检索快照只加载特征，不加载大图。 */
@TableName("face_reference_samples")
public class ReferenceEntity {
    @TableId(value="id", type=IdType.INPUT)
    private String id;
    private String role;
    private String code;
    private String image;
    private byte[] feature;
    private String createdAt;

    public String getId() { return id; }
    public void setId(String value) { this.id = value; }
    public String getRole() { return role; }
    public void setRole(String value) { this.role = value; }
    public String getCode() { return code; }
    public void setCode(String value) { this.code = value; }
    public String getImage() { return image; }
    public void setImage(String value) { this.image = value; }
    public byte[] getFeature() { return feature; }
    public void setFeature(byte[] value) { this.feature = value; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String value) { this.createdAt = value; }
}
