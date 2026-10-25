package com.face.api;

import com.face.api.engine.FaceEngine;
import com.face.api.model.FaceRecord;
import com.face.api.repository.SqliteFaceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelMigrationTest {
    @TempDir Path directory;
    @Test void invalidOriginalLeavesOldFeaturesAndMetadataReadable() {
        String old="yunet-sface-128-v1:test:test",next="yunet-auraface-512-rgb127.5-v1:test:test";
        Path path=directory.resolve("face.db");float[] vector=new float[128];vector[0]=1;
        FaceRecord person=new FaceRecord("Guest","P1","fixture",null,vector);person.setFeatureSpaceId(old);
        try(SqliteFaceRepository repo=new SqliteFaceRepository(path.toString(),old)){repo.save(person);}
        FaceEngine engine=mock(FaceEngine.class);
        assertThrows(IllegalStateException.class,()->new SqliteFaceRepository(path.toString(),next,engine));
        try(SqliteFaceRepository repo=new SqliteFaceRepository(path.toString(),old)){assertArrayEquals(vector,repo.find("Guest","P1").get().getFeature());}
        verifyNoInteractions(engine);
    }
    @Test void dimensionsAreValidatedByFeatureSpace() {
        String space="yunet-auraface-512-rgb127.5-v1:test:test";float[] vector=new float[512];vector[0]=1;
        FaceRecord person=new FaceRecord("Guest","P1","fixture",null,vector);person.setFeatureSpaceId(space);
        try(SqliteFaceRepository repo=new SqliteFaceRepository(directory.resolve("face.db").toString(),space)){
            repo.save(person);assertEquals(512,repo.find("Guest","P1").get().getFeature().length);
            float[] wrong=new float[128];wrong[0]=1;person.setFeature(wrong);assertThrows(IllegalArgumentException.class,()->repo.save(person));
        }
    }
    @Test void databaseFailureRollsBackTablesFeaturesAndMetadata() throws Exception {
        String old="yunet-sface-128-v1:test:test",next="yunet-auraface-512-rgb127.5-v1:test:test";
        Path path=directory.resolve("face.db");float[] vector=new float[128];vector[0]=1;
        FaceRecord person=new FaceRecord("Guest","P1","fixture","AQID",vector);person.setFeatureSpaceId(old);
        try(SqliteFaceRepository repo=new SqliteFaceRepository(path.toString(),old)){repo.save(person);repo.addReference(person);}
        try(java.sql.Connection db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+path);java.sql.Statement s=db.createStatement()){
            s.execute("CREATE TRIGGER reject_upgrade BEFORE UPDATE ON face_metadata BEGIN SELECT RAISE(ABORT,'fixture failure'); END");
        }
        FaceEngine engine=mock(FaceEngine.class);float[] upgraded=new float[512];upgraded[0]=1;
        when(engine.extractFeature(any(byte[].class))).thenReturn(upgraded);
        assertThrows(IllegalStateException.class,()->new SqliteFaceRepository(path.toString(),next,engine));
        try(SqliteFaceRepository repo=new SqliteFaceRepository(path.toString(),old)){
            assertArrayEquals(vector,repo.find("Guest","P1").get().getFeature());assertEquals(128,repo.find("Guest","P1").get().getReferenceFeatures().get(0).length);
        }
    }
}
