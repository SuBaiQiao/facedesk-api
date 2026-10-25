package com.face.api.service;

import com.face.api.engine.FaceBusyException;
import com.face.api.model.*;
import org.springframework.stereotype.Service;
import javax.annotation.PreDestroy;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 有界实时会话；RTSP 只保留最新采集帧，不积压旧画面。 */
@Service
public class LiveRecognitionService {
    private final FaceService faces;
    private final RecognitionHistoryService history;
    private final RtspFrameSource rtsp;
    private final Map<String,Session> sessions=new LinkedHashMap<>();
    // 每路RTSP占采集和识别两个任务；会话数与任务队列都有上限，避免无限占用线程。
    private final ExecutorService workers=new ThreadPoolExecutor(8,8,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"face-live");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService cleaner=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"face-live-watchdog");t.setDaemon(true);return t;});
    private boolean closed;
    public LiveRecognitionService(FaceService faces,RecognitionHistoryService history,RtspFrameSource rtsp) {
        this.faces=faces;this.history=history;this.rtsp=rtsp;
        cleaner.scheduleWithFixedDelay(()->expire(System.currentTimeMillis()),1,1,TimeUnit.SECONDS);
    }
    public Map<String,Object> capabilities() {
        Map<String,Object> result=new LinkedHashMap<>();result.put("usb",true);result.put("rtsp",rtsp.available());result.put("maxSessions",4);result.put("targetFps",2);result.put("usbTargetFps",10);result.put("rtspTargetFps",2);result.put("historyIntervalSeconds",10);return result;
    }
    // 创建会话时固定来源和角色；RTSP地址只交给采集任务，不放入返回快照或历史。
    public synchronized Map<String,Object> start(String source,String label,String role,String url) {
        if(closed)throw new FaceBusyException("服务正在关闭");
        if(!Arrays.asList("USB","RTSP").contains(source))throw new IllegalArgumentException("请选择 USB 或 RTSP 摄像头");
        if(!FaceRoles.isSupported(role))throw new IllegalArgumentException("不受支持的人员角色");
        if(label==null||label.trim().isEmpty())label="USB".equals(source)?"USB 摄像头":"RTSP 摄像头";
        label=label.trim();if(label.length()>64||label.contains("://"))throw new IllegalArgumentException("摄像头名称最多 64 字符，请勿填写连接地址");
        if("RTSP".equals(source)){validateRtsp(url);if(!rtsp.available())throw new IllegalArgumentException("未找到 FFmpeg，请安装到服务器 PATH 或设置 face.ffmpeg-path");}
        if(!faces.isAvailable())throw new FaceBusyException("识别引擎不可用，请先恢复引擎");
        if(sessions.values().stream().filter(s->!s.ended.get()).count()>=4)throw new FaceBusyException("实时摄像头会话已达上限（4 路）");
        while(sessions.size()>=16){Iterator<Session> it=sessions.values().iterator();while(it.hasNext()){Session s=it.next();if(s.ended.get()){it.remove();break;}}}
        Session session=new Session(source,label,role);
        history.startCamera(session.id,source,label,role);
        sessions.put(session.id,session);
        if("RTSP".equals(source)){
            try { workers.submit(()->capture(session,url));workers.submit(()->recognizeLatest(session)); }
            catch(RejectedExecutionException e){finish(session,"FAILED","实时识别服务已停止");throw new FaceBusyException("实时识别服务已停止");}
        }
        return snapshot(session,-1);
    }
    public static void validateRtsp(String value) {
        try {
            if(value==null||value.length()>2048||value.indexOf('\n')>=0||value.indexOf('\r')>=0)throw new Exception();
            URI uri=new URI(value);
            if(!"rtsp".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getHost().isEmpty()||uri.getFragment()!=null||uri.getPort()==0||uri.getPort()>65535)throw new Exception();
        } catch(Exception e) { throw new IllegalArgumentException("请输入有效的 rtsp:// 摄像头地址；特殊字符请按 URL 编码"); }
    }
    private void capture(Session s,String url) {
        if(s.ended.get())return;
        try(RtspFrameSource.Capture capture=rtsp.open(url)) {
            s.capture=capture;if(s.ended.get())return;
            while(!s.ended.get()) {
                byte[] jpeg=capture.next();
                if(jpeg==null)throw new IOException("断流");
                if(s.ended.get())break;
                s.lastCapture=System.currentTimeMillis();
                Frame frame=new Frame(jpeg,s.lastCapture);
                // 长度为1的队列丢弃尚未处理的旧帧，推理慢时优先识别最新画面。
                s.queue.poll();s.queue.offer(frame);
            }
        } catch(Exception e) { if(!s.ended.get())finish(s,"FAILED","RTSP 连接失败或画面中断，请检查地址、账号密码、网络及视频编码"); }
        finally { s.capture=null; }
    }
    private void recognizeLatest(Session s) {
        try {
            while(!s.ended.get()) {
                Frame frame=s.queue.poll(1,TimeUnit.SECONDS);
                if(frame!=null)process(s,frame);
            }
        } catch(InterruptedException e){Thread.currentThread().interrupt();}
        catch(Exception e){if(!s.ended.get())finish(s,"FAILED","摄像头识别失败，请检查引擎状态和历史数据库");}
    }
    // USB由浏览器串行上传当前帧；同一会话不接受并发推理，防止结果倒序和旧帧积压。
    public Map<String,Object> usbFrame(String id,byte[] image) {
        Session s=get(id);s.lastSeen=System.currentTimeMillis();
        if(!"USB".equals(s.source))throw new IllegalArgumentException("此会话不接收 USB 画面");
        if(s.ended.get())throw new IllegalArgumentException("摄像头会话已停止，请重新连接");
        if(!s.processing.compareAndSet(false,true))throw new FaceBusyException("当前画面仍在识别，请等待结果");
        try {
            if(System.currentTimeMillis()-s.lastSubmitted<80)throw new FaceBusyException("画面提交过快，请降低识别频率");
            s.lastSubmitted=System.currentTimeMillis();process(s,new Frame(image,s.lastSubmitted));return snapshot(s,-1);
        } catch(IllegalArgumentException | FaceBusyException e){throw e;}
        catch(Exception e){finish(s,"FAILED","实时识别失败，请检查引擎状态和历史数据库");throw new IllegalArgumentException("实时识别失败，请重新连接摄像头");}
        finally{s.processing.set(false);}
    }
    private void process(Session s,Frame frame) throws IOException {
        if(s.ended.get())return;
        long started=System.nanoTime();
        int[] size=dimensions(frame.bytes);
        List<VideoFaceResult> results=faces.recognizeFrame(frame.bytes,s.role);
        long now=System.currentTimeMillis();
        synchronized(s) {
            if(s.ended.get())return;
            long sequence=s.sequence+1;
            // 身份确认与位置更新分开：框每帧更新，姓名及匹配历史需连续确认。
            s.stabilizer.update(results,now);
            boolean previewDue=now-s.lastThumbnail>=500;
            BufferedImage image=null;
            for(VideoFaceResult result:results) {
                result.frame=sequence-1;result.timeSeconds=(frame.time-s.started)/1000.0;
                // 已知人员按role/code限频；未知人脸仅按画面分区采样，不把分区当作真实人员身份。
                String key=result.match!=null?result.match.getFaceRole()+":"+result.match.getCode():result.status+":"+(result.box.getLeft()*8/size[0])+":"+(result.box.getTop()*6/size[1]);
                boolean recordDue=now-s.recorded.getOrDefault(key,0L)>=10000&&!"RECHECK".equals(result.displayState)&&(result.match==null||Boolean.TRUE.equals(result.confirmed));
                result.thumbnail=null;result.enrollmentImage=null;
                // 完整解码、缩略图和录入裁剪按需生成；录入图只给未匹配且未处于复核状态的人脸。
                if(previewDue||recordDue){if(image==null)image=ImageIO.read(new ByteArrayInputStream(frame.bytes));result.thumbnail=VideoRecognitionService.thumbnail(image,result.box);if("UNKNOWN".equals(result.status)&&!"RECHECK".equals(result.displayState))result.enrollmentImage=VideoRecognitionService.enrollmentImage(image,result.box);}
                if(recordDue){history.cameraFace(s.id,s.source,s.label,s.role,result);s.recorded.put(key,now);s.historyEvents++;}
                if(Boolean.TRUE.equals(result.confirmed))s.matched++;
            }
            if(previewDue)s.lastThumbnail=now;
            s.totalFaces+=results.size();s.sequence=sequence;s.width=size[0];s.height=size[1];s.results=results;
            s.image="RTSP".equals(s.source)?"data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(frame.bytes):null;
            s.timestamp=Instant.ofEpochMilli(frame.time).toString();s.state="RUNNING";s.lastProcessed=now;s.processingMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
        }
    }
    // 先读图片头限制像素，避免为了检查尺寸而额外解码整张摄像头帧。
    private static int[] dimensions(byte[] bytes) throws IOException {
        if(bytes==null||bytes.length==0||bytes.length>4*1024*1024)throw new IllegalArgumentException("摄像头帧为空或超过 4 MB");
        try(ImageInputStream input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IllegalArgumentException("无效的摄像头图片");
            ImageReader reader=readers.next();try{reader.setInput(input);int width=reader.getWidth(0),height=reader.getHeight(0);if(width<1||height<1||(long)width*height>1920*1080)throw new IllegalArgumentException("实时画面不能超过 1920×1080 像素");return new int[]{width,height};}finally{reader.dispose();}
        }
    }
    public Map<String,Object> status(String id,long after){Session s=get(id);s.lastSeen=System.currentTimeMillis();return snapshot(s,after);}
    public Map<String,Object> stop(String id){Session s=get(id);finish(s,"STOPPED",null);return snapshot(s,-1);}
    private synchronized Session get(String id){Session s=sessions.get(id);if(s==null)throw new IllegalArgumentException("摄像头会话不存在或已过期，请重新连接");return s;}
    // 结束操作幂等：主动停止、看门狗和异常可能同时触发，只允许一条路径收尾。
    private void finish(Session s,String state,String error) {
        if(!s.ended.compareAndSet(false,true))return;
        RtspFrameSource.Capture capture=s.capture;if(capture!=null)capture.close();s.queue.clear();
        synchronized(s) {
            s.state=state;s.error=error;s.image=null;s.results=Collections.emptyList();s.finished=System.currentTimeMillis();
            try{history.finishCamera(s.id,state,error,s.totalFaces==0);}catch(RuntimeException e){s.state="FAILED";s.error="摄像头历史保存失败，请检查数据库";}
        }
    }
    /** 接收端定时检查租约和断流，不依赖浏览器成功发送关闭请求。 */
    public synchronized void expire(long now) {
        for(Session s:sessions.values())if(!s.ended.get()) {
            if(now-s.lastSeen>30000)finish(s,"STOPPED","页面长时间未响应，已自动释放摄像头会话");
            else if("RTSP".equals(s.source)&&now-s.lastCapture>12000)finish(s,"FAILED","RTSP 连接或读取画面超时，请检查摄像头网络后重连");
        }
        sessions.values().removeIf(s->s.ended.get()&&now-s.finished>300000);
    }
    // after是调用方已见序号；无新画面时只返回状态，避免反复传输同一张图片。
    private Map<String,Object> snapshot(Session s,long after) {
        synchronized(s) {
            Map<String,Object> m=new LinkedHashMap<>();m.put("id",s.id);m.put("source",s.source);m.put("name",s.label);m.put("state",s.state);m.put("error",s.error);
            m.put("sequence",s.sequence);m.put("timestamp",s.timestamp);m.put("width",s.width);m.put("height",s.height);m.put("totalFaces",s.totalFaces);m.put("matched",s.matched);m.put("historyEvents",s.historyEvents);
            m.put("processingMs",s.processingMs);
            if(s.sequence>after){m.put("faces",s.results);m.put("image",s.image);}return m;
        }
    }
    @PreDestroy public void close() {
        synchronized(this){closed=true;for(Session s:sessions.values())finish(s,"STOPPED","服务已关闭");}
        cleaner.shutdownNow();workers.shutdownNow();try{workers.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
    private static final class Frame {final byte[] bytes;final long time;Frame(byte[] bytes,long time){this.bytes=bytes;this.time=time;}}
    private static final class Session {
        final String id=UUID.randomUUID().toString(),source,label,role;
        final long started=System.currentTimeMillis();
        final AtomicBoolean ended=new AtomicBoolean(),processing=new AtomicBoolean();
        final LiveMatchStabilizer stabilizer=new LiveMatchStabilizer();
        // 仅缓存一帧；录入的新档案由FaceService读取，无需把底库复制进会话。
        final BlockingQueue<Frame> queue=new ArrayBlockingQueue<>(1);
        final Map<String,Long> recorded=new LinkedHashMap<String,Long>(){@Override protected boolean removeEldestEntry(Map.Entry<String,Long> e){return size()>4096;}};
        volatile long lastSeen=started,lastCapture=started,lastSubmitted,finished;
        volatile RtspFrameSource.Capture capture;
        long sequence,totalFaces,matched,historyEvents,lastProcessed,lastThumbnail,processingMs;int width,height;
        String state,timestamp,error,image;List<VideoFaceResult> results=Collections.emptyList();
        Session(String source,String label,String role){this.source=source;this.label=label;this.role=role;state="RTSP".equals(source)?"CONNECTING":"RUNNING";}
    }
}
