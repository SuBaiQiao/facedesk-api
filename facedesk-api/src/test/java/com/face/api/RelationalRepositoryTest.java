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
    private MybatisPlusDatabase database(String type)throws Exception {
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;MODE="+(type.equals("dm")?"Oracle":"MySQL"),"sa","");
        String sql=new String(RelationalRepositoryTest.class.getResourceAsStream("/db/"+type+"/schema.sql").readAllBytes(),StandardCharsets.UTF_8).replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin","");
        new ResourceDatabasePopulator(new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))).execute(source);
        return new MybatisPlusDatabase(source,type,false);
    }
    private FaceRecord person(String code){float[] v=new float[128];v[0]=1;FaceRecord r=new FaceRecord("Guest",code,"姓名","AQID",v);r.setFeatureSpaceId("test-model");return r;}
    @ParameterizedTest @ValueSource(strings={"mysql","dm"})
    void sharesLatestFeaturesAndProtectsConcurrentCreation(String type)throws Exception {
        MybatisPlusDatabase db=database(type);MybatisPlusFaceRepository a=new MybatisPlusFaceRepository(db,"test-model"),b=new MybatisPlusFaceRepository(db,"test-model");
        a.saveNew(person("P1"));assertEquals("姓名",b.find("Guest","P1").orElseThrow().getName());
        a.addReference(person("P1"));assertEquals(1,b.find("Guest","P1").get().getReferenceFeatures().size());
        b.updateName("Guest","P1","更新");assertEquals("更新",a.find("Guest","P1").get().getName());
        FaceRecord cloned=a.find("Guest","P1").get();cloned.getFeature()[0]=99;cloned.getReferenceFeatures().get(0)[0]=99;assertEquals(1,b.find("Guest","P1").get().getFeature()[0]);
        Map<String,Object> detail=a.detail("Guest","P1");assertEquals(2,detail.get("sampleCount"));String id=(String)((Map<?,?>)((List<?>)detail.get("samples")).get(0)).get("id");assertEquals("AQID",b.referenceImage("Guest","P1",id));
        a.removeReference("Guest","P1",id);assertTrue(b.find("Guest","P1").get().getReferenceFeatures().isEmpty());
        ExecutorService threads=Executors.newFixedThreadPool(2);try{
            CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> writes=new ArrayList<>();
            for(MybatisPlusFaceRepository repo:Arrays.asList(a,b))writes.add(threads.submit(()->{start.await();try{repo.saveNew(person("RACE"));return true;}catch(IllegalArgumentException duplicate){return false;}}));
            start.countDown();int accepted=0;for(Future<Boolean> write:writes)if(write.get(5,TimeUnit.SECONDS))accepted++;assertEquals(1,accepted);
        }finally{threads.shutdownNow();}
        a.delete("Guest","P1");assertFalse(b.find("Guest","P1").isPresent());
        assertThrows(IllegalStateException.class,()->new MybatisPlusFaceRepository(db,"different-model"));
    }
    @ParameterizedTest @ValueSource(strings={"mysql","dm"})
    void historyIsSharedAndOnlyDeadNodeTasksAreInterrupted(String type)throws Exception {
        MybatisPlusDatabase db=database(type);new MybatisPlusFaceRepository(db,"test-model");ClusterProperties ca=new ClusterProperties(),cb=new ClusterProperties();
        try(NodeLease a=new NodeLease(db,ca,"test-model");NodeLease b=new NodeLease(db,cb,"test-model")){
            MybatisPlusHistoryRepository ra=new MybatisPlusHistoryRepository(db,new ObjectMapper(),a),rb=new MybatisPlusHistoryRepository(db,new ObjectMapper(),b);
            RecognitionHistory task=new RecognitionHistory();task.source="VIDEO";task.kind="TASK";task.status="PROCESSING";task.taskId="task-a";task.taskState="RUNNING";task.name="literal%_!";task.role="Guest";task.code="P1";ra.save(task);
            assertEquals("RUNNING",rb.get(task.id).taskState);
            RecognitionHistory current=new RecognitionHistory();current.source="USB";current.kind="TASK";current.status="PROCESSING";current.taskId="task-b";current.taskState="RUNNING";rb.save(current);
            RecognitionHistoryRepository.Filter filter=new RecognitionHistoryRepository.Filter("","","%_!","","","");
            assertEquals(1L,rb.page(filter,1,20).get("total"));assertEquals(1L,rb.page(RecognitionHistoryRepository.Filter.person("Guest","P1"),1,5).get("total"));
            assertEquals(0L,rb.page(RecognitionHistoryRepository.Filter.person("Lawyer","P1"),1,5).get("total"));
            db.nodes.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<com.face.api.persistence.entity.NodeEntity>()
                    .eq("node_id",a.id()).set("heartbeat_ms",System.currentTimeMillis()-60000));
            b.expireStale(System.currentTimeMillis());assertEquals("INTERRUPTED",rb.get(task.id).taskState);assertEquals("RUNNING",rb.get(current.id).taskState);
            assertThrows(RuntimeException.class,()->ra.finishVideo("task-a","COMPLETED",null,false));
            assertEquals("INTERRUPTED",rb.get(task.id).taskState);
            rb.finishVideo("task-b","COMPLETED",null,false);assertEquals("COMPLETED",rb.get(current.id).status);
            assertEquals(2,rb.export(new RecognitionHistoryRepository.Filter("","","","","","")).size());
        }
    }

    @ParameterizedTest @ValueSource(strings={"mysql","dm"})
    void mapperWritesRollbackTogetherAndDoNotCrossCompositeKeys(String type)throws Exception {
        MybatisPlusDatabase db=database(type);
        MybatisPlusFaceRepository repo=new MybatisPlusFaceRepository(db,"test-model");
        repo.saveNew(person("P1")); repo.addReference(person("P1"));
        FaceRecord other=person("P1"); other.setRole("Lawyer"); other.setName("另一个角色"); repo.saveNew(other);
        long revision=db.metadata.selectById(1).getRevision();
        assertThrows(IllegalStateException.class,()->db.transaction(()->{
            db.faces.update(null,new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<com.face.api.persistence.entity.FaceEntity>()
                    .eq("role","Guest").eq("code","P1").set("name","不能提交"));
            db.samples.delete(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.face.api.persistence.entity.ReferenceEntity>()
                    .eq("role","Guest").eq("code","P1"));
            db.metadata.incrementRevision();
            throw new IllegalStateException("模拟提交前故障");
        }));
        assertEquals(revision,db.metadata.selectById(1).getRevision());
        MybatisPlusFaceRepository reader=new MybatisPlusFaceRepository(db,"test-model");
        assertEquals("姓名",reader.find("Guest","P1").get().getName());
        assertEquals(1,reader.find("Guest","P1").get().getReferenceFeatures().size());
        repo.updateName("Guest","P1",null);
        assertNull(reader.find("Guest","P1").get().getName());
        assertEquals("另一个角色",reader.find("Lawyer","P1").get().getName());
        FaceRecord replacement=person("P1");replacement.setImageBase64(null);repo.save(replacement);
        assertNull(reader.find("Guest","P1").get().getImageBase64());
        assertTrue(reader.find("Guest","P1").get().getReferenceFeatures().isEmpty());
        repo.delete("Guest","P1");assertTrue(reader.find("Lawyer","P1").isPresent());
    }

    @ParameterizedTest @ValueSource(strings={"mysql","dm"})
    void dialectPaginationFiltersAndNullTerminalErrorsRemainCorrect(String type)throws Exception {
        MybatisPlusDatabase db=database(type);new MybatisPlusFaceRepository(db,"test-model");
        try(NodeLease node=new NodeLease(db,new ClusterProperties(),"test-model")){
            MybatisPlusHistoryRepository history=new MybatisPlusHistoryRepository(db,new ObjectMapper(),node);
            for(int i=0;i<5;i++){
                RecognitionHistory record=new RecognitionHistory();record.id="record-"+i;record.source="VIDEO";
                record.kind="TASK";record.status="PROCESSING";record.taskState="RUNNING";record.taskId="task-"+i;
                record.taskError="旧错误";record.createdAt="2026-01-01T00:00:00Z";record.name="%_! ' OR 1=1 --";
                history.save(record);
            }
            RecognitionHistoryRepository.Filter all=new RecognitionHistoryRepository.Filter("","","","","","");
            Map<String,Object> page=history.page(all,2,2);
            assertEquals(5L,page.get("total"));assertEquals(3,page.get("pages"));
            List<?> items=(List<?>)page.get("items");assertEquals(2,items.size());
            assertEquals("record-2",((RecognitionHistory)items.get(0)).id);
            assertEquals("record-1",((RecognitionHistory)items.get(1)).id);
            assertEquals(3,history.page(all,99,2).get("page"));
            assertEquals(1,((List<?>)history.page(all,99,2).get("items")).size());
            assertEquals(5L,history.page(new RecognitionHistoryRepository.Filter("VIDEO","PROCESSING","%_! ' OR 1=1 --","2026-01-01T00:00:00Z","2026-01-02T00:00:00Z",""),1,10).get("total"));
            assertEquals(0L,history.page(new RecognitionHistoryRepository.Filter("","","","","2026-01-01T00:00:00Z",""),1,10).get("total"));
            assertEquals(0L,history.page(new RecognitionHistoryRepository.Filter("","","' OR 1=1 -- absent","","",""),1,10).get("total"));
            history.finishVideo("task-0","COMPLETED",null,false);
            assertNull(history.get("record-0").taskError);assertEquals("COMPLETED",history.get("record-0").status);
            assertEquals(5,history.export(all).size());
        }
    }
}
