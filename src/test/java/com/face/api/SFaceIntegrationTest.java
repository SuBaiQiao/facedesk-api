package com.face.api;

import com.face.api.config.*;
import com.face.api.engine.*;
import com.face.api.model.*;
import com.face.api.repository.SqliteFaceRepository;
import com.face.api.service.FaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 显式启用；只读本地模型和已准备好的样例，不在测试或服务中联网。 */
@EnabledIfSystemProperty(named = "face.integration", matches = "true")
class SFaceIntegrationTest {
    static SFaceEngine engine;
    static FaceProperties config;
    static byte[] portrait;
    @BeforeAll static void start() throws Exception {
        config = new FaceProperties(); if (Boolean.getBoolean("face.integration.aura")) { config.setRecognitionBackend("auraface");config.setRecognizerModel("face_recognition_auraface_v1.onnx");config.setWorkers(1);config.setInferenceThreads(4); } engine = new SFaceEngine(config); engine.initialize();
        portrait = Files.readAllBytes(Paths.get("target/test-images/lena.jpg"));
    }
    @AfterAll static void stop() { if (engine != null) engine.close(); }
    byte[] encode(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); return out.toByteArray();
    }
    @Test void realDetectionAlignmentRecognitionAndRejection() throws Exception {
        FaceSample sample = engine.analyze(portrait);
        assertEquals("auraface".equals(config.getRecognitionBackend()) ? 512 : 128, sample.getFeature().length);
        assertEquals(1, engine.compare(sample.getFeature(), sample.getFeature()), 1e-6);
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(portrait));
        BufferedImage resized = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics(); g.drawImage(source, 0, 0, 400, 400, null); g.dispose();
        double score = engine.compare(sample.getFeature(), engine.extractFeature(encode(resized)));
        assertTrue(score >= config.getThreshold(), "同图缩放应匹配: " + score);
        BufferedImage group = new BufferedImage(640, 640, BufferedImage.TYPE_INT_RGB);
        BufferedImage head = source.getSubimage(150, 150, 300, 300);
        g = group.createGraphics(); g.drawImage(head, 0, 150, null); g.drawImage(head, 330, 150, null); g.dispose();
        assertEquals(2, engine.detectFaces(encode(group)).size());
        assertThrows(FaceEngineException.class, () -> engine.analyze(encode(group)));
        byte[] blank = encode(new BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB));
        assertTrue(engine.detectFaces(blank).isEmpty());
        assertThrows(FaceEngineException.class, () -> engine.analyze(blank));
        assertThrows(FaceEngineException.class, () -> engine.analyze(new byte[] {1, 2, 3}));
        System.out.println("REAL_MODEL resized_same_person_cosine=" + score);
    }
    @Test void realMultiFaceVideoProcessesAllFrames() throws Exception {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(portrait));
        BufferedImage group = new BufferedImage(640,640,BufferedImage.TYPE_INT_RGB);
        Graphics2D g = group.createGraphics(); BufferedImage head=source.getSubimage(150,150,300,300);
        g.drawImage(head,0,150,null); g.drawImage(head,330,150,null); g.dispose();
        byte[] groupBytes=encode(group);
        assertEquals(2,engine.analyzeAll(groupBytes).size());
        FaceRecord known=new FaceRecord("Guest","VIDEO-FIXTURE","测试人员",null,engine.extractFeature(portrait));
        known.setFeatureSpaceId(engine.featureSpaceId());
        com.face.api.repository.FaceRepository repo=mock(com.face.api.repository.FaceRepository.class);
        when(repo.findByRole("")).thenReturn(Collections.singletonList(known));
        ThreadPoolTaskExecutor inference=new AppConfiguration().faceTaskExecutor(config); inference.initialize();
        FaceService service=new FaceService(engine,repo,config,inference);
        com.face.api.service.VideoRecognitionService videos=new com.face.api.service.VideoRecognitionService(service,new com.face.api.service.VideoDecoder(config),new ObjectMapper());
        Path path=Paths.get("target/test-images/two-faces.avi");
        org.opencv.videoio.VideoWriter writer=new org.opencv.videoio.VideoWriter(path.toString(),org.opencv.videoio.VideoWriter.fourcc('M','J','P','G'),5,new org.opencv.core.Size(640,640));
        org.opencv.core.MatOfByte bytes=new org.opencv.core.MatOfByte(groupBytes);
        org.opencv.core.Mat frame=org.opencv.imgcodecs.Imgcodecs.imdecode(bytes,org.opencv.imgcodecs.Imgcodecs.IMREAD_COLOR);
        org.opencv.core.Mat blank=org.opencv.core.Mat.zeros(640,640,org.opencv.core.CvType.CV_8UC3);
        try { assertTrue(writer.isOpened()); writer.write(frame); writer.write(blank); writer.write(frame); }
        finally { writer.release(); bytes.release(); frame.release(); blank.release(); }
        try {
            String id=(String)videos.start(new org.springframework.mock.web.MockMultipartFile("video","two-faces.avi","video/x-msvideo",Files.readAllBytes(path)),"").get("id");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30); Map<String,Object> status;
            do { status=videos.status(id); if(!status.get("state").equals("RUNNING"))break; Thread.sleep(20); } while(System.nanoTime()<deadline);
            assertEquals("COMPLETED",status.get("state"),String.valueOf(status.get("error")));
            assertEquals(3L,status.get("processedFrames")); assertEquals(4,status.get("total")); assertEquals(4L,status.get("matched")); assertEquals(1,status.get("people"));
            System.out.println("REAL_VIDEO all_frames=3 face_occurrences=4 matched=4 unique_known_people=1 codec=MJPEG_AVI");
        } finally { videos.close(); inference.shutdown(); }
    }
    @Test void enrollsAdditionalReferenceWithRealModelAndPersistsItsVector() throws Exception {
        Path dir=Files.createTempDirectory(Paths.get("target"),"real-references-");SqliteFaceRepository repo=new SqliteFaceRepository(dir.resolve("face.db").toString(),engine.featureSpaceId());
        ThreadPoolTaskExecutor inference=new AppConfiguration().faceTaskExecutor(config);inference.initialize();FaceService service=new FaceService(engine,repo,config,inference);
        try{service.collect(portrait,"Guest","R1","Fixture","image/jpeg");assertThrows(IllegalArgumentException.class,()->service.addReference(portrait,"Guest","R1"));BufferedImage source=ImageIO.read(new ByteArrayInputStream(portrait)),variant=new BufferedImage(400,400,BufferedImage.TYPE_INT_RGB);Graphics2D g=variant.createGraphics();g.drawImage(source,0,0,400,400,null);g.dispose();byte[] image=encode(variant);Files.write(Paths.get("target/test-images/reference-variant.png"),image);service.addReference(image,"Guest","R1");assertEquals(2,repo.detail("Guest","R1").get("sampleCount"));assertEquals("R1",service.recognize(image,"Guest",null,0).get(0).getCode());repo.close();repo=new SqliteFaceRepository(dir.resolve("face.db").toString(),engine.featureSpaceId());assertEquals(1,repo.find("Guest","R1").get().getReferenceFeatures().size());}finally{inference.shutdown();repo.close();}
    }
    @Test void usbTrackingCadenceWithRealModelAndSqliteHistory() throws Exception {
        FaceRecord known=new FaceRecord("Guest","USB-CADENCE","Fixture",null,engine.extractFeature(portrait));known.setFeatureSpaceId(engine.featureSpaceId());
        com.face.api.repository.FaceRepository repo=mock(com.face.api.repository.FaceRepository.class);when(repo.findByRole("")).thenReturn(Collections.singletonList(known));
        Path dir=Files.createTempDirectory(Paths.get("target"),"usb-cadence-");
        com.face.api.repository.RecognitionHistoryRepository records=new com.face.api.repository.RecognitionHistoryRepository(dir.resolve("face.db").toString(),new ObjectMapper());
        com.face.api.service.RecognitionHistoryService history=new com.face.api.service.RecognitionHistoryService(records,config);
        ThreadPoolTaskExecutor inference=new AppConfiguration().faceTaskExecutor(config);inference.initialize();
        FaceService service=new FaceService(engine,repo,config,inference,history);
        com.face.api.service.LiveRecognitionService live=new com.face.api.service.LiveRecognitionService(service,history,mock(com.face.api.service.RtspFrameSource.class));
        try {
            String id=(String)live.start("USB","Cadence fixture","",null).get("id");live.usbFrame(id,portrait);Thread.sleep(100);
            List<Long> timings=new ArrayList<>();long start=System.nanoTime();Map<String,Object> result=null;int previews=0;
            for(int i=0;i<12;i++) {
                long frameStart=System.nanoTime();result=live.usbFrame(id,portrait);
                List<?> faces=(List<?>)result.get("faces");assertEquals(1,faces.size());assertEquals("MATCHED",((VideoFaceResult)faces.get(0)).status);
                if(((VideoFaceResult)faces.get(0)).thumbnail!=null)previews++;
                timings.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-frameStart));
                long remaining=100-TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-frameStart);if(remaining>0)Thread.sleep(remaining);
            }
            double seconds=(System.nanoTime()-start)/1e9;Collections.sort(timings);
            assertEquals(13L,result.get("sequence"));assertEquals(1L,result.get("historyEvents"));assertTrue(previews<12,"Tracking frames should not all regenerate thumbnails");
            Map<String,Object> report=new LinkedHashMap<>();report.put("scope","service-layer USB cadence; 512x512 local fixture, real model and SQLite; excludes browser, JPEG capture and HTTP");
            report.put("frames",12);report.put("elapsedSeconds",seconds);report.put("framesPerSecond",12/seconds);report.put("p50Ms",timings.get(5));report.put("maxMs",timings.get(11));report.put("previewFrames",previews);report.put("historyEvents",result.get("historyEvents"));
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new File("target/usb-cadence-report.json"),report);System.out.println("USB_CADENCE "+new ObjectMapper().writeValueAsString(report));
        } finally {live.close();inference.shutdown();records.close();}
    }
    @Test @EnabledIfSystemProperty(named = "face.load", matches = "true")
    void twoThousandVectorsAndTwoHundredFiftyConcurrentRequests() throws Exception {
        float[] target = engine.extractFeature(portrait);
        List<FaceRecord> records = new ArrayList<>();
        Random random = new Random(42);
        for (int i = 0; i < 2000; i++) {
            float[] feature = new float[target.length];
            for (int j = 0; j < target.length; j++) feature[j] = (float) random.nextGaussian();
            feature = i == 0 ? target : FeatureMath.normalize(feature);
            FaceRecord r = new FaceRecord("Guest", "P" + i, null, null, feature);
            r.setFeatureSpaceId(engine.featureSpaceId()); records.add(r);
        }
        Path directory = Files.createTempDirectory(Paths.get("target"), "sqlite-load-");
        Path source = directory.resolve("fixture-import.json");
        new ObjectMapper().writeValue(source.toFile(), records);
        SqliteFaceRepository repo = new SqliteFaceRepository(directory.resolve("face.db").toString(), engine.featureSpaceId());
        repo.importJson(source);
        Files.delete(source);
        ThreadPoolTaskExecutor inference = new AppConfiguration().faceTaskExecutor(config); inference.initialize();
        FaceService service = new FaceService(engine, repo, config, inference);
        ExecutorService clients = Executors.newFixedThreadPool(250);
        CountDownLatch ready = new CountDownLatch(250), go = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) service.recognize(portrait, "Guest", null, 0);
            for (int i = 0; i < 250; i++) futures.add(clients.submit(() -> {
                ready.countDown(); go.await(); long start = System.nanoTime();
                List<RecognitionResult> result = service.recognize(portrait, "Guest", null, 0);
                assertEquals(1, result.size()); assertEquals("P0", result.get(0).getCode());
                return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            }));
            assertTrue(ready.await(20, TimeUnit.SECONDS)); long start = System.nanoTime(); go.countDown();
            List<Long> timings = new ArrayList<>();
            for (Future<Long> future : futures) timings.add(future.get(120, TimeUnit.SECONDS));
            double seconds = (System.nanoTime() - start) / 1e9; Collections.sort(timings);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("scope", "service-layer real inference, same local portrait, 1999 synthetic distractor vectors; excludes HTTP and accuracy evaluation");
            report.put("concurrency", 250); report.put("databaseSize", 2000); report.put("successes", timings.size());
            report.put("databasePath", directory.resolve("face.db").toString());
            report.put("storage", "SQLite persisted; in-memory snapshot for recognition");
            report.put("workers", config.getWorkers()); report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
            report.put("javaVersion", System.getProperty("java.version")); report.put("os", System.getProperty("os.name"));
            report.put("elapsedSeconds", seconds); report.put("throughputRequestsPerSecond", 250 / seconds);
            report.put("p50Ms", timings.get(124)); report.put("p95Ms", timings.get(237)); report.put("p99Ms", timings.get(247));
            report.put("maxMs", timings.get(249)); report.put("featureSpaceId", engine.featureSpaceId());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(new File("target/sqlite-load-report.json"), report);
            System.out.println("LOAD_REPORT " + new ObjectMapper().writeValueAsString(report));
        } finally { go.countDown(); clients.shutdownNow(); inference.shutdown(); repo.close(); }
    }
}
