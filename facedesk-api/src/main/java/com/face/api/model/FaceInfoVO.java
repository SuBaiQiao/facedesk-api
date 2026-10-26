package com.face.api.model;

/**
 * 角色人脸列表返回对象。
 */
public class FaceInfoVO {
    private String name;
    private String faceRole;
    private String code;
    private String faceImage;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFaceRole() {
        return faceRole;
    }

    public void setFaceRole(String faceRole) {
        this.faceRole = faceRole;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getFaceImage() {
        return faceImage;
    }

    public void setFaceImage(String faceImage) {
        this.faceImage = faceImage;
    }
}
