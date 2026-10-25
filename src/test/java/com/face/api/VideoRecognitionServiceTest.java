package com.face.api;

import com.face.api.engine.FaceBusyException;
import com.face.api.model.*;
import com.face.api.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoRecognitionServiceTest {
    FaceService faces; VideoDecoder decoder; VideoDecoder.Session session; VideoRecognitionService videos;
    RecognitionHistoryService history;
    byte[] image;
    @BeforeEach void setup() throws Exception {
        faces=mock(FaceService.class); decoder=mock(VideoDecoder.class); session=mock(VideoDecoder.Session.class);
        when(faces.isAvailable()).thenReturn(true); when(decoder.open(any())).thenReturn(session);
        ByteArrayOutputStream out=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(120,120,BufferedImage.TYPE_INT_RGB),"jpg",out); image=out.toByteArray();
        history=mock(RecognitionHistoryService.class);
        videos=new VideoRecognitionService(faces,decoder,new ObjectMapper(),history);
    }
    @AfterEach void close() { videos.close(); }
    MockMultipartFile file() { return new MockMultipartFile("video","demo.avi","video/x-msvideo",new byte[]{1,2,3}); }
    String start() throws Exception { return (String)videos.start(file(),"").get("id"); }
    Map<String,Object> await(String id) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end) { Map<String,Object> status=videos.status(id); if(!status.get("state").equals("RUNNING"))return status; Thread.sleep(10); }
        fail("Video job timed out"); return null;
    }
    @Test void keepsEveryOccurrencePagesCropsAndExportsUtf8() throws Exception {
        when(session.next()).thenReturn(image,image,null);
        when(faces.recognizeFrame(any(),eq(""))).thenAnswer(call->Arrays.asList(
            new VideoFaceResult(new FaceBox(1,2,110,115),"MATCHED",new RecognitionResult("Guest","=123","张三",.95,null)),
            new VideoFaceResult(new FaceBox(3,4,40,45),"TOO_SMALL",null)));
        String id=start(); Map<String,Object> status=await(id);
        assertEquals("COMPLETED",status.get("state")); assertEquals(2L,status.get("processedFrames"));
        assertEquals(4,status.get("total")); assertEquals(2L,status.get("matched")); assertEquals(1,status.get("people")); assertEquals(2L,status.get("tooSmall"));
        Map<String,Object> page=videos.page(id,2,3); List<?> items=(List<?>)page.get("items");
        assertEquals(1,items.size()); VideoFaceResult item=(VideoFaceResult)items.get(0);
        assertEquals(1,item.frame); assertTrue(item.thumbnail.startsWith("data:image/jpeg;base64,"));
        verify(history).startVideo(eq(id),eq("demo.avi"),eq(""));
        verify(history,times(4)).videoFace(eq(id),eq("demo.avi"),eq(""),any());
        String csv=new String(videos.export(id),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains("张三")); assertTrue(csv.contains("\"'=123\"")); assertEquals(5,csv.split("\r\n").length);
        assertThrows(IllegalArgumentException.class,()->videos.page(id,0,24));
        verify(session).close();
    }
    @Test void noFacesIsSuccessfulAndDecodeFailureIsExplicit() throws Exception {
        when(session.next()).thenReturn(image,(byte[])null); when(faces.recognizeFrame(any(),anyString())).thenReturn(Collections.emptyList());
        String id=start(); assertEquals("COMPLETED",await(id).get("state")); assertEquals(0,videos.status(id).get("total"));
        when(decoder.open(any())).thenThrow(new IllegalArgumentException("无法解码"));
        id=start(); Map<String,Object> failed=await(id); assertEquals("FAILED",failed.get("state")); assertEquals("无法解码",failed.get("error"));
    }
    @Test void rejectsConcurrentJobsAndCancelsBetweenFrames() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(session.next()).thenReturn(image);
        when(faces.recognizeFrame(any(),anyString())).thenAnswer(call->{ entered.countDown(); release.await(3,TimeUnit.SECONDS); return Collections.emptyList(); });
        String id=start(); assertTrue(entered.await(2,TimeUnit.SECONDS));
        try {
            assertThrows(FaceBusyException.class,()->videos.start(file(),"")); videos.cancel(id);
        } finally { release.countDown(); }
        assertEquals("CANCELLED",await(id).get("state")); verify(session,times(1)).next();
    }
    @Test void validatesUploadRoleEngineAndMissingJobs() {
        assertThrows(IllegalArgumentException.class,()->videos.start(new MockMultipartFile("video",new byte[0]),""));
        assertThrows(IllegalArgumentException.class,()->videos.start(file(),"invalid"));
        when(faces.isAvailable()).thenReturn(false); assertThrows(FaceBusyException.class,()->videos.start(file(),""));
        assertThrows(IllegalArgumentException.class,()->videos.status("missing"));
    }
}
