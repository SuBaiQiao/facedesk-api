package com.face.api.repository;

import com.face.api.engine.FeatureMath;
import com.face.api.model.FaceRecord;
import com.face.api.persistence.entity.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import java.nio.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * MySQL/DM共享底库。每次检索核验revision，变更后以数据库锁加载一致快照。
 */
public final class MybatisPlusFaceRepository implements FaceRepository, AutoCloseable {
    private final MybatisPlusDatabase db;
    private final String space;
    private volatile long revision = -1;
    private volatile Map<String, FaceRecord> cache = Collections.emptyMap();

    public MybatisPlusFaceRepository(MybatisPlusDatabase db, String space) {
        this.db = db;
        this.space = space;
        db.transaction(() -> {
            db.lockModel(space);
            return null;
        });
        refresh();
    }

    // 使用角色长度前缀，避免不同 role/code 组合拼接成相同缓存键。
    private static String key(String role, String code) {
        return role.length() + ":" + role + code;
    }

    private void validate(FaceRecord record) {
        if (record == null || record.getRole() == null || record.getRole().isEmpty() || record.getCode() == null || record.getCode().trim().isEmpty()) {
            throw new IllegalArgumentException("role/code不能为空");
        }
        if (record.getRole().length() > 128 || record.getCode().length() > 128 || (record.getName() != null && record.getName().length() > 128)) {
            throw new IllegalArgumentException("档案字段过长");
        }
        if (!space.equals(record.getFeatureSpaceId())) {
            throw new IllegalArgumentException("不同模型特征禁止混用");
        }
        // 自相似计算同时校验零向量及非有限值，防止损坏特征进入检索缓存。
        FeatureMath.cosine(record.getFeature(), record.getFeature());
        int dimensions = space.startsWith("yunet-auraface-512-") ? 512 : 128;
        if (record.getFeature().length != dimensions) {
            throw new IllegalArgumentException("特征维度与模型不符");
        }
    }

    private synchronized void refresh() {
        // 常规检索只查询版本号；其他实例提交档案变更后，下次读取会重新加载。
        long latest = db.metadata.selectById(1).getRevision();
        if (latest == revision) {
            return;
        }
        db.transaction(() -> {
            // 加载主档案和参考特征期间持有元数据锁，避免读取混合版本。
            db.lockModel(space);
            long locked = db.metadata.selectById(1).getRevision();
            Map<String, FaceRecord> next = new LinkedHashMap<>();
            for (FaceEntity row : db.faces.selectList(null)) {
                FaceRecord record = new FaceRecord(row.getRole(), row.getCode(), row.getName(), row.getImageBase64(), decode(row.getFeature()));
                record.setFeatureSpaceId(row.getFeatureSpaceId());
                validate(record);
                next.put(key(record.getRole(), record.getCode()), record);
            }
            for (ReferenceEntity row : db.samples.selectList(new QueryWrapper<ReferenceEntity>().select("role", "code", "feature").orderByAsc("id"))) {
                FaceRecord record = next.get(key(row.getRole(), row.getCode()));
                if (record == null) throw new IllegalStateException("参考照片缺少人员档案");
                float[] vector = decode(row.getFeature());
                FeatureMath.cosine(vector, vector);
                if (vector.length != record.getFeature().length) throw new IllegalStateException("参考特征维度损坏");
                record.getReferenceFeatures().add(vector);
            }
            // 全部校验完成后一次性发布快照，读取线程不会看到加载中的集合。
            cache = Collections.unmodifiableMap(next);
            revision = locked;
            return null;
        });
    }

    // 档案修改与版本递增在同一事务提交，回滚时不会发布无效版本。
    private void mutate(Supplier<Void> action) {
        db.transaction(() -> {
            db.lockModel(space);
            action.get();
            db.metadata.incrementRevision();
            return null;
        });
        revision = -1; // 其他实例也会在下次读取发现revision变化，不依赖消息广播送达。
    }

    @Override
    public void save(FaceRecord record) {
        save(record, false);
    }

    @Override
    public void saveNew(FaceRecord record) {
        save(record, true);
    }

    private void save(FaceRecord r, boolean onlyNew) {
        validate(r);
        mutate(() -> {
            int count = count(r.getRole(), r.getCode());
            // 存在性检查受元数据锁保护，跨实例并发录入也不能覆盖已有档案。
            if (onlyNew && count > 0) {
                throw new IllegalArgumentException("该角色下的人员编号已存在，请到已有档案添加参考照片");
            }
            String now = Instant.now().toString();
            if (count == 0) {
                FaceEntity row = new FaceEntity();
                row.setRole(r.getRole()); row.setCode(r.getCode()); row.setName(r.getName());
                row.setImageBase64(r.getImageBase64()); row.setFeature(encode(r.getFeature()));
                row.setFeatureSpaceId(space); row.setCreatedAt(now); row.setUpdatedAt(now);
                db.faces.insert(row);
            } else {
                // 显式 set 保留空值覆盖语义；不能使用默认忽略 null 的实体更新策略。
                db.faces.update(null, new UpdateWrapper<FaceEntity>().eq("role", r.getRole()).eq("code", r.getCode())
                        .set("name", r.getName()).set("image_base64", r.getImageBase64()).set("feature", encode(r.getFeature()))
                        .set("feature_space_id", space).set("updated_at", now));
            }
            // 替换主照片时清空旧参考特征；仅修改姓名则保留参考照片。
            db.samples.delete(new QueryWrapper<ReferenceEntity>().eq("role", r.getRole()).eq("code", r.getCode()));
            return null;
        });
    }

