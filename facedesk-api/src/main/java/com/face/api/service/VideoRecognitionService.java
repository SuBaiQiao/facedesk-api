package com.face.api.service;

import com.face.api.engine.FaceBusyException;
import com.face.api.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.annotation.PreDestroy;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

/** 单并发视频任务；结果落临时磁盘，分页读取，终态一小时过期。 */
@Service
public class VideoRecognitionService {
    private static final long MAX_UPLOAD = 500L * 1024 * 1024;
    private static final long MAX_RESULTS_BYTES = 256L * 1024 * 1024;
    private static final int MAX_RESULTS = 200000;
    private final FaceService faces;
    private final VideoDecoder decoder;
    private final ObjectMapper json;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "face-video"); t.setDaemon(true); return t; });
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "face-video-cleanup"); t.setDaemon(true); return t; });
    private boolean occupied;
    private volatile boolean closing;
    private final RecognitionHistoryService history;
    public VideoRecognitionService(FaceService faces, VideoDecoder decoder, ObjectMapper json) {
        this(faces,decoder,json,null);
    }
    private final com.face.api.cluster.ClusterProperties cluster;
    public VideoRecognitionService(FaceService faces, VideoDecoder decoder, ObjectMapper json, RecognitionHistoryService history) {this(faces,decoder,json,history,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public VideoRecognitionService(FaceService faces, VideoDecoder decoder, ObjectMapper json, RecognitionHistoryService history,com.face.api.cluster.ClusterProperties cluster) {
        this.faces = faces; this.decoder = decoder; this.json = json;
        this.history = history;this.cluster=cluster;
        cleaner.scheduleWithFixedDelay(this::cleanup, 1, 1, TimeUnit.MINUTES);
    }
    // 视频单任务执行；上传文件和结果使用临时文件，任务失败时仍可查看已完成部分。
    public synchronized Map<String,Object> start(MultipartFile file, String role) throws IOException {
        cleanup();
        if (closing) throw new FaceBusyException("服务正在关闭");
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择视频文件");
        if (file.getSize() > MAX_UPLOAD) throw new IllegalArgumentException("视频不能超过 500 MB");
        if (!FaceRoles.isSupported(role)) throw new IllegalArgumentException("不受支持的角色");
        if (!faces.isAvailable()) throw new FaceBusyException("请先恢复识别引擎");
        if (occupied) throw new FaceBusyException("已有视频正在处理，请等待完成或取消后再提交");
        if (jobs.size() >= 4) {
            Job oldest = jobs.values().iterator().next(); dispose(oldest); jobs.remove(oldest.id);
        }
        String name = file.getOriginalFilename() == null ? "video" : file.getOriginalFilename();
        String extension = name.toLowerCase(Locale.ROOT).endsWith(".avi") ? ".avi" : ".mp4";
        Path source = Files.createTempFile("face-video-", extension), results = null;
        try {
            file.transferTo(source);
            results = Files.createTempFile("face-video-results-", ".jsonl");
            Job job = new Job(cluster==null?UUID.randomUUID().toString():cluster.newTaskId(),name, role, source, results);
            if (history != null) history.startVideo(job.id,name,role);
            jobs.put(job.id, job); occupied = true;
            try { worker.submit(() -> process(job)); }
            catch (RejectedExecutionException e) { jobs.remove(job.id); occupied = false; throw e; }
            return snapshot(job);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(source); if (results != null) Files.deleteIfExists(results); throw e;
        }
    }
    private void process(Job job) {
        try (VideoDecoder.Session video = decoder.open(job.source); RandomAccessFile out = new RandomAccessFile(job.results.toFile(), "rw")) {
            synchronized (job) { job.fps = video.fps; job.totalFrames = video.totalFrames; }
            long started = System.nanoTime();
            while (!job.cancelled) {
                if (TimeUnit.NANOSECONDS.toMinutes(System.nanoTime() - started) >= 30)
                    throw new IllegalArgumentException("处理超过 30 分钟限制；已保留部分结果，请拆分视频重试");
                // 上传视频顺序处理每个可解码帧；不同于实时流，这里不会为追赶进度而主动丢帧。
                byte[] frame = video.next();
                if (frame == null) break;
                long index; synchronized (job) { index = job.processedFrames; }
                double time = video.timeSeconds(index);
                List<VideoFaceResult> detections = faces.recognizeFrame(frame, job.role);
                BufferedImage image = detections.isEmpty() ? null : ImageIO.read(new ByteArrayInputStream(frame));
                for (VideoFaceResult result : detections) {
                    if (job.cancelled) break;
                    result.frame = index; result.timeSeconds = time; result.thumbnail = thumbnail(image, result.box);
                    if ("UNKNOWN".equals(result.status)) result.enrollmentImage = enrollmentImage(image,result.box);
                    // 每个人脸事件单独存为一行JSON；偏移索引用于分页，避免把所有图片保存在内存。
                    byte[] line = (json.writeValueAsString(result) + "\n").getBytes(StandardCharsets.UTF_8);
                    synchronized (job) {
                        // 次数和字节数任一达到上限就明确结束为部分失败，包含录入图的结果也计入限额。
                        if (job.offsets.size() >= MAX_RESULTS || out.length() + line.length > MAX_RESULTS_BYTES)
                            throw new IllegalArgumentException("抓拍结果达到存储上限；已保留部分结果，请拆分视频重试");
                        if (history != null) history.videoFace(job.id,job.filename,job.role,result);
                        long offset = out.getFilePointer(); out.write(line); job.offsets.add(offset);
                        if (result.match != null) { job.matched++; job.people.add(result.match.getFaceRole() + ":" + result.match.getCode()); }
                        else if ("TOO_SMALL".equals(result.status)) job.tooSmall++; else job.unknown++;
                    }
                }
                synchronized (job) { job.processedFrames++; job.timeSeconds = time; }
            }
            synchronized (job) {
                if (!job.cancelled && job.processedFrames == 0) throw new IllegalArgumentException("视频中没有可解码的画面");
                if (!job.cancelled && job.totalFrames > 0 && job.processedFrames < job.totalFrames)
                    throw new IllegalArgumentException("视频在末尾之前停止解码，可能存在损坏；已保留部分结果");
                job.state = job.cancelled ? "CANCELLED" : "COMPLETED";
            }
        } catch (Exception | LinkageError e) {
            synchronized (job) { job.state = job.cancelled ? "CANCELLED" : "FAILED"; job.error = e.getMessage() == null ? "视频处理失败" : e.getMessage(); }
        } finally {
            if (history != null) try { history.finishVideo(job.id,job.state,job.error,job.offsets.isEmpty()); }
            catch (RuntimeException e) { synchronized(job) { job.state="FAILED"; job.error=e.getMessage(); } }
            try { Files.deleteIfExists(job.source); } catch (IOException ignored) { }
            synchronized (this) { synchronized (job) { job.finishedAt = System.currentTimeMillis(); } occupied = false; }
            if (closing) dispose(job);
        }
    }
    // 录入需重新检测关键点，保留人脸四周30%上下文和最多512像素，不能复用112像素展示图。
    static String enrollmentImage(BufferedImage source, FaceBox box) throws IOException {
        if (source == null) return null;
        int padX=(int)Math.ceil((box.getRight()-box.getLeft())*.3),padY=(int)Math.ceil((box.getBottom()-box.getTop())*.3);
        return crop(source,new FaceBox(box.getLeft()-padX,box.getTop()-padY,box.getRight()+padX,box.getBottom()+padY),512);
    }
    static String thumbnail(BufferedImage source, FaceBox box) throws IOException { return crop(source,box,112); }
    private static String crop(BufferedImage source, FaceBox box, int maxSide) throws IOException {
        if (source == null) return null;
        int left = Math.max(0, box.getLeft()), top = Math.max(0, box.getTop());
        int width = Math.min(source.getWidth(), box.getRight()) - left, height = Math.min(source.getHeight(), box.getBottom()) - top;
        if (width <= 0 || height <= 0) return null;
        // 只缩小不放大，避免把过小或模糊抓拍伪装成高清录入照片。
        double scale = Math.min(1, (double)maxSide / Math.max(width, height));
        BufferedImage image = new BufferedImage(Math.max(1,(int)(width*scale)),Math.max(1,(int)(height*scale)),BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try { g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR); g.drawImage(source,0,0,image.getWidth(),image.getHeight(),left,top,left+width,top+height,null); }
        finally { g.dispose(); }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(image,"jpg",bytes);
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    public synchronized Map<String,Object> status(String id) { return snapshot(get(id)); }
    // 取消是协作式的：正在执行的原生推理结束后，处理循环在边界检查取消标志。
    public synchronized Map<String,Object> cancel(String id) {
        Job job = get(id); synchronized (job) { if ("RUNNING".equals(job.state)) job.cancelled = true; }
        return snapshot(job);
    }
    public synchronized Map<String,Object> page(String id, int page, int size) throws IOException {
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("分页参数无效");
        Job job = get(id);
        synchronized (job) {
            int total = job.offsets.size();
            List<Object> items = new ArrayList<>();
            long start = (long)(page - 1) * size;
            try (RandomAccessFile input = new RandomAccessFile(job.results.toFile(), "r")) {
                for (long i = start; i < Math.min(start + size, total); i++) {
                    input.seek(job.offsets.get((int)i));
                    String line = input.readLine();
                    items.add(json.readValue(new String(line.getBytes(StandardCharsets.ISO_8859_1),StandardCharsets.UTF_8), VideoFaceResult.class));
                }
            }
            Map<String,Object> result = snapshot(job); result.put("items",items); result.put("page",page); result.put("pages",Math.max(1,(total+size-1)/size)); return result;
        }
    }
    public synchronized byte[] export(String id) throws IOException {
        Job job = get(id);
        synchronized (job) {
            // CSV only includes metadata, not thumbnails; quote values and neutralize spreadsheet formulas.
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Writer writer = new OutputStreamWriter(bytes,StandardCharsets.UTF_8);
            writer.write("\ufeffframe,time_seconds,status,role,code,name,score,left,top,right,bottom\r\n");
            try (RandomAccessFile input = new RandomAccessFile(job.results.toFile(),"r")) {
                for (long offset : job.offsets) {
                    input.seek(offset); String line = input.readLine();
                    VideoFaceResult r = json.readValue(new String(line.getBytes(StandardCharsets.ISO_8859_1),StandardCharsets.UTF_8),VideoFaceResult.class);
                    RecognitionResult m = r.match;
                    writer.write(r.frame+","+r.timeSeconds+","+r.status+","+csv(m==null?"":m.getFaceRole())+","+csv(m==null?"":m.getCode())+","+csv(m==null?"":m.getName())+","+(m==null?"":m.getScore())+","+r.box+"\r\n");
                }
            }
            writer.flush(); return bytes.toByteArray();
        }
    }
    private static String csv(String s) {
        if (s == null) s = "";
        if (s.matches("^[\\s]*[=+@-].*")) s = "'" + s;
        return "\"" + s.replace("\"","\"\"") + "\"";
    }
    private Job get(String id) {
        Job job = jobs.get(id);
        if (job == null) throw new IllegalArgumentException("视频任务不存在或已过期，请重新上传");
        return job;
    }
    private Map<String,Object> snapshot(Job job) {
        synchronized (job) {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id",job.id); m.put("filename",job.filename); m.put("state",job.state); m.put("cancelRequested",job.cancelled);
            m.put("processedFrames",job.processedFrames); m.put("totalFrames",job.totalFrames); m.put("fps",job.fps); m.put("timeSeconds",job.timeSeconds);
            m.put("total",job.offsets.size()); m.put("matched",job.matched); m.put("unknown",job.unknown); m.put("tooSmall",job.tooSmall); m.put("people",job.people.size()); m.put("error",job.error);
            return m;
        }
    }
    private synchronized void cleanup() {
        Iterator<Job> it = jobs.values().iterator();
        while (it.hasNext()) { Job job = it.next(); if (job.finishedAt > 0 && System.currentTimeMillis()-job.finishedAt >= 3600000) { dispose(job); it.remove(); } }
    }
    private void dispose(Job job) { try { Files.deleteIfExists(job.results); Files.deleteIfExists(job.source); } catch (IOException ignored) { } }
    @PreDestroy public void close() {
        synchronized(this) {
            closing = true;
            for (Job job : jobs.values()) job.cancelled = true;
            worker.shutdownNow(); cleaner.shutdownNow();
        }
        // 必须在服务锁外等待线程结束；处理线程finally也需要该锁，锁内等待会阻塞收尾。
        try { worker.awaitTermination(5,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        synchronized(this) { for (Job job : jobs.values()) dispose(job); }
    }
    private static final class Job {
        final String id, filename, role;
        final Path source, results;
        final List<Long> offsets = new ArrayList<>(); final Set<String> people = new HashSet<>();
        volatile boolean cancelled; volatile long finishedAt;
        String state = "RUNNING", error; long processedFrames,totalFrames,matched,unknown,tooSmall; double fps,timeSeconds;
        Job(String id,String filename,String role,Path source,Path results) { this.id=id;this.filename=filename; this.role=role; this.source=source; this.results=results; }
    }
}
