package com.face.api.repository;

import com.face.api.engine.FeatureMath;
import com.face.api.model.FaceRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;

import java.io.IOException;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** 单实例 SQLite 持久化，事务提交后发布只读快照；识别路径不访问数据库。 */
public class SqliteFaceRepository implements FaceRepository, AutoCloseable {
    private static final String UPSERT = "INSERT INTO face_records "
        + "(role,code,name,image_base64,feature,feature_space_id) VALUES (?,?,?,?,?,?) "
        + "ON CONFLICT(role,code) DO UPDATE SET name=excluded.name,image_base64=excluded.image_base64,"
        + "feature=excluded.feature,feature_space_id=excluded.feature_space_id,updated_at=CURRENT_TIMESTAMP";
    private final String featureSpaceId;
    private final Path path;
    private Connection connection;
    private FileChannel lockChannel;
    private FileLock lock;
    private volatile Map<Key, FaceRecord> snapshot = Collections.emptyMap();
    private boolean closed;

    public SqliteFaceRepository(String file, String featureSpaceId) { this(file, featureSpaceId, null); }

    public SqliteFaceRepository(String file, String featureSpaceId, com.face.api.engine.FaceEngine migrationEngine) {
        if (file == null || file.trim().isEmpty() || file.toLowerCase(Locale.ROOT).endsWith(".json"))
            throw new IllegalArgumentException("face.storage-path 必须是 SQLite 数据库路径，例如 ./data/face.db，不能使用旧 JSON 路径");
        if (featureSpaceId == null || featureSpaceId.isEmpty()) throw new IllegalArgumentException("缺少模型指纹");
        this.path = Paths.get(file).toAbsolutePath().normalize();
        this.featureSpaceId = featureSpaceId;
        try {
            Files.createDirectories(path.getParent());
            // 避免多个服务进程各自持有过期缓存；外部工具仍可连接数据库读取。
            lockChannel = FileChannel.open(Paths.get(path + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (lock == null) throw new IllegalStateException("数据库已由其他服务实例使用: " + path);
            connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("CREATE TABLE IF NOT EXISTS face_reference_samples (id TEXT PRIMARY KEY,role TEXT NOT NULL,code TEXT NOT NULL,image TEXT NOT NULL,feature BLOB NOT NULL CHECK(length(feature) IN (512,2048)),created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_reference_person ON face_reference_samples(role,code)");
                statement.execute("CREATE TABLE IF NOT EXISTS face_metadata ("
                    + "id INTEGER PRIMARY KEY CHECK(id=1),schema_version INTEGER NOT NULL,feature_space_id TEXT NOT NULL)");
                statement.execute("CREATE TABLE IF NOT EXISTS face_records ("
                    + "role TEXT NOT NULL,code TEXT NOT NULL,name TEXT,image_base64 TEXT,"
                    + "feature BLOB NOT NULL CHECK(length(feature) IN (512,2048)),feature_space_id TEXT NOT NULL,"
                    + "created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                    + "updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(role,code))");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR IGNORE INTO face_metadata(id,schema_version,feature_space_id) VALUES(1,1,?)")) {
                statement.setString(1, featureSpaceId); statement.executeUpdate();
            }
            // 建表后、加载内存快照前迁移旧模型；迁移失败时终止启动，禁止带混合维度继续服务。
            ModelFeatureMigration.run(connection,path,featureSpaceId,migrationEngine);
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT schema_version,feature_space_id FROM face_metadata WHERE id=1")) {
                if (!rows.next() || rows.getInt(1) != 1 || !featureSpaceId.equals(rows.getString(2)))
                    throw new IllegalStateException("数据库版本或模型指纹不匹配，请迁移原图到新数据库");
            }
            Map<Key, FaceRecord> loaded = new HashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT role,code,name,image_base64,feature,feature_space_id FROM face_records")) {
                while (rows.next()) {
                    FaceRecord record = new FaceRecord(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getString(4), decode(rows.getBytes(5)));
                    record.setFeatureSpaceId(rows.getString(6)); validate(record);
                    loaded.put(key(record), record);
                }
            }
            try(Statement s=connection.createStatement();ResultSet rows=s.executeQuery("SELECT role,code,feature FROM face_reference_samples ORDER BY id")) {
                while(rows.next()) { FaceRecord person=loaded.get(new Key(rows.getString(1),rows.getString(2))); if(person==null)throw new IllegalStateException("参考照片缺少人员档案");float[] vector=decode(rows.getBytes(3));FeatureMath.cosine(vector,vector);if(vector.length!=person.getFeature().length)throw new IllegalStateException("参考特征模型维度不匹配");person.getReferenceFeatures().add(vector); }
            }
            snapshot = Collections.unmodifiableMap(loaded);
        } catch (Exception e) {
            close();
            throw new IllegalStateException("初始化人脸 SQLite 数据库失败: " + path + "；" + e.getMessage(), e);
        }
    }

    // 同步锁覆盖查重与写入；同一服务内并发创建相同role/code时最多一个成功。
    @Override public synchronized void saveNew(FaceRecord record) {
        ensureOpen();validate(record);
        if(snapshot.containsKey(key(record))) throw new IllegalArgumentException("该角色下的人员编号已存在，请更换编号，或到已有档案详情中添加参考照片");
        save(record);
    }
    @Override public synchronized void save(FaceRecord record) {
        ensureOpen(); validate(record);
        FaceRecord stored = copy(record);
        // 更换主图代表重新建档，附加样本在同一事务中清空；只改姓名走updateName保留样本。
        stored.setReferenceFeatures(new ArrayList<>());
        Map<Key, FaceRecord> updated = new HashMap<>(snapshot); updated.put(key(stored), stored);
        transaction(() -> {
            try(PreparedStatement clear=connection.prepareStatement("DELETE FROM face_reference_samples WHERE role=? AND code=?")){clear.setString(1,record.getRole());clear.setString(2,record.getCode());clear.executeUpdate();}
            try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                bind(statement, stored); statement.executeUpdate();
            }
        }, updated);
    }

    @Override public synchronized void delete(String role, String code) {
        ensureOpen();
        if (role == null || code == null) throw new IllegalArgumentException("role/code 不能为空");
        Map<Key, FaceRecord> updated = new HashMap<>(snapshot); updated.remove(new Key(role, code));
        transaction(() -> {
            try(PreparedStatement clear=connection.prepareStatement("DELETE FROM face_reference_samples WHERE role=? AND code=?")){clear.setString(1,role);clear.setString(2,code);clear.executeUpdate();}
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM face_records WHERE role=? AND code=?")) {
                statement.setString(1, role); statement.setString(2, code); statement.executeUpdate();
            }
        }, updated);
    }

    @Override public synchronized void updateName(String role, String code, String name) {
        ensureOpen();
        Key id = new Key(role, code);
        FaceRecord existing = snapshot.get(id);
        if (existing == null) throw new IllegalArgumentException("人员记录不存在，请刷新列表");
        FaceRecord updatedRecord = copy(existing); updatedRecord.setName(name);
        Map<Key, FaceRecord> updated = new HashMap<>(snapshot); updated.put(id, updatedRecord);
        transaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE face_records SET name=?,updated_at=CURRENT_TIMESTAMP WHERE role=? AND code=?")) {
                statement.setString(1, name); statement.setString(2, role); statement.setString(3, code);
                if (statement.executeUpdate() != 1) throw new SQLException("人员记录不存在");
            }
        }, updated);
    }

    /** 仅支持向空库显式导入同模型 SFace JSON，整批校验、整批事务，不修改源文件。 */
    public synchronized void importJson(Path source) {
        ensureOpen();
        if (!snapshot.isEmpty()) throw new IllegalStateException("JSON 导入只允许空数据库，避免覆盖现有人员");
        if (source.toAbsolutePath().normalize().equals(path)) throw new IllegalArgumentException("导入源不能是目标数据库");
        final List<FaceRecord> records;
        try {
            records = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readValue(source.toFile(), new TypeReference<List<FaceRecord>>() { });
        } catch (IOException e) { throw new IllegalArgumentException("读取待迁移 JSON 失败: " + source, e); }
        if (records == null) throw new IllegalArgumentException("导入内容必须是人脸记录数组");
        Map<Key, FaceRecord> updated = new HashMap<>();
        for (FaceRecord record : records) {
            validate(record);
            FaceRecord stored = copy(record);
            if (updated.put(key(stored), stored) != null) throw new IllegalArgumentException("导入存在重复 role/code: " + stored.getCode());
        }
        transaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                for (FaceRecord record : updated.values()) { bind(statement, record); statement.addBatch(); }
                statement.executeBatch();
            }
        }, updated);
    }

    // 先提交SQLite再发布新内存快照；写盘失败时读请求仍看到原有底库。
    private void transaction(SqlAction action, Map<Key, FaceRecord> updated) {
        try {
            connection.setAutoCommit(false);
            action.run(); connection.commit();
            snapshot = Collections.unmodifiableMap(updated);
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
            throw new IllegalStateException("人脸数据库事务失败，内存底库未更新", e);
        } finally {
            try { connection.setAutoCommit(true); }
            catch (SQLException e) { throw new IllegalStateException("恢复数据库事务状态失败", e); }
        }
    }
    private void bind(PreparedStatement statement, FaceRecord record) throws SQLException {
        statement.setString(1, record.getRole()); statement.setString(2, record.getCode());
        statement.setString(3, record.getName()); statement.setString(4, record.getImageBase64());
        statement.setBytes(5, encode(record.getFeature())); statement.setString(6, record.getFeatureSpaceId());
    }
    private void validate(FaceRecord record) {
        if (record == null || record.getRole() == null || record.getRole().trim().isEmpty()
            || record.getCode() == null || record.getCode().trim().isEmpty())
            throw new IllegalArgumentException("role/code 不能为空");
        if (!featureSpaceId.equals(record.getFeatureSpaceId()))
            throw new IllegalArgumentException("旧算法或不同模型特征禁止混用，请使用原图重新采集或迁移");
        FeatureMath.cosine(record.getFeature(), record.getFeature());
        if (record.getFeature().length != (featureSpaceId.startsWith("yunet-auraface-512-") ? 512 : 128)) throw new IllegalArgumentException("特征维度与模型不符");
    }
    // 使用小端float32存储：SFace128维占512字节，AuraFace512维占2048字节。
    private static byte[] encode(float[] feature) {
        ByteBuffer bytes = ByteBuffer.allocate(feature.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : feature) bytes.putFloat(value);
        return bytes.array();
    }
    private static float[] decode(byte[] bytes) {
        if (bytes == null || (bytes.length != 512 && bytes.length != 2048)) throw new IllegalArgumentException("数据库特征维度损坏");
        float[] feature = new float[bytes.length / 4];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(feature); return feature;
    }
    // 复制主向量和参考向量，避免业务层修改返回对象时污染并发识别使用的缓存。
    private static FaceRecord copy(FaceRecord record) {
        FaceRecord copy = new FaceRecord(record.getRole(), record.getCode(), record.getName(),
            record.getImageBase64(), record.getFeature().clone());
        copy.setFeatureSpaceId(record.getFeatureSpaceId());for(float[] vector:record.getReferenceFeatures())copy.getReferenceFeatures().add(vector.clone());return copy;
    }
    private static Key key(FaceRecord record) { return new Key(record.getRole(), record.getCode()); }
    @Override public Optional<FaceRecord> find(String role, String code) {
        FaceRecord record = snapshot.get(new Key(role, code));
        return record == null ? Optional.empty() : Optional.of(copy(record));
    }
    @Override public List<FaceRecord> findByRole(String role) {
        Map<Key, FaceRecord> current = snapshot;
        List<FaceRecord> result = new ArrayList<>();
        for (FaceRecord record : current.values())
            if (role == null || role.isEmpty() || role.equals(record.getRole())) result.add(copy(record));
        return result;
    }
    @Override public List<FaceRecord> findAll() { return findByRole(null); }
    private void ensureOpen() { if (closed) throw new IllegalStateException("人脸数据库已关闭"); }
    @Override public synchronized void addReference(FaceRecord sample) {
        ensureOpen();validate(sample);Key key=key(sample);FaceRecord person=snapshot.get(key);
        if(person==null)throw new IllegalArgumentException("人员档案不存在");
        if(person.getReferenceFeatures().size()>=4)throw new IllegalArgumentException("每人最多 5 张照片（含主照片）");
        FaceRecord updatedPerson=copy(person);updatedPerson.getReferenceFeatures().add(sample.getFeature().clone());Map<Key,FaceRecord> updated=new HashMap<>(snapshot);updated.put(key,updatedPerson);
        transaction(()->{try(PreparedStatement s=connection.prepareStatement("INSERT INTO face_reference_samples(id,role,code,image,feature) VALUES(?,?,?,?,?)")){s.setString(1,UUID.randomUUID().toString());s.setString(2,sample.getRole());s.setString(3,sample.getCode());s.setString(4,sample.getImageBase64());s.setBytes(5,encode(sample.getFeature()));s.executeUpdate();}},updated);
    }
    @Override public synchronized void removeReference(String role,String code,String id) {
        ensureOpen();FaceRecord person=snapshot.get(new Key(role,code));if(person==null)throw new IllegalArgumentException("人员档案不存在");
        FaceRecord updatedPerson=copy(person);updatedPerson.setReferenceFeatures(new ArrayList<>());Map<Key,FaceRecord> updated=new HashMap<>(snapshot);updated.put(new Key(role,code),updatedPerson);
        transaction(()->{
            try(PreparedStatement s=connection.prepareStatement("DELETE FROM face_reference_samples WHERE role=? AND code=? AND id=?")){s.setString(1,role);s.setString(2,code);s.setString(3,id);if(s.executeUpdate()!=1)throw new SQLException("参考照片不存在");}
            try(PreparedStatement s=connection.prepareStatement("SELECT feature FROM face_reference_samples WHERE role=? AND code=? ORDER BY id")){s.setString(1,role);s.setString(2,code);try(ResultSet rows=s.executeQuery()){while(rows.next())updatedPerson.getReferenceFeatures().add(decode(rows.getBytes(1)));}}
        },updated);
    }
    // 详情仅返回档案和样本元数据，图片独立读取，特征向量不暴露到详情页面。
    @Override public synchronized Map<String,Object> detail(String role,String code) {
        ensureOpen();FaceRecord person=find(role,code).orElseThrow(()->new IllegalArgumentException("人员档案不存在"));Map<String,Object> result=new LinkedHashMap<>();
        result.put("role",role);result.put("code",code);result.put("name",person.getName());result.put("hasImage",person.getImageBase64()!=null);result.put("sampleCount",1+person.getReferenceFeatures().size());result.put("featureDimensions",person.getFeature().length);result.put("featureSpaceId",person.getFeatureSpaceId());
        try {
            try(PreparedStatement s=connection.prepareStatement("SELECT created_at,updated_at FROM face_records WHERE role=? AND code=?")){s.setString(1,role);s.setString(2,code);try(ResultSet rows=s.executeQuery()){if(rows.next()){result.put("createdAt",rows.getString(1));result.put("updatedAt",rows.getString(2));}}}
            List<Map<String,Object>> samples=new ArrayList<>();try(PreparedStatement s=connection.prepareStatement("SELECT id,created_at FROM face_reference_samples WHERE role=? AND code=? ORDER BY id")){s.setString(1,role);s.setString(2,code);try(ResultSet rows=s.executeQuery()){while(rows.next()){Map<String,Object> sample=new LinkedHashMap<>();sample.put("id",rows.getString(1));sample.put("createdAt",rows.getString(2));samples.add(sample);}}}result.put("samples",samples);return result;
        }catch(SQLException e){throw new IllegalStateException("读取人员详情失败",e);}
    }
    @Override public synchronized String referenceImage(String role,String code,String id) {
        try(PreparedStatement s=connection.prepareStatement("SELECT image FROM face_reference_samples WHERE role=? AND code=? AND id=?")){s.setString(1,role);s.setString(2,code);s.setString(3,id);try(ResultSet rows=s.executeQuery()){if(rows.next())return rows.getString(1);}}
        catch(SQLException e){throw new IllegalStateException("读取参考照片失败",e);}throw new IllegalArgumentException("参考照片不存在");
    }
    @Override public synchronized void close() {
        closed = true;
        try { if (connection != null) connection.close(); } catch (SQLException ignored) { }
        try { if (lock != null) lock.release(); } catch (IOException ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) { }
    }
    private interface SqlAction { void run() throws SQLException; }
    private static final class Key {
        final String role, code;
        Key(String role, String code) { this.role = role; this.code = code; }
        @Override public int hashCode() { return Objects.hash(role, code); }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false;
            Key key = (Key) other; return Objects.equals(role, key.role) && Objects.equals(code, key.code);
        }
    }
}
