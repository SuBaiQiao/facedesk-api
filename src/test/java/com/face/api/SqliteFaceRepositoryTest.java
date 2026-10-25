package com.face.api;

import com.face.api.model.FaceRecord;
import com.face.api.repository.SqliteFaceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SqliteFaceRepositoryTest {
    @Test void captureEnrollmentCannotReplaceExistingProfileOrReferences() {
        try(SqliteFaceRepository repo=new SqliteFaceRepository(db().toString(),"test-model")){
            FaceRecord original=record("Guest","P1");repo.saveNew(original);repo.addReference(original);
            FaceRecord other=record("Guest","P1");other.setName("replacement");
            assertThrows(IllegalArgumentException.class,()->repo.saveNew(other));
            assertEquals(original.getName(),repo.find("Guest","P1").get().getName());
            assertEquals(1,repo.find("Guest","P1").get().getReferenceFeatures().size());
        }
    }
    @TempDir Path temp;
    Path db() { return temp.resolve("face.db"); }
    @Test void referenceSamplesPersistCopyAndFollowPrimaryReplacement() {
        SqliteFaceRepository repo=new SqliteFaceRepository(db().toString(),"test-model");repo.save(record("Guest","P1"));repo.addReference(record("Guest","P1"));repo.addReference(record("Guest","P1"));repo.close();
        repo=new SqliteFaceRepository(db().toString(),"test-model");
        try{assertEquals(2,repo.find("Guest","P1").get().getReferenceFeatures().size());Map<String,Object> detail=repo.detail("Guest","P1");assertEquals(3,detail.get("sampleCount"));assertFalse(detail.containsKey("feature"));
            String id=(String)((Map<?,?>)((List<?>)detail.get("samples")).get(0)).get("id");assertNotNull(repo.referenceImage("Guest","P1",id));repo.removeReference("Guest","P1",id);assertEquals(1,repo.find("Guest","P1").get().getReferenceFeatures().size());
            repo.find("Guest","P1").get().getReferenceFeatures().get(0)[0]=999;assertEquals(.6f,repo.find("Guest","P1").get().getReferenceFeatures().get(0)[0]);repo.updateName("Guest","P1","New");assertEquals(1,repo.find("Guest","P1").get().getReferenceFeatures().size());
            repo.save(record("Guest","P1"));assertEquals(1,repo.detail("Guest","P1").get("sampleCount"));repo.addReference(record("Guest","P1"));repo.delete("Guest","P1");
        }finally{repo.close();}
        try(SqliteFaceRepository reopened=new SqliteFaceRepository(db().toString(),"test-model")){assertTrue(reopened.findAll().isEmpty());}
    }
    @Test void boundsReferenceSamplesAndRejectsMixedFeatureSpaces(){
        try(SqliteFaceRepository repo=new SqliteFaceRepository(db().toString(),"test-model")){repo.save(record("Guest","P1"));for(int i=0;i<4;i++)repo.addReference(record("Guest","P1"));assertThrows(IllegalArgumentException.class,()->repo.addReference(record("Guest","P1")));FaceRecord wrong=record("Guest","P1");wrong.setFeatureSpaceId("other");assertThrows(IllegalArgumentException.class,()->repo.addReference(wrong));}
    }
    FaceRecord record(String role, String code) {
        float[] feature = new float[128]; feature[0] = .6f; feature[1] = .8f;
        FaceRecord record = new FaceRecord(role, code, "测试姓名", "data:image/png;base64,AQID", feature);
        record.setFeatureSpaceId("test-model"); return record;
    }
    Connection sql() throws SQLException { return DriverManager.getConnection("jdbc:sqlite:" + db()); }
    void failCode(String code) throws SQLException {
        try (Connection connection = sql(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER fail_insert BEFORE INSERT ON face_records WHEN NEW.code='" + code + "' BEGIN SELECT RAISE(ABORT,'test failure'); END");
        }
    }
    int countSql() throws SQLException {
        try (Connection connection = sql(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT count(*) FROM face_records")) {
            rows.next(); return rows.getInt(1);
        }
    }
    @Test void persistsBinaryFeaturesUpdatesAndDeletesAcrossRestarts() throws Exception {
        try (SqliteFaceRepository repo = new SqliteFaceRepository(db().toString(), "test-model")) {
            repo.save(record("Guest", "001")); repo.save(record("Family", "001"));
            FaceRecord update = record("Guest", "001"); update.setName("更新姓名"); repo.save(update);
            assertEquals(2, countSql());
            try (Connection connection = sql(); Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT name,typeof(feature),length(feature) FROM face_records WHERE role='Guest'")) {
                assertTrue(rows.next()); assertEquals("更新姓名", rows.getString(1));
                assertEquals("blob", rows.getString(2)); assertEquals(512, rows.getInt(3));
            }
        }
        assertEquals("SQLite format 3\0", new String(Files.readAllBytes(db()), 0, 16, java.nio.charset.StandardCharsets.US_ASCII));
        try (SqliteFaceRepository repo = new SqliteFaceRepository(db().toString(), "test-model")) {
            FaceRecord loaded = repo.find("Guest", "001").orElseThrow();
            assertEquals("更新姓名", loaded.getName()); assertArrayEquals(record("Guest", "001").getFeature(), loaded.getFeature());
            assertEquals("data:image/png;base64,AQID", loaded.getImageBase64());
            assertEquals(1, repo.findByRole("Family").size());
            repo.delete("Guest", "001"); assertEquals(1, repo.findAll().size());
        }
        try (SqliteFaceRepository repo = new SqliteFaceRepository(db().toString(), "test-model")) {
            assertFalse(repo.find("Guest", "001").isPresent()); assertEquals(1, repo.findAll().size());
        }
    }
    @Test void rejectsWrongModelAndCorruptFeatures() {
        try (SqliteFaceRepository repo = new SqliteFaceRepository(db().toString(), "test-model")) {
            FaceRecord old = record("Guest", "old"); old.setFeatureSpaceId("seeta");
            assertThrows(IllegalArgumentException.class, () -> repo.save(old));
            FaceRecord corrupt = record("Guest", "bad"); corrupt.setFeature(new float[128]);
            assertThrows(RuntimeException.class, () -> repo.save(corrupt));
            repo.save(record("Guest", "ok"));
        }
        assertThrows(IllegalStateException.class, () -> new SqliteFaceRepository(db().toString(), "other-model"));
        assertThrows(IllegalArgumentException.class, () -> new SqliteFaceRepository(temp.resolve("old.json").toString(), "test-model"));
    }
    @Test void nameOnlyEditKeepsOriginalPhotoAndFeaturesAcrossRestart() {
        try (SqliteFaceRepository repo = new SqliteFaceRepository(db().toString(), "test-model")) {
            repo.save(record("Guest", "001")); repo.updateName("Guest", "001", "只改姓名");
            assertThrows(IllegalArgumentException.class, () -> repo.updateName("Guest", "missing", "x"));
        }
        try (SqliteFaceRepository repo = new SqliteFaceRepository(db().toString(), "test-model")) {
            FaceRecord updated = repo.find("Guest", "001").orElseThrow();
            assertEquals("只改姓名", updated.getName()); assertArrayEquals(record("Guest", "001").getFeature(), updated.getFeature());
            assertEquals(record("Guest", "001").getImageBase64(), updated.getImageBase64());
        }
    }
    @Test void onlyPublishesCacheAfterSuccessfulCommitAndProtectsFeatureArrays() throws Exception {
        try (SqliteFaceRepository repo = new SqliteFaceRepository(db().toString(), "test-model")) {
            FaceRecord input = record("Guest", "ok"); repo.save(input); input.getFeature()[0] = 99;
            FaceRecord output = repo.find("Guest", "ok").orElseThrow(); output.getFeature()[0] = -99;
            assertEquals(.6f, repo.find("Guest", "ok").orElseThrow().getFeature()[0]);
            failCode("fail");
            assertThrows(IllegalStateException.class, () -> repo.save(record("Guest", "fail")));
            assertFalse(repo.find("Guest", "fail").isPresent()); assertEquals(1, countSql());
            // 失败后连接仍可继续写入。
            repo.save(record("Family", "next")); assertEquals(2, countSql());
        }
    }
    @Test void importsMatchingJsonAtomicallyWithoutModifyingSource() throws Exception {
        Path source = temp.resolve("source.json");
        new ObjectMapper().writeValue(source.toFile(), Arrays.asList(record("Guest", "a"), record("Family", "b")));
        byte[] original = Files.readAllBytes(source);
        try (SqliteFaceRepository repo = new SqliteFaceRepository(db().toString(), "test-model")) {
            repo.importJson(source); assertEquals(2, countSql()); assertEquals(2, repo.findAll().size());
            assertThrows(IllegalStateException.class, () -> repo.importJson(source));
        }
        assertArrayEquals(original, Files.readAllBytes(source));
    }
    @Test void importRollsBackEntireBatchOnDatabaseFailure() throws Exception {
        Path source = temp.resolve("source.json");
        new ObjectMapper().writeValue(source.toFile(), Arrays.asList(record("Guest", "ok"), record("Guest", "fail")));
        try (SqliteFaceRepository repo = new SqliteFaceRepository(db().toString(), "test-model")) {
            failCode("fail"); assertThrows(IllegalStateException.class, () -> repo.importJson(source));
            assertEquals(0, countSql()); assertTrue(repo.findAll().isEmpty());
        }
    }
    @Test void legacyOrDuplicateImportDoesNotPartiallyWrite() throws Exception {
        Path source = temp.resolve("source.json");
        try (SqliteFaceRepository repo = new SqliteFaceRepository(db().toString(), "test-model")) {
            FaceRecord old = record("Guest", "old"); old.setFeatureSpaceId(null);
            new ObjectMapper().writeValue(source.toFile(), Arrays.asList(record("Guest", "ok"), old));
            assertThrows(IllegalArgumentException.class, () -> repo.importJson(source)); assertEquals(0, countSql());
            new ObjectMapper().writeValue(source.toFile(), Arrays.asList(record("Guest", "same"), record("Guest", "same")));
            assertThrows(IllegalArgumentException.class, () -> repo.importJson(source)); assertEquals(0, countSql());
        }
    }
    @Test void concurrentReadsAndWritesKeepConsistentSnapshots() throws Exception {
        try (SqliteFaceRepository repo = new SqliteFaceRepository(db().toString(), "test-model")) {
            ExecutorService pool = Executors.newFixedThreadPool(16);
            try {
                List<Future<?>> tasks = new ArrayList<>();
                for (int i = 0; i < 250; i++) {
                    final int id = i;
                    tasks.add(pool.submit(() -> { repo.save(record("Guest", "P" + id)); repo.findByRole("Guest"); }));
                }
                for (Future<?> task : tasks) task.get(20, TimeUnit.SECONDS);
                assertEquals(250, repo.findAll().size()); assertEquals(250, countSql());
            } finally { pool.shutdownNow(); }
        }
    }
    @Test void refusesSecondWriterInstanceAndReleasesLockOnClose() {
        try (SqliteFaceRepository repo = new SqliteFaceRepository(db().toString(), "test-model")) {
            assertThrows(IllegalStateException.class, () -> new SqliteFaceRepository(db().toString(), "test-model"));
            repo.save(record("Guest", "still-open"));
        }
        try (SqliteFaceRepository repo = new SqliteFaceRepository(db().toString(), "test-model")) { assertEquals(1, repo.findAll().size()); }
    }
}
