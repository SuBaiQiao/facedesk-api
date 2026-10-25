package com.face.api;

import com.face.api.engine.FaceBusyException;
import com.face.api.model.*;
import com.face.api.service.*;
import org.junit.jupiter.api.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiveRecognitionServiceTest {
    FaceService faces;RecognitionHistoryService history;RtspFrameSource rtsp;LiveRecognitionService live;byte[] image;
    @BeforeEach void setup()throws Exception{
        faces=mock(FaceService.class);history=mock(RecognitionHistoryService.class);rtsp=mock(RtspFrameSource.class);
        when(faces.isAvailable()).thenReturn(true);when(rtsp.available()).thenReturn(true);
        ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(160,120,BufferedImage.TYPE_INT_RGB),"jpg",out);image=out.toByteArray();
        live=new LiveRecognitionService(faces,history,rtsp);
    }
    @AfterEach void close(){live.close();}
    String usb(){return (String)live.start("USB","门口摄像头","",null).get("id");}
    @Test void usbRecognizesMultipleFacesAndThrottlesHistoricalDuplicates()throws Exception{
        when(faces.recognizeFrame(any(),eq(""))).thenAnswer(call->Arrays.asList(
            new VideoFaceResult(new FaceBox(1,2,105,110),"MATCHED",new RecognitionResult("Guest","001","张三",.9,null)),
            new VideoFaceResult(new FaceBox(110,2,155,80),"UNKNOWN",null)));
        String id=usb();Map<String,Object> first=live.usbFrame(id,image);
        assertEquals(1L,first.get("sequence"));assertEquals(2L,first.get("totalFaces"));assertEquals(0L,first.get("matched"));assertEquals(1L,first.get("historyEvents"));assertEquals(160,first.get("width"));assertNull(first.get("image"));
        List<?> captures=(List<?>)first.get("faces");assertNull(((VideoFaceResult)captures.get(0)).enrollmentImage);assertNotNull(((VideoFaceResult)captures.get(1)).enrollmentImage);
        assertFalse(live.status(id,1).containsKey("faces"));
        Thread.sleep(110);Map<String,Object> second=live.usbFrame(id,image);assertEquals(2L,second.get("sequence"));assertEquals(1L,second.get("historyEvents"));
        assertEquals(2,((List<?>)second.get("faces")).size());assertNotNull(second.get("processingMs"));
        Thread.sleep(110);Map<String,Object> third=live.usbFrame(id,image);assertEquals(1L,third.get("matched"));assertEquals(2L,third.get("historyEvents"));
        verify(history,times(2)).cameraFace(eq(id),eq("USB"),eq("门口摄像头"),eq(""),any());
        assertEquals("STOPPED",live.stop(id).get("state"));live.stop(id);
        verify(history,times(1)).finishCamera(id,"STOPPED",null,false);
        assertThrows(IllegalArgumentException.class,()->live.usbFrame(id,image));
    }
    @Test void limitsSessionsValidatesFramesAndExpiresAbandonedClients(){
        String id=usb();for(int i=0;i<3;i++)usb();assertThrows(FaceBusyException.class,this::usb);
        assertThrows(IllegalArgumentException.class,()->live.usbFrame(id,new byte[]{1,2,3}));
        live.expire(System.currentTimeMillis()+31000);assertEquals("STOPPED",live.status(id,-1).get("state"));
        assertNotNull(usb());
    }
    @Test void onlyAcceptsRtspUrlsAndDoesNotEchoCredentials()throws Exception{
        for(String value:Arrays.asList("http://localhost/a","file:///secret","rtsp://host:0/a","rtsp://host/a\nheader","rtsp:///missing"))assertThrows(IllegalArgumentException.class,()->LiveRecognitionService.validateRtsp(value));
        LiveRecognitionService.validateRtsp("rtsp://user:p%40ss@127.0.0.1:8554/camera");
        RtspFrameSource.Capture source=mock(RtspFrameSource.Capture.class);when(rtsp.open(anyString())).thenReturn(source);
        when(source.next()).thenThrow(new IOException("rtsp://secret:password@host/camera"));
        String id=(String)live.start("RTSP","入口","","rtsp://secret:password@host/camera").get("id");
        Map<String,Object> status=await(id,"FAILED");assertFalse(status.toString().contains("password"));assertFalse(status.toString().contains("secret"));
        verify(source,atLeastOnce()).close();
        when(rtsp.available()).thenReturn(false);assertThrows(IllegalArgumentException.class,()->live.start("RTSP","入口","","rtsp://host/a"));
    }
    @Test void rtspConsumesLatestFrameWithoutAnUnboundedQueue()throws Exception{
        BlockingQueue<byte[]> inputs=new LinkedBlockingQueue<>();CountDownLatch recognized=new CountDownLatch(1),release=new CountDownLatch(1),captured=new CountDownLatch(1);AtomicInteger reads=new AtomicInteger();
        RtspFrameSource.Capture source=mock(RtspFrameSource.Capture.class);when(rtsp.open(anyString())).thenReturn(source);
        when(source.next()).thenAnswer(call->{if(reads.incrementAndGet()==9)captured.countDown();byte[] next=inputs.take();if(next.length==0)return null;return next;});
        doAnswer(call->{inputs.offer(new byte[0]);return null;}).when(source).close();
        AtomicInteger calls=new AtomicInteger();when(faces.recognizeFrame(any(),anyString())).thenAnswer(call->{if(calls.incrementAndGet()==1){recognized.countDown();release.await(3,TimeUnit.SECONDS);}return Collections.emptyList();});
        String id=(String)live.start("RTSP","测试流","","rtsp://127.0.0.1:8554/test").get("id");
        inputs.offer(image);assertTrue(recognized.await(2,TimeUnit.SECONDS));for(int i=0;i<7;i++)inputs.offer(image);assertTrue(captured.await(2,TimeUnit.SECONDS));
        release.countDown();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);Map<String,Object> status;
        do{status=live.status(id,-1);if(((Number)status.get("sequence")).intValue()>=2)break;Thread.sleep(10);}while(System.nanoTime()<end);
        assertEquals(2L,status.get("sequence"));assertTrue(String.valueOf(status.get("image")).startsWith("data:image/jpeg;base64,"));assertEquals(2,calls.get());live.stop(id);
    }
    @Test void rtspWatchdogStopsStalledReaders()throws Exception{
        CountDownLatch closed=new CountDownLatch(1);RtspFrameSource.Capture source=mock(RtspFrameSource.Capture.class);when(rtsp.open(anyString())).thenReturn(source);
        when(source.next()).thenAnswer(call->{closed.await(3,TimeUnit.SECONDS);return null;});doAnswer(call->{closed.countDown();return null;}).when(source).close();
        String id=(String)live.start("RTSP","测试流","","rtsp://127.0.0.1:8554/test").get("id");
        live.expire(System.currentTimeMillis()+13000);assertEquals("FAILED",live.status(id,-1).get("state"));
    }
    Map<String,Object> await(String id,String state)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);Map<String,Object> result;
        do{result=live.status(id,-1);if(result.get("state").equals(state))return result;Thread.sleep(10);}while(System.nanoTime()<end);fail("Missing state "+state);return result;
    }
}
