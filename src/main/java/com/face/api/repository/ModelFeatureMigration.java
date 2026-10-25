package com.face.api.repository;

import com.face.api.engine.FaceEngine;
import com.face.api.engine.FeatureMath;
import java.nio.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Rebuild embeddings from retained originals; never reinterpret an old vector. */
final class ModelFeatureMigration {
    private ModelFeatureMigration() { }
    // 仅允许显式支持的SFace到AuraFace升级；模型相同直接返回，其余不兼容组合拒绝启动。
    static void run(Connection db, Path path, String target, FaceEngine engine) throws Exception {
        String previous;
        try (Statement s=db.createStatement(); ResultSet r=s.executeQuery("SELECT feature_space_id FROM face_metadata WHERE id=1")) {
            if (!r.next()) return;
            previous=r.getString(1);
        }
        if (target.equals(previous)) return;
        if (engine==null || !previous.startsWith("yunet-sface-128-v1:") || !target.startsWith("yunet-auraface-512-rgb127.5-v1:"))
            throw new IllegalStateException("模型指纹不匹配；仅支持 SFace 原图迁移至 AuraFace，禁止混用不同特征");
        // SQLite snapshot includes committed WAL data; a raw file copy would not.
        Path backup=Paths.get(path+".before-auraface-"+UUID.randomUUID()+".db");
        try (Statement s=db.createStatement()) { s.execute("VACUUM INTO '"+backup.toString().replace("'","''")+"'"); }
        // 先完整提取主照片和参考照片，任意照片失败都不改动原特征和元数据。
        List<Embedding> people=extract(db,engine,"SELECT role,code,image_base64 FROM face_records");
        List<Embedding> references=extract(db,engine,"SELECT id,'',image FROM face_reference_samples");
        // 表结构、所有向量和模型指纹在一个事务切换；中途失败连同表重建一起回滚。
        db.setAutoCommit(false);
        try (Statement s=db.createStatement()) {
            // 旧表可能有固定512字节约束，需要重建；复制原图、人员字段和时间，随后只更新特征。
            s.execute("ALTER TABLE face_records RENAME TO face_records_old_model");
            s.execute("CREATE TABLE face_records (role TEXT NOT NULL,code TEXT NOT NULL,name TEXT,image_base64 TEXT,feature BLOB NOT NULL CHECK(length(feature) IN (512,2048)),feature_space_id TEXT NOT NULL,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(role,code))");
            s.execute("INSERT INTO face_records SELECT * FROM face_records_old_model");
            s.execute("ALTER TABLE face_reference_samples RENAME TO face_reference_samples_old_model");
            s.execute("CREATE TABLE face_reference_samples (id TEXT PRIMARY KEY,role TEXT NOT NULL,code TEXT NOT NULL,image TEXT NOT NULL,feature BLOB NOT NULL CHECK(length(feature) IN (512,2048)),created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            s.execute("INSERT INTO face_reference_samples SELECT * FROM face_reference_samples_old_model");
            try (PreparedStatement update=db.prepareStatement("UPDATE face_records SET feature=?,feature_space_id=? WHERE role=? AND code=?")) {
                for (Embedding e:people) { update.setBytes(1,e.vector);update.setString(2,target);update.setString(3,e.key);update.setString(4,e.code);update.addBatch(); }
                update.executeBatch();
            }
            try (PreparedStatement update=db.prepareStatement("UPDATE face_reference_samples SET feature=? WHERE id=?")) {
                for (Embedding e:references) { update.setBytes(1,e.vector);update.setString(2,e.key);update.addBatch(); }
                update.executeBatch();
            }
            // 最后更新模型指纹，避免新向量与旧指纹被当作有效底库加载。
            try (PreparedStatement update=db.prepareStatement("UPDATE face_metadata SET feature_space_id=? WHERE id=1")) {
                update.setString(1,target);update.executeUpdate();
            }
            s.execute("DROP TABLE face_records_old_model");
            s.execute("DROP TABLE face_reference_samples_old_model");
            s.execute("CREATE INDEX idx_reference_person ON face_reference_samples(role,code)");
            db.commit();
        } catch (Exception failure) { db.rollback();throw failure; }
        finally { db.setAutoCommit(true); }
        System.out.println("AuraFace migration complete: people="+people.size()+", references="+references.size()+", backup="+backup);
    }
    // 原图既可能是纯Base64也可能带Data URL前缀；向量重新归一化后编码为小端float32。
    private static List<Embedding> extract(Connection db,FaceEngine engine,String query) throws Exception {
        List<Embedding> result=new ArrayList<>();
        try (Statement s=db.createStatement();ResultSet r=s.executeQuery(query)) {
            while(r.next()) {
                String key=r.getString(1),code=r.getString(2),image=r.getString(3);
                try {
                    if(image==null || image.isEmpty()) throw new IllegalArgumentException("缺少原始照片");
                    int comma=image.indexOf(',');
                    float[] vector=engine.extractFeature(Base64.getDecoder().decode(comma>=0?image.substring(comma+1):image));
                    if(vector.length!=512) throw new IllegalArgumentException("目标模型应输出512维");
                    vector=FeatureMath.normalize(vector);
                    ByteBuffer bytes=ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);
                    for(float value:vector)bytes.putFloat(value);
                    result.add(new Embedding(key,code,bytes.array()));
                }catch(Exception failure){throw new IllegalStateException("原图迁移失败："+key+"/"+code+"；原库未切换，请修复该照片后重启",failure);}
            }
        }
        return result;
    }
    private static final class Embedding {
        final String key,code;final byte[] vector;
        Embedding(String key,String code,byte[] vector){this.key=key;this.code=code;this.vector=vector;}
    }
}
