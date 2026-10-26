package com.face.api.model;

/**
 * 每次检测的人脸；不把未匹配的人脸误当成已知人员。
 */
public class VideoFaceResult {
    public FaceBox box;
    public String status;
    public RecognitionResult match;
    public long frame;
    public double timeSeconds;
    public String thumbnail;
    /**
     * Padded capture for manual enrollment, only for UNKNOWN faces.
     */
    public String enrollmentImage;
    // 实时流程的连续身份确认；上传视频原始逐帧结果不设置这两个字段。
    public Boolean confirmed;
    public String displayState;

    public VideoFaceResult() {
    }

    public VideoFaceResult(FaceBox box, String status, RecognitionResult match) {
        this.box = box;
        this.status = status;
        this.match = match;
    }
}
