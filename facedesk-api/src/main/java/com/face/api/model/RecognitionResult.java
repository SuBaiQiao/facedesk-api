package com.face.api.model;

/**
 * 1:N 识别结果。
 */
public class RecognitionResult {
    private String faceRole;
    private String code;
    private String name;
    private double score;
    private String rect;

    public RecognitionResult() {
    }

    public RecognitionResult(String faceRole, String code, String name, double score, FaceBox box) {
        this.faceRole = faceRole;
        this.code = code;
        this.name = name;
        this.score = score;
        this.rect = box == null ? null : box.toString();
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public String getRect() {
        return rect;
    }

    public void setRect(String rect) {
        this.rect = rect;
    }
}
