package com.face.api.service;

import com.face.api.config.FaceProperties;
import com.face.api.engine.FaceEngine;
import com.face.api.engine.FaceEngineException;
import com.face.api.engine.FaceSample;
import com.face.api.engine.FaceBusyException;
import com.face.api.engine.EngineLifecycle;
import com.face.api.model.FaceBox;
import com.face.api.model.FaceInfoVO;
import com.face.api.model.FaceRecord;
import com.face.api.model.FaceRoles;
import com.face.api.model.LivenessResult;
import com.face.api.model.OneToOneResult;
import com.face.api.model.QualityResult;
import com.face.api.model.RecognitionResult;
import com.face.api.repository.FaceRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** 人脸业务服务，负责参数校验、引擎调用和底库匹配。 */
@Service
public class FaceService {
    private final FaceEngine engine;
    private final FaceRepository repository;
    private final FaceProperties properties;
    private final ThreadPoolTaskExecutor executor;
    private final EngineLifecycle lifecycle;
    private final RecognitionHistoryService history;

    public FaceService(
            FaceEngine engine,
            FaceRepository repository,
            FaceProperties properties,
            @Qualifier("faceTaskExecutor") ThreadPoolTaskExecutor executor) {
        this(engine,repository,properties,executor,null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FaceService(FaceEngine engine, FaceRepository repository, FaceProperties properties,
            @Qualifier("faceTaskExecutor") ThreadPoolTaskExecutor executor, RecognitionHistoryService history) {
        this.engine = engine;
        this.repository = repository;
        this.properties = properties;
        this.executor = executor;
        this.lifecycle = new EngineLifecycle(engine, properties.getRequestTimeoutMs());
        this.history = history;
    }

    /** 提取特征并写入底库；同一 role/code 会被覆盖更新。 */
    public void collect(byte[] image, String role, String code, String name, String contentType) {
        collect(image,role,code,name,contentType,false);
    }
    // 抓拍录入只允许创建新档案；与原有可覆盖采集接口分开，避免误清空主图和参考照片。
    public void collectCapture(byte[] image,String role,String code,String name,String contentType) {
        if(code!=null&&code.length()>128)throw new IllegalArgumentException("编号不能超过128个字符");
        if(name!=null&&name.length()>128)throw new IllegalArgumentException("姓名不能超过128个字符");
        collect(image,role,code,name,contentType,true);
    }
    private void collect(byte[] image,String role,String code,String name,String contentType,boolean createOnly) {
        requireImage(image);
        requireRole(role);
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("code 不能为空");
        }

        run(() -> {
            // 即使照片来自服务端抓拍，也重新检测单人并提取当前模型特征，不接受客户端提供的向量。
            float[] feature = engine.analyze(image).getFeature();
            String base64 = Base64.getEncoder().encodeToString(image);
            String imageData = contentType == null ? base64 : "data:" + contentType + ";base64," + base64;
            FaceRecord record = new FaceRecord(role, code, name, imageData, feature);
            record.setFeatureSpaceId(engine.featureSpaceId());
            // 重复编号检查与保存由仓储同步完成，不能先在控制器查询再写入。
            if(createOnly)repository.saveNew(record);else repository.save(record);
            return null;
        });
    }

    /** 对目标图片执行 1:N 检索，仅返回满足分差标准的唯一最高候选。 */
    public List<RecognitionResult> recognize(
            byte[] image, String role, Double threshold, Integer verifyMinSide) {
        requireImage(image);
        if (!FaceRoles.isSupported(role)) {
            throw new IllegalArgumentException("不受支持的角色:" + role);
        }

        double limit = threshold(threshold);
        if (verifyMinSide != null && verifyMinSide < 0) throw new IllegalArgumentException("verifyMinSide 不能为负数");
        long started = System.nanoTime();
        FaceBox[] detected = new FaceBox[1];
        List<RecognitionResult> result;
        try { result = run(() -> {
            FaceSample sample = engine.analyze(image);
            FaceBox box = sample.getBox();
            detected[0] = box;
            if (verifyMinSide != null && Math.min(box.getRight() - box.getLeft(), box.getBottom() - box.getTop()) < verifyMinSide)
                return Collections.emptyList();
            FaceRecord best = null;
            double first = -Double.MAX_VALUE, second = -Double.MAX_VALUE;
            for (FaceRecord record : repository.findByRole(role)) {
                if (!engine.featureSpaceId().equals(record.getFeatureSpaceId()))
                    throw new FaceEngineException("底库模型版本不匹配，请重新采集或迁移");
                double score = referenceScore(sample.getFeature(), record);
                if (!Double.isFinite(score)) throw new FaceEngineException("无效的人脸相似度");
                if (score > first) { second = first; first = score; best = record; }
                else if (score > second) second = score;
            }
            // 最高候选必须同时满足相似度和不同人员之间的分差；分数接近时宁可返回未匹配。
            if (best == null || first < limit || first - second < properties.getMinMargin()) return Collections.emptyList();
            return Collections.singletonList(new RecognitionResult(best.getRole(), best.getCode(), best.getName(), first, box));
        }); } catch (RuntimeException failure) {
            if (history != null) {
                try { history.photo(image,role,Collections.emptyList(),detected[0],TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),engine.featureSpaceId(),limit,failure); }
                catch (RuntimeException writeFailure) { failure.addSuppressed(writeFailure); }
            }
            throw failure;
        }
        if (history != null) history.photo(image,role,result,detected[0],TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),engine.featureSpaceId(),limit,null);
        return result;
    }

    /** 对视频或摄像头的一帧执行多人检测与逐人检索，过小人脸仍保留检测结果。 */
    public List<com.face.api.model.VideoFaceResult> recognizeFrame(byte[] image, String role) {
        requireImage(image);
        if (!FaceRoles.isSupported(role)) throw new IllegalArgumentException("不受支持的角色:" + role);
        return run(() -> {
            // 每次画面识别读取最新底库快照，因此手工录入后后续帧立即可用，无需重连摄像头。
            List<FaceRecord> records = repository.findByRole(role);
            for (FaceRecord record : records)
                if (!engine.featureSpaceId().equals(record.getFeatureSpaceId()))
                    throw new FaceEngineException("底库模型版本不匹配，请重新采集或迁移");
            List<com.face.api.model.VideoFaceResult> results = new ArrayList<>();
            for (FaceSample sample : engine.analyzeAll(image)) {
                if (sample.getFeature() == null) {
                    results.add(new com.face.api.model.VideoFaceResult(sample.getBox(), "TOO_SMALL", null)); continue;
                }
                FaceRecord best = null;
                double first = -Double.MAX_VALUE, second = -Double.MAX_VALUE;
                for (FaceRecord record : records) {
                    double score = referenceScore(sample.getFeature(), record);
                    if (!Double.isFinite(score)) throw new FaceEngineException("无效的人脸相似度");
                    if (score > first) { second = first; first = score; best = record; }
                    else if (score > second) second = score;
                }
                RecognitionResult match = best != null && first >= properties.getThreshold()
                    && first - second >= properties.getMinMargin()
                    ? new RecognitionResult(best.getRole(), best.getCode(), best.getName(), first, sample.getBox()) : null;
                results.add(new com.face.api.model.VideoFaceResult(sample.getBox(), match == null ? "UNKNOWN" : "MATCHED", match));
            }
            return results;
        });
    }

    /** 对两张单人照片执行1:1余弦比对；分数含义与当前模型和预处理相关。 */
    public OneToOneResult verifyOne(byte[] image1, byte[] image2, Double threshold) {
        requireImage(image1);
        requireImage(image2);
        double limit = threshold(threshold);
        return run(() -> {
            float[] feature1 = engine.analyze(image1).getFeature();
            float[] feature2 = engine.analyze(image2).getFeature();
            double score = engine.compare(feature1, feature2);
            if (!Double.isFinite(score)) throw new FaceEngineException("无效的人脸相似度");
            return new OneToOneResult(score >= limit, score, limit);
        });
    }
    // 每个人先取主图和参考图的最高分，再参与人员间比较，防止同人多样本挤占次高候选。
    private double referenceScore(float[] feature,FaceRecord record) {
        double best=engine.compare(feature,record.getFeature());if(!Double.isFinite(best))throw new FaceEngineException("无效的人脸相似度");
        for(float[] reference:record.getReferenceFeatures()){double score=engine.compare(feature,reference);if(!Double.isFinite(score))throw new FaceEngineException("无效的人脸相似度");best=Math.max(best,score);}return best;
    }
    // 添加参考照片前核验所属人员相似度和其他人员分差，不把未知抓拍自动学习进已有档案。
    public void addReference(byte[] image,String role,String code) {
        requireImage(image);requireRole(role);
        run(()->{
            FaceRecord person=repository.find(role,code).orElseThrow(()->new IllegalArgumentException("人员档案不存在"));
            if(!engine.featureSpaceId().equals(person.getFeatureSpaceId()))throw new FaceEngineException("底库模型版本不匹配");
            float[] feature=engine.analyze(image).getFeature();
            double ownerScore=referenceScore(feature,person);
            if(ownerScore>.99999)throw new IllegalArgumentException("照片与已有样本几乎相同，请补充不同角度或光照的清晰照片");
            if(ownerScore<properties.getThreshold())throw new IllegalArgumentException("与已有照片相似度不足，请上传同一人员清晰、角度接近的照片，或先更换主照片");
            for(FaceRecord other:repository.findAll())if(!(role.equals(other.getRole())&&code.equals(other.getCode()))) {
                if(!engine.featureSpaceId().equals(other.getFeatureSpaceId()))throw new FaceEngineException("底库模型版本不匹配");
                if(ownerScore-referenceScore(feature,other)<properties.getMinMargin())throw new IllegalArgumentException("参考照片与其他人员过于接近，请检查是否选错档案或重新采集");
            }
            FaceRecord sample=new FaceRecord(role,code,person.getName(),Base64.getEncoder().encodeToString(image),feature);sample.setFeatureSpaceId(engine.featureSpaceId());repository.addReference(sample);return null;
        });
    }

    /** 返回图片中检测到的所有人脸框。 */
    public List<FaceBox> detect(byte[] image) {
        requireImage(image);
        return run(() -> engine.detectFaces(image));
    }

    /** 活体能力必须由独立模型提供。 */
    public LivenessResult liveness(byte[] image) {
        requireImage(image);
        if (!engine.supportsLiveness()) {
            return new LivenessResult(
                    false,
                    false,
                    "YuNet/SFace 不提供活体检测，需独立接入活体模型");
        }
        return new LivenessResult(true, run(() -> engine.liveness(image)), "检测完成");
    }

    /** 基于人脸数量执行基础质量检查，具体质量模型可继续扩展。 */
    public QualityResult quality(byte[] image, Double qualityScore) {
        requireImage(image);
        List<FaceBox> boxes = detect(image);
        String message = boxes.isEmpty()
                ? "未检测到人脸"
                : boxes.size() > 1 ? "人脸数量超过系统限制" : "人脸数量检查通过，未评估模糊、姿态和活体";
        QualityResult result = new QualityResult(boxes.size() == 1, boxes.size(), message);
        if (boxes.size() != 1) {
            result.setBlurry(false);
        }
        return result;
    }

    /** 从 SQLite 和内存底库删除指定人脸。 */
    public void remove(String role, String code) {
        requireRole(role);
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("code 不能为空");
        }
        repository.delete(role, code);
    }

    /** 转换为与旧项目相同的角色人脸列表。 */
    public List<FaceInfoVO> list(String role) {
        if (!FaceRoles.isSupported(role)) {
            throw new IllegalArgumentException("不受支持的角色:" + role);
        }

        List<FaceInfoVO> result = new ArrayList<>();
        for (FaceRecord record : repository.findByRole(role)) {
            FaceInfoVO item = new FaceInfoVO();
            item.setName(record.getName());
            item.setFaceRole(record.getRole());
            item.setCode(record.getCode());
            item.setFaceImage(record.getImageBase64());
            result.add(item);
        }
        return result;
    }

    /** 返回底层人脸引擎是否已成功初始化。 */
    public boolean isAvailable() {
        return lifecycle.isAvailable();
    }

    /** 返回底层人脸引擎名称，供状态接口展示。 */
    public String engineName() {
        return engine.engineName();
    }

    /** 返回当前内存底库中的人脸数量。 */
    public int databaseSize() {
        return repository.findAll().size();
    }

    private void requireRole(String role) {
        if (role == null || role.trim().isEmpty()) {
            throw new IllegalArgumentException("role 不能为空");
        }
        if (!FaceRoles.isSupported(role)) {
            throw new IllegalArgumentException("不受支持的角色:" + role);
        }
    }

    private void requireImage(byte[] image) {
        if (image == null || image.length == 0) {
            throw new IllegalArgumentException("图片不能为空");
        }
        if (image.length > 10 * 1024 * 1024) throw new IllegalArgumentException("图片不能超过 10 MB");
    }

    /** 把引擎工作提交到专用线程池，并还原底层异常。 */
    private <T> T run(Supplier<T> action) {
        long ticket = lifecycle.ticket();
        Future<T> future = null;
        try {
            future = executor.submit(() -> lifecycle.execute(ticket, action));
            return future.get(properties.getRequestTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (future != null) future.cancel(true);
            throw new FaceBusyException("人脸请求等待超时，请稍后重试");
        } catch (InterruptedException e) {
            if (future != null) future.cancel(true);
            Thread.currentThread().interrupt();
            throw new FaceBusyException("人脸请求已中断");
        } catch (RejectedExecutionException e) {
            throw new FaceBusyException("人脸请求已达并发上限，请稍后重试");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new FaceEngineException("人脸引擎执行失败", cause);
        }
    }

    private double threshold(Double requested) {
        if (requested != null && (!Double.isFinite(requested) || requested < 0 || requested > 1))
            throw new IllegalArgumentException("matchScore 必须在 0 到 1 之间");
        return requested == null ? properties.getThreshold() : Math.max(properties.getThreshold(), requested);
    }

    public EngineLifecycle lifecycle() { return lifecycle; }

    public java.util.Map<String, Object> engineStatus() {
        java.util.Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("recognitionBackend", properties.getRecognitionBackend());status.put("featureDimensions", "auraface".equals(properties.getRecognitionBackend())?512:128);status.put("inferenceThreads", properties.getInferenceThreads());
        status.put("engine", engineName()); status.put("state", lifecycle.getState());
        status.put("available", isAvailable()); status.put("modelLoaded", engine.isAvailable());
        status.put("databaseSize", databaseSize()); status.put("storage", "SQLite");
        status.put("databasePath", java.nio.file.Paths.get(properties.getStoragePath()).toAbsolutePath().normalize().toString());
        status.put("featureSpaceId", lifecycle.getFeatureSpaceId());
        status.put("detectorModel", properties.getDetectorModel()); status.put("recognizerModel", properties.getRecognizerModel());
        status.put("workers", properties.getWorkers()); status.put("maxInFlight", properties.getMaxInFlight());
        status.put("activeTasks", executor.getActiveCount()); status.put("queuedTasks", executor.getThreadPoolExecutor().getQueue().size());
        status.put("completedTasks", executor.getThreadPoolExecutor().getCompletedTaskCount());
        status.put("threshold", properties.getThreshold()); status.put("minMargin", properties.getMinMargin());
        status.put("minFaceSize", properties.getMinFaceSize()); status.put("detectionSize", properties.getDetectionSize());
        status.put("startedAt", lifecycle.getStartedAt().toString()); status.put("changedAt", lifecycle.getChangedAt().toString());
        status.put("lastError", lifecycle.getLastError());
        return status;
    }
}
