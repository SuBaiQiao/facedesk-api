package com.face.api;

import com.face.api.config.FaceProperties;
import com.face.api.service.VideoDecoder;
import nu.pattern.OpenCV;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.opencv.core.*;
import org.opencv.videoio.VideoWriter;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class VideoDecoderTest {
    @TempDir Path dir;
    @BeforeAll static void load() { OpenCV.loadLocally(); }
    @Test void decodesEveryMjpegFrameAndRejectsInvalidVideo() throws Exception {
        Path path=dir.resolve("fixture.avi");
        VideoWriter writer=new VideoWriter(path.toString(),VideoWriter.fourcc('M','J','P','G'),5,new Size(320,240));
        Mat frame=new Mat(240,320,CvType.CV_8UC3,new Scalar(80,120,180));
        try { assertTrue(writer.isOpened(),"Bundled OpenCV must support MJPEG AVI"); for(int i=0;i<4;i++)writer.write(frame); }
        finally { writer.release(); frame.release(); }
        VideoDecoder decoder=new VideoDecoder(new FaceProperties());
        try(VideoDecoder.Session session=decoder.open(path)) {
            assertEquals(4,session.totalFrames); assertEquals(5,session.fps,.01);
            for(int i=0;i<4;i++) { assertNotNull(session.next()); assertEquals(i/5.0,session.timeSeconds(i),.05); }
            assertNull(session.next());
        }
        Path invalid=dir.resolve("invalid.mp4"); Files.write(invalid,new byte[]{1,2,3});
        assertThrows(IllegalArgumentException.class,()->decoder.open(invalid));
    }
    @Test void decodesLocalMp4WhenPlatformEncoderIsAvailable() throws Exception {
        Path path=dir.resolve("fixture.mp4");
        VideoWriter writer=new VideoWriter(path.toString(),VideoWriter.fourcc('m','p','4','v'),5,new Size(320,240));
        Mat frame=new Mat(240,320,CvType.CV_8UC3,new Scalar(80,120,180));
        boolean opened=writer.isOpened();
        try { if(opened)for(int i=0;i<3;i++)writer.write(frame); }
        finally { writer.release(); frame.release(); }
        System.out.println("VIDEO_MP4 platform_encoder="+opened);
        org.junit.jupiter.api.Assumptions.assumeTrue(opened,"Platform has no MP4 encoder; MJPEG AVI is always verified");
        try(VideoDecoder.Session session=new VideoDecoder(new FaceProperties()).open(path)) {
            for(int i=0;i<3;i++)assertNotNull(session.next()); assertNull(session.next());
        }
    }
}
