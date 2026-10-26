package com.face.api.service;

import com.face.api.config.FaceProperties;
import com.face.api.model.*;
import com.face.api.repository.RecognitionHistoryRepository;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.*;
import java.util.*;

@Service
public class RecognitionHistoryService {
    private final RecognitionHistoryRepository records;
    private final FaceProperties config;

    public RecognitionHistoryService(RecognitionHistoryRepository records, FaceProperties config) {
        this.records = records;
        this.config = config;
    }

    // 记录识别当时的姓名、编号、分数和照片；之后修改或删除档案不会改写这些现场信息。
    public void photo(byte[] image, String role, List<RecognitionResult> matches, FaceBox box, long elapsed, String model, double threshold, RuntimeException failure) {
        RecognitionHistory r = new RecognitionHistory();
        r.source = "IMAGE";
        r.sourceName = "照片识别";
        r.requestedRole = role;
        r.box = box;
        r.durationMs = elapsed;
        r.featureSpaceId = model;
        r.threshold = threshold;
        r.status = failure != null ? (failure.getMessage() != null && failure.getMessage().contains("未检测到人脸") ? "NO_FACE" : "ERROR") : matches.isEmpty() ? "UNKNOWN" : "MATCHED";
        r.error = failure == null ? null : failure.getMessage();
        r.taskState = failure == null ? "COMPLETED" : "FAILED";
        if (failure == null && !matches.isEmpty()) match(r, matches.get(0));
        if (box != null) try {
            r.thumbnail = VideoRecognitionService.thumbnail(ImageIO.read(new ByteArrayInputStream(image)), box);
        } catch (IOException ignored) {
        }
        records.save(r);
    }

    public void startVideo(String id, String filename, String role) {
        RecognitionHistory r = video(id, filename, role);
        r.kind = "TASK";
        r.status = "PROCESSING";
        records.save(r);
    }

    private RecognitionHistory video(String id, String filename, String role) {
        RecognitionHistory r = new RecognitionHistory();
        r.source = "VIDEO";
        r.taskId = id;
        r.taskState = "RUNNING";
        r.sourceName = filename;
        r.requestedRole = role;
        r.threshold = config.getThreshold();
        return r;
    }

    public void videoFace(String id, String filename, String role, VideoFaceResult result) {
        RecognitionHistory r = video(id, filename, role);
        r.status = result.status;
        r.frame = result.frame;
        r.timeSeconds = result.timeSeconds;
        r.box = result.box;
        r.thumbnail = result.thumbnail;
        if (result.match != null) match(r, result.match);
        records.save(r);
    }

    private void match(RecognitionHistory r, RecognitionResult m) {
        r.role = m.getFaceRole();
        r.code = m.getCode();
        r.name = m.getName();
        r.score = m.getScore();
    }

    public void startCamera(String id, String source, String name, String role) {
        RecognitionHistory r = camera(id, source, name, role);
        r.kind = "TASK";
        r.status = "PROCESSING";
        records.save(r);
    }

    private RecognitionHistory camera(String id, String source, String name, String role) {
        RecognitionHistory r = new RecognitionHistory();
        r.source = source;
        r.sourceName = name;
        r.taskId = id;
        r.taskState = "RUNNING";
        r.requestedRole = role;
        r.threshold = config.getThreshold();
        return r;
    }

    // 调用方负责限频与连续确认；此处只持久化选中的抓拍，不保存录入用大图或RTSP地址。
    public void cameraFace(String id, String source, String name, String role, VideoFaceResult result) {
        RecognitionHistory r = camera(id, source, name, role);
        r.status = result.status;
        r.frame = result.frame;
        r.timeSeconds = result.timeSeconds;
        r.box = result.box;
        r.thumbnail = result.thumbnail;
        if (result.match != null) match(r, result.match);
        records.save(r);
    }

    public void finishCamera(String id, String state, String error, boolean noFaces) {
        records.finishVideo(id, state, error, noFaces);
    }

    public void finishVideo(String id, String state, String error, boolean noFaces) {
        records.finishVideo(id, state, error, noFaces);
    }

    public Map<String, Object> page(RecognitionHistoryRepository.Filter filter, int page, int size) {
        return records.page(filter, page, size);
    }

    public RecognitionHistory get(String id) {
        return records.get(id);
    }

    // CSV包含UTF-8 BOM并处理引号、公式前缀，便于表格软件打开且避免文本被解释为公式。
    public byte[] export(RecognitionHistoryRepository.Filter filter) {
        StringBuilder out = new StringBuilder("\ufeff时间,来源,类型,文件名,识别状态,视频任务状态,姓名,编号,角色,相似度,视频时间秒,帧号,任务ID,错误\r\n");
        for (RecognitionHistory r : records.export(filter)) {
            Object[] values = {r.createdAt, r.source, r.kind, r.sourceName, r.status, r.taskState, r.name, r.code, r.role, r.score, r.timeSeconds, r.frame, r.taskId, r.error == null ? r.taskError : r.error};
            for (int i = 0; i < values.length; i++) {
                if (i > 0) out.append(',');
                String text = values[i] == null ? "" : String.valueOf(values[i]);
                String trimmed = text.stripLeading();
                if (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0) text = "'" + text;
                out.append('"').append(text.replace("\"", "\"\"")).append('"');
            }
            out.append("\r\n");
        }
        return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
