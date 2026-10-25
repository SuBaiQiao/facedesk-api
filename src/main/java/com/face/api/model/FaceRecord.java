package com.face.api.model;

/** 底库中的一条人脸记录。 */
public class FaceRecord {
    private String role;
    private String code;
    private String name;
    private String imageBase64;
    private float[] feature;
    // 同时校验模型内容和预处理版本；维数相同不代表向量可以相互比较。
    private String featureSpaceId;
    // 内存检索只加载参考向量，参考原图保存在独立表并按需读取。
    private java.util.List<float[]> referenceFeatures = new java.util.ArrayList<>();
    @com.fasterxml.jackson.annotation.JsonIgnore
    public java.util.List<float[]> getReferenceFeatures() { return referenceFeatures; }
    public void setReferenceFeatures(java.util.List<float[]> value) { referenceFeatures=value; }

    public String getFeatureSpaceId() { return featureSpaceId; }
    public void setFeatureSpaceId(String value) { featureSpaceId = value; }

    public FaceRecord() {
    }

    public FaceRecord(String role, String code, String name, String imageBase64, float[] feature) {
        this.role = role;
        this.code = code;
        this.name = name;
        this.imageBase64 = imageBase64;
        this.feature = feature;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getImageBase64() {
        return imageBase64;
    }

    public void setImageBase64(String imageBase64) {
        this.imageBase64 = imageBase64;
    }

    public float[] getFeature() {
        return feature;
    }

    public void setFeature(float[] feature) {
        this.feature = feature;
    }
}