    private int count(String role, String code) {
        return db.faces.selectCount(person(role, code)).intValue();
    }

    // MyBatis-Plus 不使用单列主键 API 操作联合主键档案，始终同时限定角色和编号。
    private static QueryWrapper<FaceEntity> person(String role, String code) {
        return new QueryWrapper<FaceEntity>().eq("role", role).eq("code", code);
    }

    private void requirePerson(String role, String code) {
        if (count(role, code) != 1) {
            throw new IllegalArgumentException("人员档案不存在");
        }
    }

    @Override
    public void delete(String role, String code) {
        mutate(() -> {
            db.samples.delete(new QueryWrapper<ReferenceEntity>().eq("role", role).eq("code", code));
            db.faces.delete(person(role, code));
            return null;
        });
    }

    @Override
    public void updateName(String role, String code, String name) {
        if (name != null && name.length() > 128) {
            throw new IllegalArgumentException("姓名不能超过128字符");
        }
        mutate(() -> {
            requirePerson(role, code);
            db.faces.update(null, new UpdateWrapper<FaceEntity>().eq("role", role).eq("code", code)
                    .set("name", name).set("updated_at", Instant.now().toString()));
            return null;
        });
    }

    @Override
    public void addReference(FaceRecord sample) {
        validate(sample);
        mutate(() -> {
            requirePerson(sample.getRole(), sample.getCode());
            long count = db.samples.selectCount(new QueryWrapper<ReferenceEntity>().eq("role", sample.getRole()).eq("code", sample.getCode()));
            // 上限检查和插入共用事务锁，防止多个实例同时添加导致数量超限。
            if (count >= 4) {
                throw new IllegalArgumentException("每人最多5张照片（含主照片）");
            }
            ReferenceEntity row = new ReferenceEntity();
            row.setId(UUID.randomUUID().toString()); row.setRole(sample.getRole()); row.setCode(sample.getCode());
            row.setImage(sample.getImageBase64()); row.setFeature(encode(sample.getFeature())); row.setCreatedAt(Instant.now().toString());
            db.samples.insert(row);
            return null;
        });
    }

    @Override
    public void removeReference(String role, String code, String id) {
        mutate(() -> {
            if (db.samples.delete(new QueryWrapper<ReferenceEntity>().eq("role", role).eq("code", code).eq("id", id)) != 1) {
                throw new IllegalArgumentException("参考照片不存在");
            }
            return null;
        });
    }

    @Override
    public Optional<FaceRecord> find(String role, String code) {
        refresh();
        return Optional.ofNullable(cache.get(key(role, code))).map(MybatisPlusFaceRepository::copy);
    }

    @Override
    public List<FaceRecord> findAll() {
        return findByRole(null);
    }

    @Override
    public List<FaceRecord> findByRole(String role) {
        refresh();
        List<FaceRecord> out = new ArrayList<>();
        for (FaceRecord r : cache.values()) {
            if (role == null || role.isEmpty() || role.equals(r.getRole())) {
                out.add(copy(r));
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> detail(String role, String code) {
        FaceRecord person = find(role, code).orElseThrow(() -> new IllegalArgumentException("人员档案不存在"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", role);
        out.put("code", code);
        out.put("name", person.getName());
        out.put("hasImage", person.getImageBase64() != null);
        out.put("featureDimensions", person.getFeature().length);
        out.put("featureSpaceId", space);
        FaceEntity row = db.faces.selectOne(person(role, code).select("created_at", "updated_at"));
        if (row != null) {
            out.put("createdAt", row.getCreatedAt()); out.put("updatedAt", row.getUpdatedAt());
        }
        List<Map<String, Object>> samples = new ArrayList<>();
        for (ReferenceEntity ref : db.samples.selectList(new QueryWrapper<ReferenceEntity>()
                .select("id", "created_at").eq("role", role).eq("code", code).orderByAsc("id"))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", ref.getId()); item.put("createdAt", ref.getCreatedAt()); samples.add(item);
        }
        out.put("samples", samples);
        out.put("sampleCount", 1 + samples.size());
        return out;
    }

    @Override
    public String referenceImage(String role, String code, String id) {
        ReferenceEntity row = db.samples.selectOne(new QueryWrapper<ReferenceEntity>()
                .select("image").eq("role", role).eq("code", code).eq("id", id));
        if (row == null) throw new IllegalArgumentException("参考照片不存在");
        return row.getImage();
    }

    // 深拷贝特征数组，避免调用方修改公共检索快照。
    private static FaceRecord copy(FaceRecord r) {
        FaceRecord out = new FaceRecord(r.getRole(), r.getCode(), r.getName(), r.getImageBase64(), r.getFeature().clone());
        out.setFeatureSpaceId(r.getFeatureSpaceId());
        for (float[] f : r.getReferenceFeatures()) {
            out.getReferenceFeatures().add(f.clone());
        }
        return out;
    }

    // 统一采用小端 float32 格式存储，数据库只负责保存二进制内容。
    static byte[] encode(float[] vector) {
        ByteBuffer out = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : vector) {
            out.putFloat(f);
        }
        return out.array();
    }

    // 只接受128维或512维向量长度，加载时还需核验当前模型与特征空间。
    static float[] decode(byte[] bytes) {
        if (bytes == null || (bytes.length != 512 && bytes.length != 2048)) {
            throw new IllegalStateException("特征数据损坏");
        }
        float[] out = new float[bytes.length / 4];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
        return out;
    }

    @Override
    public void close() {
        cache = Collections.emptyMap();
    }
}
