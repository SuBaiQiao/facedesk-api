package com.face.api.repository;

import com.face.api.engine.FeatureMath;
import com.face.api.model.FaceRecord;
import java.nio.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** MySQL/DM共享底库。每次检索核验revision，变更后以数据库锁加载一致快照。 */
public final class JdbcFaceRepository implements FaceRepository,AutoCloseable {
    private final JdbcDatabase db;
    private final String space;
    private volatile long revision=-1;
    private volatile Map<String,FaceRecord> cache=Collections.emptyMap();
    public JdbcFaceRepository(JdbcDatabase db,String space){
        this.db=db;this.space=space;
        db.transaction(()->{db.lockModel(space);return null;});refresh();
    }
    // 使用角色长度前缀，避免不同 role/code 组合拼接成相同缓存键。
    private static String key(String role,String code){return role.length()+":"+role+code;}
    private void validate(FaceRecord record){
        if(record==null||record.getRole()==null||record.getRole().isEmpty()||record.getCode()==null||record.getCode().trim().isEmpty())throw new IllegalArgumentException("role/code不能为空");
        if(record.getRole().length()>128||record.getCode().length()>128||(record.getName()!=null&&record.getName().length()>128))throw new IllegalArgumentException("档案字段过长");
        if(!space.equals(record.getFeatureSpaceId()))throw new IllegalArgumentException("不同模型特征禁止混用");
        // 自相似计算同时校验零向量及非有限值，防止损坏特征进入检索缓存。
        FeatureMath.cosine(record.getFeature(),record.getFeature());
        int dimensions=space.startsWith("yunet-auraface-512-")?512:128;
        if(record.getFeature().length!=dimensions)throw new IllegalArgumentException("特征维度与模型不符");
    }
    private synchronized void refresh(){
        // 常规检索只查询版本号；其他实例提交档案变更后，下次读取会重新加载。
        long latest=db.jdbc.queryForObject("SELECT revision FROM face_metadata WHERE id=1",Long.class);
        if(latest==revision)return;
        db.transaction(()->{
            // 加载主档案和参考特征期间持有元数据锁，避免读取混合版本。
            db.lockModel(space);
            long locked=db.jdbc.queryForObject("SELECT revision FROM face_metadata WHERE id=1",Long.class);
            Map<String,FaceRecord> next=new LinkedHashMap<>();
            db.jdbc.query("SELECT role,code,name,image_base64,feature,feature_space_id FROM face_records",rs->{
                FaceRecord record=new FaceRecord(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),decode(rs.getBytes(5)));
                record.setFeatureSpaceId(rs.getString(6));validate(record);next.put(key(record.getRole(),record.getCode()),record);
            });
            db.jdbc.query("SELECT role,code,feature FROM face_reference_samples ORDER BY id",rs->{
                FaceRecord record=next.get(key(rs.getString(1),rs.getString(2)));if(record==null)throw new IllegalStateException("参考照片缺少人员档案");
                float[] vector=decode(rs.getBytes(3));FeatureMath.cosine(vector,vector);
                if(vector.length!=record.getFeature().length)throw new IllegalStateException("参考特征维度损坏");record.getReferenceFeatures().add(vector);
            });
            // 全部校验完成后一次性发布快照，读取线程不会看到加载中的集合。
            cache=Collections.unmodifiableMap(next);revision=locked;return null;
        });
    }
    // 档案修改与版本递增在同一事务提交，回滚时不会发布无效版本。
    private void mutate(Supplier<Void> action){
        db.transaction(()->{db.lockModel(space);action.get();db.jdbc.update("UPDATE face_metadata SET revision=revision+1 WHERE id=1");return null;});
        revision=-1; // 其他实例也会在下次读取发现revision变化，不依赖消息广播送达。
    }
    @Override public void save(FaceRecord record){save(record,false);}
    @Override public void saveNew(FaceRecord record){save(record,true);}
    private void save(FaceRecord r,boolean onlyNew){
        validate(r);mutate(()->{
            int count=count(r.getRole(),r.getCode());
            // 存在性检查受元数据锁保护，跨实例并发录入也不能覆盖已有档案。
            if(onlyNew&&count>0)throw new IllegalArgumentException("该角色下的人员编号已存在，请到已有档案添加参考照片");
            String now=Instant.now().toString();
            if(count==0)db.jdbc.update("INSERT INTO face_records(role,code,name,image_base64,feature,feature_space_id,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",r.getRole(),r.getCode(),r.getName(),r.getImageBase64(),encode(r.getFeature()),space,now,now);
            else db.jdbc.update("UPDATE face_records SET name=?,image_base64=?,feature=?,feature_space_id=?,updated_at=? WHERE role=? AND code=?",r.getName(),r.getImageBase64(),encode(r.getFeature()),space,now,r.getRole(),r.getCode());
            // 替换主照片时清空旧参考特征；仅修改姓名则保留参考照片。
            db.jdbc.update("DELETE FROM face_reference_samples WHERE role=? AND code=?",r.getRole(),r.getCode());return null;
        });
    }
    private int count(String role,String code){return db.jdbc.queryForObject("SELECT COUNT(*) FROM face_records WHERE role=? AND code=?",Integer.class,role,code);}
    private void requirePerson(String role,String code){if(count(role,code)!=1)throw new IllegalArgumentException("人员档案不存在");}
    @Override public void delete(String role,String code){mutate(()->{db.jdbc.update("DELETE FROM face_reference_samples WHERE role=? AND code=?",role,code);db.jdbc.update("DELETE FROM face_records WHERE role=? AND code=?",role,code);return null;});}
    @Override public void updateName(String role,String code,String name){
        if(name!=null&&name.length()>128)throw new IllegalArgumentException("姓名不能超过128字符");
        mutate(()->{requirePerson(role,code);db.jdbc.update("UPDATE face_records SET name=?,updated_at=? WHERE role=? AND code=?",name,Instant.now().toString(),role,code);return null;});
    }
    @Override public void addReference(FaceRecord sample){
        validate(sample);mutate(()->{requirePerson(sample.getRole(),sample.getCode());
            int count=db.jdbc.queryForObject("SELECT COUNT(*) FROM face_reference_samples WHERE role=? AND code=?",Integer.class,sample.getRole(),sample.getCode());
            // 上限检查和插入共用事务锁，防止多个实例同时添加导致数量超限。
            if(count>=4)throw new IllegalArgumentException("每人最多5张照片（含主照片）");
            db.jdbc.update("INSERT INTO face_reference_samples(id,role,code,image,feature,created_at) VALUES(?,?,?,?,?,?)",UUID.randomUUID().toString(),sample.getRole(),sample.getCode(),sample.getImageBase64(),encode(sample.getFeature()),Instant.now().toString());return null;
        });
    }
    @Override public void removeReference(String role,String code,String id){mutate(()->{
        if(db.jdbc.update("DELETE FROM face_reference_samples WHERE role=? AND code=? AND id=?",role,code,id)!=1)throw new IllegalArgumentException("参考照片不存在");return null;
    });}
    @Override public Optional<FaceRecord> find(String role,String code){refresh();return Optional.ofNullable(cache.get(key(role,code))).map(JdbcFaceRepository::copy);}
    @Override public List<FaceRecord> findAll(){return findByRole(null);}
    @Override public List<FaceRecord> findByRole(String role){refresh();List<FaceRecord> out=new ArrayList<>();for(FaceRecord r:cache.values())if(role==null||role.isEmpty()||role.equals(r.getRole()))out.add(copy(r));return out;}
    @Override public Map<String,Object> detail(String role,String code){
        FaceRecord person=find(role,code).orElseThrow(()->new IllegalArgumentException("人员档案不存在"));
        Map<String,Object> out=new LinkedHashMap<>();out.put("role",role);out.put("code",code);out.put("name",person.getName());out.put("hasImage",person.getImageBase64()!=null);out.put("featureDimensions",person.getFeature().length);out.put("featureSpaceId",space);
        db.jdbc.query("SELECT created_at,updated_at FROM face_records WHERE role=? AND code=?",rs->{out.put("createdAt",rs.getString(1));out.put("updatedAt",rs.getString(2));},role,code);
        List<Map<String,Object>> samples=db.jdbc.query("SELECT id,created_at FROM face_reference_samples WHERE role=? AND code=? ORDER BY id",(rs,row)->{Map<String,Object> item=new LinkedHashMap<>();item.put("id",rs.getString(1));item.put("createdAt",rs.getString(2));return item;},role,code);
        out.put("samples",samples);out.put("sampleCount",1+samples.size());return out;
    }
    @Override public String referenceImage(String role,String code,String id){List<String> found=db.jdbc.query("SELECT image FROM face_reference_samples WHERE role=? AND code=? AND id=?",(rs,n)->rs.getString(1),role,code,id);if(found.isEmpty())throw new IllegalArgumentException("参考照片不存在");return found.get(0);}
    // 深拷贝特征数组，避免调用方修改公共检索快照。
    private static FaceRecord copy(FaceRecord r){FaceRecord out=new FaceRecord(r.getRole(),r.getCode(),r.getName(),r.getImageBase64(),r.getFeature().clone());out.setFeatureSpaceId(r.getFeatureSpaceId());for(float[] f:r.getReferenceFeatures())out.getReferenceFeatures().add(f.clone());return out;}
    // 统一采用小端 float32 格式存储，数据库只负责保存二进制内容。
    static byte[] encode(float[] vector){ByteBuffer out=ByteBuffer.allocate(vector.length*4).order(ByteOrder.LITTLE_ENDIAN);for(float f:vector)out.putFloat(f);return out.array();}
    // 只接受128维或512维向量长度，加载时还需核验当前模型与特征空间。
    static float[] decode(byte[] bytes){if(bytes==null||(bytes.length!=512&&bytes.length!=2048))throw new IllegalStateException("特征数据损坏");float[] out=new float[bytes.length/4];ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);return out;}
    @Override public void close(){cache=Collections.emptyMap();}
}
