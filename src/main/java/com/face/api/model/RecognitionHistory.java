package com.face.api.model;

/** 历史保存识别时的人员快照，不随底库改名或删除而变化。 */
public class RecognitionHistory {
    public String id, createdAt, source, sourceName, kind = "FACE", taskId, taskState, taskError;
    public String status, role, code, name, requestedRole, error, thumbnail, featureSpaceId;
    public Double score, threshold;
    public FaceBox box;
    public Long frame, durationMs;
    public Double timeSeconds;
}
