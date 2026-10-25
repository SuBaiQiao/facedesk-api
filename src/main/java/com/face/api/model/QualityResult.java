package com.face.api.model;

/** 基础图片质量检查结果。 */
public class QualityResult {
    private boolean pass;
    private boolean eyesClosed;
    private boolean occlude;
    private boolean blurry;
    private int faceCount;
    private String message;

    public QualityResult() {
    }

    public QualityResult(boolean pass, int faceCount, String message) {
        this.pass = pass;
        this.faceCount = faceCount;
        this.message = message;
    }

    public boolean isPass() {
        return pass;
    }

    public void setPass(boolean pass) {
        this.pass = pass;
    }

    public boolean isEyesClosed() {
        return eyesClosed;
    }

    public void setEyesClosed(boolean eyesClosed) {
        this.eyesClosed = eyesClosed;
    }

    public boolean isOcclude() {
        return occlude;
    }

    public void setOcclude(boolean occlude) {
        this.occlude = occlude;
    }

    public boolean isBlurry() {
        return blurry;
    }

    public void setBlurry(boolean blurry) {
        this.blurry = blurry;
    }

    public int getFaceCount() {
        return faceCount;
    }

    public void setFaceCount(int faceCount) {
        this.faceCount = faceCount;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
