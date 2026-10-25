package com.face.api.engine;

import com.face.api.config.FaceProperties;
import com.face.api.model.FaceBox;
import nu.pattern.OpenCV;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.FaceDetectorYN;
import org.opencv.objdetect.FaceRecognizerSF;
import org.opencv.dnn.Dnn;
import org.opencv.dnn.Net;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** 离线 YuNet 五点检测，支持 SFace128 / AuraFace512；每个 worker 独占原生实例。 */
public class SFaceEngine implements FaceEngine {
    private final FaceProperties config;
    private final BlockingQueue<Worker> workers;
    private volatile boolean available;
    private String spaceId;
    // 兼容保留原类名；识别后端决定预处理、输出维数和模型指纹，不能只替换模型文件。
    private boolean aura() { return "auraface".equals(config.getRecognitionBackend()); }
    private int dimensions() { return aura() ? 512 : 128; }
    public SFaceEngine(FaceProperties config) {
        this.config = config;
        workers = new ArrayBlockingQueue<>(config.getWorkers());
    }
    @Override public synchronized void initialize() {
        if (available) return;
        try {
            Path det = Paths.get(config.getModelDir(), config.getDetectorModel()).toAbsolutePath();
            Path rec = Paths.get(config.getModelDir(), config.getRecognizerModel()).toAbsolutePath();
            requireModel(det); requireModel(rec);
            // 指纹包含预处理版本和检测/识别模型内容，阻止不同特征空间进入同一底库。
            spaceId = (aura() ? "yunet-auraface-512-rgb127.5-v1:" : "yunet-sface-128-v1:") + digest(det) + ":" + digest(rec);
            OpenCV.loadLocally();
            Core.setNumThreads(config.getInferenceThreads());
            // OpenCV 推理实例含可变内部状态，每个 worker 独占实例，请求从池中借用。
            for (int i = 0; i < config.getWorkers(); i++) workers.add(new Worker(
                FaceDetectorYN.create(det.toString(), "", new Size(640, 640), config.getDetectionThreshold(), 0.3f, 5000),
                FaceRecognizerSF.create((aura() ? Paths.get(config.getModelDir(), "face_recognition_sface_2021dec.onnx") : rec).toString(), ""),
                aura() ? Dnn.readNetFromONNX(rec.toString()) : null));
            // 启动时用固定输入预热并核验输出维数，尽早发现模型配置错误。
            if (aura()) {
                Mat aligned = Mat.zeros(112,112,CvType.CV_8UC3), feature = new Mat();
                try { for (Worker worker : workers) {
                    extract(worker,aligned,feature);
                    if (feature.total()!=512 || feature.depth()!=CvType.CV_32F) throw new FaceEngineException("AuraFace 模型应输出512维float特征");
                } } finally { aligned.release();feature.release(); }
            }
            available = true;
        } catch (Exception | LinkageError e) {
            workers.clear();
            throw new FaceEngineException("人脸模型初始化失败，请检查本地 ONNX 模型与 OpenCV 原生库", e);
        }
    }
    private static void requireModel(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) < 10000)
            throw new IOException("模型缺失或为 Git LFS 指针: " + path);
    }
    private static String digest(Path path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) md.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) hex.append(String.format("%02x", b & 255));
        return hex.toString();
    }
    @Override public boolean isAvailable() { return available; }
    @Override public String engineName() { return aura() ? "OpenCV-YuNet-AuraFace-v1" : "OpenCV-YuNet-SFace"; }
    @Override public String featureSpaceId() { return spaceId; }
    @Override public float[] extractFeature(byte[] bytes) { return analyze(bytes).getFeature(); }
    @Override public FaceSample analyze(byte[] bytes) {
        return use(worker -> {
            Mat image = decode(bytes), faces = new Mat(), aligned = new Mat(), feature = new Mat();
            Mat row = null;
            try {
                detect(worker, image, faces);
                if (faces.rows() != 1) throw new FaceEngineException(faces.empty() ? "未检测到人脸" : "图片必须仅包含一张人脸");
                FaceBox box = box(faces, 0, image.cols(), image.rows());
                if (Math.min(box.getRight() - box.getLeft(), box.getBottom() - box.getTop()) < config.getMinFaceSize())
                    throw new FaceEngineException("人脸过小，请靠近摄像头或上传更清晰照片");
                row = faces.row(0);
                worker.recognizer.alignCrop(image, row, aligned);
                extract(worker, aligned, feature);
                if (feature.total() != dimensions() || feature.depth() != CvType.CV_32F)
                    throw new FaceEngineException("人脸模型输出维度或类型不匹配");
                float[] vector = new float[dimensions()]; feature.get(0, 0, vector);
                return new FaceSample(box, FeatureMath.normalize(vector));
            } finally {
                if (row != null) row.release();
                image.release(); faces.release(); aligned.release(); feature.release();
            }
        });
    }
    @Override public List<FaceBox> detectFaces(byte[] bytes) {
        return use(worker -> {
            Mat image = decode(bytes), faces = new Mat();
            try {
                detect(worker, image, faces);
                List<FaceBox> result = new ArrayList<>();
                for (int i = 0; i < faces.rows(); i++) result.add(box(faces, i, image.cols(), image.rows()));
                return result;
            } finally { image.release(); faces.release(); }
        });
    }
    @Override public List<FaceSample> analyzeAll(byte[] bytes) {
        return use(worker -> {
            Mat image = decode(bytes), faces = new Mat();
            try {
                detect(worker, image, faces);
                List<FaceSample> result = new ArrayList<>();
                for (int i = 0; i < faces.rows(); i++) {
                    FaceBox box = box(faces, i, image.cols(), image.rows());
                    if (Math.min(box.getRight() - box.getLeft(), box.getBottom() - box.getTop()) < config.getMinFaceSize()) {
                        result.add(new FaceSample(box, null)); continue;
                    }
                    Mat row = faces.row(i), aligned = new Mat(), feature = new Mat();
                    try {
                        worker.recognizer.alignCrop(image, row, aligned);
                        extract(worker, aligned, feature);
                        if (feature.total() != dimensions() || feature.depth() != CvType.CV_32F)
                            throw new FaceEngineException("人脸模型输出维度或类型不匹配");
                        float[] vector = new float[dimensions()]; feature.get(0, 0, vector);
                        result.add(new FaceSample(box, FeatureMath.normalize(vector)));
                    } finally { row.release(); aligned.release(); feature.release(); }
                }
                return result;
            } finally { image.release(); faces.release(); }
        });
    }
    // 对齐均采用112像素五点模板；AuraFace输入需要RGB和127.5归一化，SFace走自身预处理。
    private void extract(Worker worker, Mat aligned, Mat feature) {
        if (!aura()) { worker.recognizer.feature(aligned, feature); return; }
        Mat blob = Dnn.blobFromImage(aligned, 1.0 / 127.5, new Size(112,112), new Scalar(127.5,127.5,127.5), true, false);
        Mat output = null;
        try { worker.arcface.setInput(blob); output = worker.arcface.forward(); output.copyTo(feature); }
        finally { blob.release(); if (output != null) output.release(); }
    }
    private Mat decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw new FaceEngineException("图片不能为空");
        // 解码前检查像素，控制并发请求的解压内存。
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new FaceEngineException("无效的图片");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > config.getMaxImagePixels())
                    throw new FaceEngineException("图片像素超过限制");
            } finally { reader.dispose(); }
        } catch (IOException e) { throw new FaceEngineException("图片无法读取", e); }
        MatOfByte encoded = new MatOfByte(bytes);
        try {
            Mat image = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR);
            if (image.empty()) { image.release(); throw new FaceEngineException("图片无法解码"); }
            return image;
        } finally { encoded.release(); }
    }
    private void detect(Worker worker, Mat image, Mat faces) {
        Mat resized = new Mat();
        try {
            // 检测可用缩小图节省耗时；下方将检测框和五个关键点一起映射回原图坐标。
            double scale = Math.min(1.0, (double) config.getDetectionSize() / Math.max(image.cols(), image.rows()));
            int width = Math.max(1, (int) Math.round(image.cols() * scale));
            int height = Math.max(1, (int) Math.round(image.rows() * scale));
            Imgproc.resize(image, resized, new Size(width, height));
            worker.detector.setInputSize(resized.size());
            worker.detector.detect(resized, faces);
            double sx = (double) image.cols() / width, sy = (double) image.rows() / height;
            for (int r = 0; r < faces.rows(); r++) {
                float[] values = new float[15]; faces.get(r, 0, values);
                for (int c = 0; c < 14; c++) values[c] *= (c % 2 == 0 ? sx : sy);
                faces.put(r, 0, values);
            }
        } finally { resized.release(); }
    }
    private FaceBox box(Mat faces, int row, int width, int height) {
        float[] v = new float[15]; faces.get(row, 0, v);
        return new FaceBox(Math.max(0, (int) v[0]), Math.max(0, (int) v[1]),
            Math.min(width, (int) Math.ceil(v[0] + v[2])), Math.min(height, (int) Math.ceil(v[1] + v[3])));
    }
    // 有超时地借用推理实例，并在异常路径归还；生命周期层负责等待在途任务后再关闭引擎。
    private <T> T use(Operation<T> operation) {
        if (!available) throw new FaceEngineException("人脸模型未初始化");
        Worker worker = null;
        try {
            worker = workers.poll(config.getRequestTimeoutMs(), TimeUnit.MILLISECONDS);
            if (worker == null) throw new FaceBusyException("人脸推理繁忙，请稍后重试");
            return operation.apply(worker);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new FaceBusyException("人脸请求已中断");
        } catch (FaceEngineException | FaceBusyException e) { throw e;
        } catch (RuntimeException e) { throw new FaceEngineException("人脸模型推理失败", e);
        } finally { if (worker != null && available) workers.offer(worker); }
    }
    @Override public double compare(float[] a, float[] b) { return FeatureMath.cosine(a, b); }
    @Override public synchronized void close() {
        available = false;
        // OpenCV Java 模型 wrapper 无公开 close，撤销引用后由其原生析构器回收。
        // 请求级 Mat 在 finally 中及时 release。
        workers.clear();
    }
    private interface Operation<T> { T apply(Worker worker); }
    private static final class Worker {
        final FaceDetectorYN detector; final FaceRecognizerSF recognizer; final Net arcface;
        Worker(FaceDetectorYN d, FaceRecognizerSF r, Net a) { detector = d; recognizer = r; arcface = a; }
    }
}
