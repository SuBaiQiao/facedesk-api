package com.face.api;

import com.face.api.config.*;
import com.face.api.engine.*;
import com.face.api.model.*;
import com.face.api.repository.*;
import com.face.api.service.FaceService;
import org.junit.jupiter.api.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FaceServiceTest {
    @Test void captureEnrollmentReextractsFeaturesAndUsesCreateOnlyStorage() {
        service.collectCapture(image,"Guest","NEW","Fixture","image/jpeg");
        verify(engine).analyze(image);verify(repo).saveNew(argThat(record->record.getCode().equals("NEW")&&record.getFeatureSpaceId().equals("test-model")));
        verify(repo,never()).save(any());
        when(engine.analyze(image)).thenThrow(new FaceEngineException("图片必须仅包含一张人脸"));
        assertThrows(FaceEngineException.class,()->service.collectCapture(image,"Guest","BAD","Fixture","image/jpeg"));
        verify(repo,times(1)).saveNew(any());
    }
    FaceEngine engine; FaceRepository repo; FaceProperties config;
    ThreadPoolTaskExecutor executor; FaceService service;
    final byte[] image = {1};
    @BeforeEach void setup() {
        engine = mock(FaceEngine.class); repo = mock(FaceRepository.class); config = new FaceProperties();
        executor = new AppConfiguration().faceTaskExecutor(config); executor.initialize();
        service = new FaceService(engine, repo, config, executor);
        when(engine.featureSpaceId()).thenReturn("test-model");
        when(engine.analyze(image)).thenReturn(new FaceSample(new FaceBox(0, 0, 100, 100), new float[128]));
    }
    @AfterEach void close() { executor.shutdown(); }
    FaceRecord candidate(String code, double score) {
        float[] feature = new float[128]; feature[0] = (float) score;
        FaceRecord r = new FaceRecord("Guest", code, null, null, feature); r.setFeatureSpaceId("test-model");
        when(engine.compare(any(), same(feature))).thenReturn(score); return r;
    }
    @Test void refusesAmbiguousMatchEvenWhenRunnerUpIsBelowThreshold() {
        List<FaceRecord> records = Arrays.asList(candidate("a", .58), candidate("b", .54));
        when(repo.findByRole("Guest")).thenReturn(records);
        assertTrue(service.recognize(image, "Guest", null, 0).isEmpty());
    }
    @Test void aggregatesReferenceScoresPerPersonBeforeApplyingRunnerUpMargin(){
        FaceRecord a=candidate("A",.4),b=candidate("B",.3);float[] reference=new float[128],another=new float[128];reference[0]=.9f;another[0]=.88f;a.getReferenceFeatures().add(reference);a.getReferenceFeatures().add(another);
        when(engine.compare(any(),same(reference))).thenReturn(.9);when(engine.compare(any(),same(another))).thenReturn(.88);when(repo.findByRole("Guest")).thenReturn(Arrays.asList(a,b));
        assertEquals("A",service.recognize(image,"Guest",null,0).get(0).getCode());
        when(engine.compare(any(),same(b.getFeature()))).thenReturn(.86);assertTrue(service.recognize(image,"Guest",null,0).isEmpty());
    }
    @Test void referenceEnrollmentRejectsUnrelatedFaces(){
        FaceRecord a=candidate("A",.2);when(repo.find("Guest","A")).thenReturn(Optional.of(a));assertThrows(IllegalArgumentException.class,()->service.addReference(image,"Guest","A"));verify(repo,never()).addReference(any());
        when(engine.compare(any(),same(a.getFeature()))).thenReturn(.9);service.addReference(image,"Guest","A");verify(repo).addReference(argThat(sample->sample.getCode().equals("A")&&sample.getFeatureSpaceId().equals("test-model")));
    }
    @Test void videoFramePreservesKnownUnknownAndSmallFaces() {
        float[] known = new float[128], unknown = new float[128];
        FaceBox box = new FaceBox(0,0,100,100);
        when(engine.analyzeAll(image)).thenReturn(Arrays.asList(new FaceSample(box,known),new FaceSample(box,unknown),new FaceSample(box,null)));
        FaceRecord record = new FaceRecord("Guest","P1","Person",null,new float[128]); record.setFeatureSpaceId("test-model");
        when(repo.findByRole("")).thenReturn(Collections.singletonList(record));
        when(engine.compare(same(known),same(record.getFeature()))).thenReturn(.9);
        when(engine.compare(same(unknown),same(record.getFeature()))).thenReturn(.2);
        List<VideoFaceResult> results = service.recognizeFrame(image,"");
        assertEquals(3,results.size()); assertEquals("MATCHED",results.get(0).status);
        assertEquals("P1",results.get(0).match.getCode());
        assertEquals("UNKNOWN",results.get(1).status); assertNull(results.get(1).match);
        assertEquals("TOO_SMALL",results.get(2).status); assertNull(results.get(2).match);
        verify(engine,never()).analyze(image);
    }
    @Test void photoHistoryCapturesKnownUnknownAndFailuresWithoutChangingSingleFaceRules() {
        com.face.api.service.RecognitionHistoryService history=mock(com.face.api.service.RecognitionHistoryService.class);
        service=new FaceService(engine,repo,config,executor,history);
        FaceRecord person=candidate("P001",.91);when(repo.findByRole("Guest")).thenReturn(Collections.singletonList(person));
        service.recognize(image,"Guest",null,0);
        verify(history).photo(eq(image),eq("Guest"),argThat(matches->matches.size()==1&&matches.get(0).getCode().equals("P001")),any(),anyLong(),eq("test-model"),eq(config.getThreshold()),isNull());
        when(repo.findByRole("Guest")).thenReturn(Collections.emptyList());service.recognize(image,"Guest",null,0);
        verify(history).photo(eq(image),eq("Guest"),argThat(List::isEmpty),any(),anyLong(),eq("test-model"),eq(config.getThreshold()),isNull());
        FaceEngineException failure=new FaceEngineException("未检测到人脸");when(engine.analyze(image)).thenThrow(failure);
        assertSame(failure,assertThrows(FaceEngineException.class,()->service.recognize(image,"Guest",null,0)));
        verify(history).photo(eq(image),eq("Guest"),argThat(List::isEmpty),isNull(),anyLong(),eq("test-model"),eq(config.getThreshold()),same(failure));
    }
    @Test void videoRefusesAmbiguousMatchesAndWrongModels() {
        FaceBox box = new FaceBox(0,0,100,100);
        when(engine.analyzeAll(image)).thenReturn(Collections.singletonList(new FaceSample(box,new float[128])));
        FaceRecord first = candidate("a",.8), second = candidate("b",.78);
        when(repo.findByRole("Guest")).thenReturn(Arrays.asList(first,second));
        assertEquals("UNKNOWN",service.recognizeFrame(image,"Guest").get(0).status);
        second.setFeatureSpaceId("old-model");
        assertThrows(FaceEngineException.class,()->service.recognizeFrame(image,"Guest"));
        assertThrows(IllegalArgumentException.class,()->service.recognizeFrame(image,"unsupported"));
    }
    @Test void returnsOnlyUniqueBestAndHonorsStricterClientThreshold() {
        List<FaceRecord> records = Arrays.asList(candidate("b", .35), candidate("a", .8));
        when(repo.findByRole("Guest")).thenReturn(records);
        assertEquals("a", service.recognize(image, "Guest", null, 0).get(0).getCode());
        assertTrue(service.recognize(image, "Guest", .9, 0).isEmpty());
    }
    @Test void clientCannotLowerServerThresholdOrSendNan() {
        FaceRecord record = candidate("a", .4);
        when(repo.findByRole("Guest")).thenReturn(Collections.singletonList(record));
        assertTrue(service.recognize(image, "Guest", .1, 0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.recognize(image, "Guest", Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> service.verifyOne(image, image, 1.1));
    }
    @Test void refusesWrongModelAndPropagatesMultiFaceRejection() {
        FaceRecord r = candidate("old", .99); r.setFeatureSpaceId("old-seeta");
        when(repo.findByRole("Guest")).thenReturn(Collections.singletonList(r));
        assertThrows(FaceEngineException.class, () -> service.recognize(image, "Guest", null, 0));
        when(engine.analyze(image)).thenThrow(new FaceEngineException("图片必须仅包含一张人脸"));
        assertThrows(FaceEngineException.class, () -> service.collect(image, "Guest", "a", null, null));
        verify(repo, never()).save(any());
    }
    @Test void boundedQueueRejectsExcessRequests() throws Exception {
        executor.shutdown(); config.setWorkers(1); config.setMaxInFlight(2);
        executor = new AppConfiguration().faceTaskExecutor(config); executor.initialize();
        service = new FaceService(engine, repo, config, executor);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        executor.submit(() -> { started.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        assertTrue(started.await(2, TimeUnit.SECONDS)); executor.submit(() -> { });
        try { assertThrows(FaceBusyException.class, () -> service.detect(image)); }
        finally { release.countDown(); }
    }
    @Test void waitingRequestTimesOut() {
        config.setRequestTimeoutMs(100);
        when(engine.detectFaces(image)).thenAnswer(call -> { Thread.sleep(10000); return Collections.emptyList(); });
        assertThrows(FaceBusyException.class, () -> service.detect(image));
    }
}
