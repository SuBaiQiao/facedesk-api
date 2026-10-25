package com.face.api;

import com.face.api.config.FaceProperties;
import com.face.api.engine.*;
import com.face.api.model.FaceRecord;
import com.face.api.repository.SqliteFaceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="face.aura.integration",matches="true")
class AuraFaceIntegrationTest {
    @Test void realModelMigratesOriginalsAndReferencesPreservingHistory() throws Exception {
        FaceProperties config=new FaceProperties();config.setRecognitionBackend("auraface");
        config.setRecognizerModel("face_recognition_auraface_v1.onnx");config.setWorkers(1);config.setInferenceThreads(4);
        try(SFaceEngine engine=new SFaceEngine(config)) {
            engine.initialize();byte[] image=Files.readAllBytes(Paths.get("target/test-images/lena.jpg"));
            long start=System.nanoTime();float[] vector=engine.extractFeature(image);long elapsed=(System.nanoTime()-start)/1000000;
            assertEquals(512,vector.length);assertEquals(1,FeatureMath.cosine(vector,vector),1e-6);
            byte[] variant=Files.readAllBytes(Paths.get("target/test-images/reference-variant.png"));
            double score=FeatureMath.cosine(vector,engine.extractFeature(variant));assertTrue(score>.55,"resize cosine="+score);
            Path path=Files.createTempDirectory(Paths.get("target"),"aura-migration-").resolve("face.db");
            String previous="yunet-sface-128-v1:test:test";
            float[] old=new float[128];old[0]=1;
            FaceRecord record=new FaceRecord("Guest","AURA-FIXTURE","fixture",Base64.getEncoder().encodeToString(image),old);record.setFeatureSpaceId(previous);
            try(SqliteFaceRepository repo=new SqliteFaceRepository(path.toString(),previous)){repo.save(record);repo.addReference(record);}
            // Emulate the original schema's fixed 128-dimensional constraint.
            try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+path);Statement s=db.createStatement()) {
                s.execute("CREATE TABLE fixture_history(value TEXT)");s.execute("INSERT INTO fixture_history VALUES('retained')");
                s.execute("ALTER TABLE face_records RENAME TO fixture_old_records");
                s.execute("CREATE TABLE face_records(role TEXT,code TEXT,name TEXT,image_base64 TEXT,feature BLOB CHECK(length(feature)=512),feature_space_id TEXT,created_at TEXT,updated_at TEXT,PRIMARY KEY(role,code))");
                s.execute("INSERT INTO face_records SELECT * FROM fixture_old_records");s.execute("DROP TABLE fixture_old_records");
            }
            try(SqliteFaceRepository repo=new SqliteFaceRepository(path.toString(),engine.featureSpaceId(),engine)) {
                FaceRecord upgraded=repo.find("Guest","AURA-FIXTURE").orElseThrow();
                assertEquals(512,upgraded.getFeature().length);assertEquals(512,upgraded.getReferenceFeatures().get(0).length);
                assertEquals(1,FeatureMath.cosine(vector,upgraded.getFeature()),1e-6);
                assertEquals(512,repo.detail("Guest","AURA-FIXTURE").get("featureDimensions"));
            }
            try(SqliteFaceRepository reopened=new SqliteFaceRepository(path.toString(),engine.featureSpaceId())){assertEquals(1,reopened.findAll().size());}
            try(Connection db=DriverManager.getConnection("jdbc:sqlite:"+path);Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT value FROM fixture_history")){assertTrue(r.next());assertEquals("retained",r.getString(1));}
            try(java.util.stream.Stream<Path> files=Files.list(path.getParent())){assertEquals(1,files.filter(p->p.getFileName().toString().contains("before-auraface")).count());}
            Map<String,Object> report=new LinkedHashMap<>();report.put("engine",engine.engineName());report.put("dimensions",512);report.put("firstInferenceMs",elapsed);List<Long> timings=new ArrayList<>();for(int i=0;i<8;i++){long began=System.nanoTime();engine.extractFeature(image);timings.add((System.nanoTime()-began)/1000000);}Collections.sort(timings);report.put("warmP50Ms",timings.get(3));report.put("warmMaxMs",timings.get(7));report.put("inferenceThreads",4);report.put("sameImageResizedCosine",score);report.put("scope","Local portrait resize and migration smoke test, not an accuracy benchmark or physical camera test");
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Paths.get("target/auraface-report.json").toFile(),report);
            System.out.println("AURA_REPORT "+report);
        }
    }
}
