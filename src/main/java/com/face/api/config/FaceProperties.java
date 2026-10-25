package com.face.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import javax.validation.constraints.*;

/** 所有模型均从本地加载，不下载、不激活。 */
@Validated
@ConfigurationProperties(prefix = "face")
public class FaceProperties {
    @Pattern(regexp="sface|auraface") private String recognitionBackend = "sface";
    public String getRecognitionBackend() { return recognitionBackend; }
    public void setRecognitionBackend(String value) { recognitionBackend = value; }
    @NotBlank private String modelDir = "./models";
    @NotBlank private String detectorModel = "face_detection_yunet_2023mar.onnx";
    @NotBlank private String recognizerModel = "face_recognition_sface_2021dec.onnx";
    @NotBlank private String storagePath = "./data/face.db";
    private String importJson = "";
    @DecimalMin("0.0") @DecimalMax("1.0") private double threshold = 0.55;
    @DecimalMin("0.0") @DecimalMax("1.0") private double minMargin = 0.08;
    @DecimalMin("0.1") @DecimalMax("1.0") private float detectionThreshold = 0.9f;
    @Min(32) private int minFaceSize = 80;
    @Min(320) @Max(1280) private int detectionSize = 640;
    // OpenCV内部CPU并行线程数；workers是独立模型实例数，两者共同影响吞吐与内存。
    @Min(1) @Max(16) private int inferenceThreads = 1;
    public int getInferenceThreads() { return inferenceThreads; }
    public void setInferenceThreads(int value) { inferenceThreads = value; }
    @Min(1) @Max(64) private int workers = 4;
    @Min(1) @Max(1000) private int maxInFlight = 250;
    @Min(100) private long requestTimeoutMs = 60000;
    @Min(1) private int maxImagePixels = 16000000;
    public String getModelDir() { return modelDir; }
    public void setModelDir(String v) { modelDir = v; }
    public String getDetectorModel() { return detectorModel; }
    public void setDetectorModel(String v) { detectorModel = v; }
    public String getRecognizerModel() { return recognizerModel; }
    public void setRecognizerModel(String v) { recognizerModel = v; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String v) { storagePath = v; }
    public String getImportJson() { return importJson; }
    public void setImportJson(String v) { importJson = v; }
    public double getThreshold() { return threshold; }
    public void setThreshold(double v) { threshold = v; }
    public double getMinMargin() { return minMargin; }
    public void setMinMargin(double v) { minMargin = v; }
    public float getDetectionThreshold() { return detectionThreshold; }
    public void setDetectionThreshold(float v) { detectionThreshold = v; }
    public int getMinFaceSize() { return minFaceSize; }
    public void setMinFaceSize(int v) { minFaceSize = v; }
    public int getDetectionSize() { return detectionSize; }
    public void setDetectionSize(int v) { detectionSize = v; }
    public int getWorkers() { return workers; }
    public void setWorkers(int v) { workers = v; }
    public int getMaxInFlight() { return maxInFlight; }
    public void setMaxInFlight(int v) { maxInFlight = v; }
    public long getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(long v) { requestTimeoutMs = v; }
    public int getMaxImagePixels() { return maxImagePixels; }
    public void setMaxImagePixels(int v) { maxImagePixels = v; }
    private String ffmpegPath = "ffmpeg";
    public String getFfmpegPath() { return ffmpegPath; }
    public void setFfmpegPath(String value) { ffmpegPath = value; }
}
