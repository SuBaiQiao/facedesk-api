package com.face.api.engine;

import com.face.api.model.FaceBox;

import java.util.List;

/** 离线人脸引擎接口。 */
public interface FaceEngine extends AutoCloseable {
    /** 初始化模型和本地资源。 */
    void initialize();

    /** 返回引擎是否已成功初始化。 */
    boolean isAvailable();

    /** 返回引擎名称，用于状态接口。 */
    String engineName();

    /** 包含模型内容指纹和预处理版本，用于阻止不同特征空间混用。 */
    String featureSpaceId();

    /** 必须仅有一张足够清晰的人脸，检测和特征提取使用同一结果。 */
    FaceSample analyze(byte[] imageBytes);

    /** 多人检测与特征提取；过小人脸的 feature 为 null。 */
    default List<FaceSample> analyzeAll(byte[] imageBytes) {
        throw new FaceEngineException("当前引擎不支持多人识别");
    }

    /** 从图片中提取单张人脸特征。 */
    float[] extractFeature(byte[] imageBytes);

    /** 检测图片中的人脸框。 */
    List<FaceBox> detectFaces(byte[] imageBytes);

    /** 计算两个人脸特征的相似度。 */
    double compare(float[] left, float[] right);

    /** 当前引擎是否支持活体检测。 */
    default boolean supportsLiveness() {
        return false;
    }

    /** 执行活体检测；不支持时由默认实现返回 false。 */
    default boolean liveness(byte[] imageBytes) {
        return false;
    }

    @Override
    default void close() {
        // 默认引擎没有需要释放的资源。
    }
}
