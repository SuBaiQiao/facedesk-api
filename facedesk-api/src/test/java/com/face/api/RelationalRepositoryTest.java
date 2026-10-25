package com.face.api;

import com.face.api.cluster.*;
import com.face.api.model.*;
import com.face.api.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ByteArrayResource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** H2方言契约测试，不代替MySQL/DM服务器验收。每个用例使用独立内存库。 */
class RelationalRepositoryTest {
    private JdbcDatabase database(String type)throws Exception {
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;MODE="+(type.equals("dm")?"Oracle":"MySQL"),"sa","");
        String sql=new String(RelationalRepositoryTest.class.getResourceAsStream("/db/"+type+"/schema.sql").readAllBytes(),StandardCharsets.UTF_8).replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin","");
        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(source);
        return new JdbcDatabase(source,type,false);
    }
    private FaceRecord person(String code){float[] v=new float[128];v[0]=1;FaceRecord r=new FaceRecord("Guest",code,"姓名","AQID",v);r.setFeatureSpaceId("test-model");return r;}
    @ParameterizedTest @ValueSource(strings={"mysql","dm"})
    void sharesLatestFeaturesAndProtectsConcurrentCreation(String type)throws Exception {
        JdbcDatabase db=database(type);JdbcFaceRepository a=new JdbcFaceRepository(db,"test-model"),b=new JdbcFaceRepository(db,"test-model");
        a.saveNew(person("P1"));assertEquals("姓名",b.find("Guest","P1").orElseThrow().getName());
        a.addReference(person("P1"));assertEquals(1,b.find("Guest","P1").get().getReferenceFeatures().size());
        b.updateName("Guest","P1","更新");assertEquals("更新",a.find("Guest","P1").get().getName());
        FaceRecord cloned=a.find("Guest","P1").get();cloned.getFeature()[0]=99;cloned.getReferenceFeatures().get(0)[0]=99;assertEquals(1,b.find("Guest","P1").get().getFeature()[0]);
        Map<String,Object> detail=a.detail("Guest","P1");assertEquals(2,detail.get("sampleCount"));String id=(String)((Map<?,?>)((List<?>)detail.get("samples")).get(0)).get("id");assertEquals("AQID",b.referenceImage("Guest","P1",id));
        a.removeReference("Guest","P1",id);assertTrue(b.find("Guest","P1").get().getReferenceFeatures().isEmpty());
        ExecutorService threads=Executors.newFixedThreadPool(2);try{
            CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> writes=new ArrayList<>();
            for(JdbcFaceRepository repo:Arrays.asList(a,b))writes.add(threads.submit(()->{start.await();try{repo.saveNew(person("RACE"));return true;}catch(IllegalArgumentException duplicate){return false;}}));
            start.countDown();int accepted=0;for(Future<Boolean> write:writes)if(write.get(5,TimeUnit.SECONDS))accepted++;assertEquals(1,accepted);
        }finally{threads.shutdownNow();}
        a.delete("Guest","P1");assertFalse(b.find("Guest","P1").isPresent());
        assertThrows(IllegalStateException.class,()->new JdbcFaceRepository(db,"different-model"));
    }
    @ParameterizedTest @ValueSource(strings={"mysql","dm"})
    void historyIsSharedAndOnlyDeadNodeTasksAreInterrupted(String type)throws Exception {
        JdbcDatabase db=database(type);new JdbcFaceRepository(db,"test-model");ClusterProperties ca=new ClusterProperties(),cb=new ClusterProperties();
        try(NodeLease a=new NodeLease(db,ca,"test-model");NodeLease b=new NodeLease(db,cb,"test-model")){
            JdbcRecognitionHistoryRepository ra=new JdbcRecognitionHistoryRepository(db,new ObjectMapper(),a),rb=new JdbcRecognitionHistoryRepository(db,new ObjectMapper(),b);
            RecognitionHistory task=new RecognitionHistory();task.source="VIDEO";task.kind="TASK";task.status="PROCESSING";task.taskId="task-a";task.taskState="RUNNING";task.name="literal%_!";task.role="Guest";task.code="P1";ra.save(task);
            assertEquals("RUNNING",rb.get(task.id).taskState);
            RecognitionHistory current=new RecognitionHistory();current.source="USB";current.kind="TASK";current.status="PROCESSING";current.taskId="task-b";current.taskState="RUNNING";rb.save(current);
            RecognitionHistoryRepository.Filter filter=new RecognitionHistoryRepository.Filter("","","%_!","","","");
            assertEquals(1L,rb.page(filter,1,20).get("total"));assertEquals(1L,rb.page(RecognitionHistoryRepository.Filter.person("Guest","P1"),1,5).get("total"));
            assertEquals(0L,rb.page(RecognitionHistoryRepository.Filter.person("Lawyer","P1"),1,5).get("total"));
            db.jdbc.update("UPDATE facedesk_nodes SET heartbeat_ms=? WHERE node_id=?",System.currentTimeMillis()-60000,a.id());
            b.expireStale(System.currentTimeMillis());assertEquals("INTERRUPTED",rb.get(task.id).taskState);assertEquals("RUNNING",rb.get(current.id).taskState);
            assertThrows(RuntimeException.class,()->ra.finishVideo("task-a","COMPLETED",null,false));
            assertEquals("INTERRUPTED",rb.get(task.id).taskState);
            rb.finishVideo("task-b","COMPLETED",null,false);assertEquals("COMPLETED",rb.get(current.id).status);
            assertEquals(2,rb.export(new RecognitionHistoryRepository.Filter("","","","","","")).size());
        }
    }
}
