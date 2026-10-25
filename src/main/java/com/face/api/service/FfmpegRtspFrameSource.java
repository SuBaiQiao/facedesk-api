package com.face.api.service;

import com.face.api.config.FaceProperties;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 子进程以独立参数启动，不经过 shell；不记录含凭据的地址或 stderr。 */
@Component
public class FfmpegRtspFrameSource implements RtspFrameSource {
    private final FaceProperties config;
    private long checkedAt;
    private boolean available;
    public FfmpegRtspFrameSource(FaceProperties config) { this.config=config; }
    private String executable() {
        String configured=config.getFfmpegPath();
        if(configured==null||configured.trim().isEmpty())return "ffmpeg";
        Path bundled=Paths.get("tools",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"ffmpeg.exe":"ffmpeg");
        return "ffmpeg".equals(configured)&&Files.isRegularFile(bundled)?bundled.toAbsolutePath().toString():configured;
    }
    // 短期缓存可执行文件探测结果，避免页面轮询不断启动FFmpeg子进程。
    @Override public synchronized boolean available() {
        if(System.currentTimeMillis()-checkedAt<30000)return available;
        Process process=null;
        try {
            process=new ProcessBuilder(executable(),"-version").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            available=process.waitFor(3,TimeUnit.SECONDS)&&process.exitValue()==0;
        } catch(IOException e) { available=false; } catch(InterruptedException e) { Thread.currentThread().interrupt(); available=false; }
        finally { if(process!=null&&process.isAlive())process.destroyForcibly();checkedAt=System.currentTimeMillis(); }
        return available;
    }
    // 参数列表直接交给ProcessBuilder；地址作为一个参数传递，包含凭据的stderr不向页面暴露。
    @Override public Capture open(String url) throws IOException {
        // TCP and finite I/O timeout. The session watchdog also kills stalled processes.
        List<String> args=Arrays.asList(executable(),"-hide_banner","-loglevel","error","-nostdin","-rtsp_transport","tcp",
            "-timeout","5000000","-rw_timeout","5000000","-i",url,"-map","0:v:0","-an","-sn","-dn",
            "-vf","fps=2,scale=1280:720:force_original_aspect_ratio=decrease","-threads","1","-q:v","4","-c:v","mjpeg","-f","image2pipe","pipe:1");
        Process process;
        try { process=new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start(); }
        catch(IOException e) { throw new IOException("无法启动 RTSP 解码器，请检查 FFmpeg 配置"); }
        process.getOutputStream().close();
        return new Capture() {
            final InputStream input=new BufferedInputStream(process.getInputStream(),65536);
            final AtomicBoolean closed=new AtomicBoolean();
            @Override public byte[] next() throws IOException { return readJpeg(input,4*1024*1024); }
            @Override public void close() {
                if(!closed.compareAndSet(false,true))return;
                process.destroyForcibly();
                try{input.close();}catch(IOException ignored){}
            }
        };
    }
    /** 有界 MJPEG 分帧，避免损坏码流无限增长。 */
    // MJPEG管道没有HTTP边界，按JPEG起止标记切帧，并限制单帧字节数防止无限读入。
    public static byte[] readJpeg(InputStream input,int limit) throws IOException {
        ByteArrayOutputStream bytes=null;int previous=-1,value;
        while((value=input.read())!=-1) {
            if(bytes==null) {
                if(previous==0xff&&value==0xd8){bytes=new ByteArrayOutputStream();bytes.write(0xff);bytes.write(0xd8);}
            } else {
                bytes.write(value);
                if(bytes.size()>limit)throw new IOException("摄像头帧大小超过限制");
                if(previous==0xff&&value==0xd9)return bytes.toByteArray();
            }
            previous=value;
        }
        if(bytes!=null)throw new EOFException("摄像头画面不完整");
        return null;
    }
}
