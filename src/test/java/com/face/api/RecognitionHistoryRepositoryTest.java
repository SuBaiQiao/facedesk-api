package com.face.api;

import com.face.api.config.FaceProperties;
import com.face.api.model.*;
import com.face.api.repository.RecognitionHistoryRepository;
import com.face.api.repository.RecognitionHistoryRepository.Filter;
import com.face.api.service.RecognitionHistoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RecognitionHistoryRepositoryTest {
    @TempDir Path directory;
    RecognitionHistoryRepository repo;
    @BeforeEach void open(){repo=new RecognitionHistoryRepository(directory.resolve("face.db").toString(),new ObjectMapper());}
    @AfterEach void close(){repo.close();}
    Filter all(){return new Filter("","","","","","");}
    @Test void personHistoryUsesExactRoleAndCodeRatherThanNameOrSubstring(){
        RecognitionHistory a=record("MATCHED","2026-10-25T02:00:00Z");a.role="Guest";a.code="1";a.name="Old name";repo.save(a);
        RecognitionHistory b=record("MATCHED","2026-10-25T02:00:00Z");b.role="Lawyer";b.code="1";repo.save(b);
        RecognitionHistory c=record("MATCHED","2026-10-25T02:00:00Z");c.role="Guest";c.code="11";repo.save(c);
        assertEquals(1L,repo.page(Filter.person("Guest","1"),1,5).get("total"));
    }
    RecognitionHistory record(String status,String time){RecognitionHistory r=new RecognitionHistory();r.createdAt=time;r.source="IMAGE";r.sourceName="照片识别";r.status=status;return r;}
    @Test void persistsSnapshotsAcrossRestartAndFiltersByTimeSourceStatusAndLiteralSearch(){
        RecognitionHistory known=record("MATCHED","2026-10-25T02:00:00Z");known.name="张三%_";known.code="P001";known.score=.91;known.thumbnail="data:image/jpeg;base64,AA==";repo.save(known);
        repo.save(record("UNKNOWN","2026-10-25T03:00:00Z"));
        repo.close();open();
        RecognitionHistory stored=repo.get(known.id);assertEquals("张三%_",stored.name);assertEquals(.91,stored.score);assertEquals(known.thumbnail,stored.thumbnail);
        Map<String,Object> page=repo.page(new Filter("IMAGE","MATCHED","%_","2026-10-25T02:00:00Z","2026-10-25T03:00:00Z",""),1,20);
        assertEquals(1L,page.get("total"));assertEquals(1L,page.get("matched"));
        assertEquals(0L,repo.page(new Filter("VIDEO","","","","",""),1,20).get("total"));
        assertEquals(0L,repo.page(new Filter("","","' OR 1=1 --","","",""),1,20).get("total"));
        assertEquals(1L,repo.page(new Filter("","","","2026-10-25T03:00:00Z","",""),1,20).get("total"));
        assertEquals(1,((List<?>)repo.page(all(),2,1).get("items")).size());
    }
    @Test void videoSummaryAndFacesShareFinalStateAndRestartMarksUnfinishedTasks(){
        RecognitionHistoryService service=new RecognitionHistoryService(repo,new FaceProperties());
        service.startVideo("done","门口.mp4","");VideoFaceResult face=new VideoFaceResult(new FaceBox(0,0,100,100),"UNKNOWN",null);face.frame=3;face.timeSeconds=.1;
        service.videoFace("done","门口.mp4","",face);service.finishVideo("done","CANCELLED",null,false);
        List<?> cancelled=(List<?>)repo.page(new Filter("VIDEO","","","","","done"),1,20).get("items");
        assertEquals(2,cancelled.size());for(Object row:cancelled)assertEquals("CANCELLED",((RecognitionHistory)row).taskState);
        service.startVideo("incomplete","test.avi","");repo.close();open();
        RecognitionHistory interrupted=(RecognitionHistory)((List<?>)repo.page(new Filter("","","","","","incomplete"),1,20).get("items")).get(0);
        assertEquals("INTERRUPTED",interrupted.taskState);assertEquals("ERROR",interrupted.status);
        assertEquals(2L,repo.page(new Filter("","","","","","done"),1,20).get("total"));
    }
    @Test void exportUsesSnapshotsAndNeutralizesSpreadsheetFormulas(){
        RecognitionHistory r=record("MATCHED","2026-10-25T03:00:00Z");r.name="=1+1\n恶意公式";r.code="001";repo.save(r);
        String csv=new String(new RecognitionHistoryService(repo,new FaceProperties()).export(all()),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\ufeff"));assertTrue(csv.contains("\"'=1+1\n恶意公式\""));assertTrue(csv.contains("001"));
        assertThrows(IllegalArgumentException.class,()->new Filter("wrong","","","","",""));
        assertThrows(IllegalArgumentException.class,()->new Filter("","wrong","","","",""));
        assertThrows(IllegalArgumentException.class,()->new Filter("","","","not-time","",""));
        assertThrows(IllegalArgumentException.class,()->new Filter("","","","2026-10-26T00:00:00Z","2026-10-25T00:00:00Z",""));
        assertThrows(IllegalArgumentException.class,()->repo.page(all(),0,20));
    }
    @Test void cameraSourcesPersistWithSessionStateAndNameOnly(){
        RecognitionHistoryService service=new RecognitionHistoryService(repo,new FaceProperties());
        service.startCamera("camera-session","RTSP","入口摄像头","");
        VideoFaceResult face=new VideoFaceResult(new FaceBox(2,3,100,110),"MATCHED",new RecognitionResult("Guest","P1","张三",.91,null));
        service.cameraFace("camera-session","RTSP","入口摄像头","",face);service.finishCamera("camera-session","STOPPED",null,false);
        repo.close();open();Map<String,Object> result=repo.page(new Filter("RTSP","","入口","","","camera-session"),1,20);
        assertEquals(2L,result.get("total"));assertEquals(1L,result.get("matched"));
        for(Object row:(List<?>)result.get("items")){RecognitionHistory r=(RecognitionHistory)row;assertEquals("STOPPED",r.taskState);assertEquals("入口摄像头",r.sourceName);}
        assertEquals(0L,repo.page(new Filter("USB","","","","",""),1,20).get("total"));
    }
}
