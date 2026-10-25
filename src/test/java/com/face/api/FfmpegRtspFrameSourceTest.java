package com.face.api;

import com.face.api.service.FfmpegRtspFrameSource;
import org.junit.jupiter.api.Test;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class FfmpegRtspFrameSourceTest {
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="face.ffmpeg.integration",matches="true")
    void realFfmpegPipeProducesBoundedReadableJpegs()throws Exception{
        com.face.api.config.FaceProperties config=new com.face.api.config.FaceProperties();
        assertTrue(new FfmpegRtspFrameSource(config).available(),"FFmpeg must be installed for this explicit integration check");
        Process process=new ProcessBuilder("ffmpeg","-hide_banner","-loglevel","error","-nostdin","-f","lavfi","-i","color=c=blue:s=160x120:r=2","-frames:v","2","-threads","1","-c:v","mjpeg","-f","image2pipe","pipe:1").redirectError(ProcessBuilder.Redirect.DISCARD).start();
        java.util.concurrent.ScheduledExecutorService watchdog=java.util.concurrent.Executors.newSingleThreadScheduledExecutor();watchdog.schedule(process::destroyForcibly,5,java.util.concurrent.TimeUnit.SECONDS);
        try(InputStream input=new BufferedInputStream(process.getInputStream())){
            for(int i=0;i<2;i++){byte[] jpeg=FfmpegRtspFrameSource.readJpeg(input,1024*1024);assertNotNull(jpeg);java.awt.image.BufferedImage frame=javax.imageio.ImageIO.read(new ByteArrayInputStream(jpeg));assertEquals(160,frame.getWidth());assertEquals(120,frame.getHeight());}
            assertNull(FfmpegRtspFrameSource.readJpeg(input,1024*1024));assertTrue(process.waitFor(3,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue());
        }finally{process.destroyForcibly();watchdog.shutdownNow();}
    }
    @Test void splitsJpegFramesAndBoundsMalformedInput()throws Exception{
        byte[] jpeg={(byte)255,(byte)216,1,2,3,(byte)255,(byte)217};
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(new byte[]{4,5,6});out.write(jpeg);out.write(jpeg);
        InputStream in=new ByteArrayInputStream(out.toByteArray());assertArrayEquals(jpeg,FfmpegRtspFrameSource.readJpeg(in,100));assertArrayEquals(jpeg,FfmpegRtspFrameSource.readJpeg(in,100));assertNull(FfmpegRtspFrameSource.readJpeg(in,100));
        assertThrows(IOException.class,()->FfmpegRtspFrameSource.readJpeg(new ByteArrayInputStream(jpeg),4));
        assertThrows(EOFException.class,()->FfmpegRtspFrameSource.readJpeg(new ByteArrayInputStream(new byte[]{(byte)255,(byte)216,1}),100));
    }
}
