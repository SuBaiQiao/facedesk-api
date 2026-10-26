package com.face.api.repository;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.face.api.persistence.entity.MetadataEntity;
import com.face.api.persistence.mapper.*;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.type.JdbcType;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.function.Supplier;

/**
 * MyBatis-Plus 共享关系库访问入口，Mapper 与事务管理器使用同一个连接池。
 */
public final class MybatisPlusDatabase {
    public final MetadataMapper metadata;
    public final FaceMapper faces;
    public final ReferenceMapper samples;
    public final NodeMapper nodes;
    public final HistoryMapper history;
    private final TransactionTemplate transactions;
    private final SqlSessionFactory sessionFactory;

    public MybatisPlusDatabase(DataSource source, String type, boolean initialize) {
        if (!"mysql".equals(type) && !"dm".equals(type)) {
            throw new IllegalArgumentException("关系型存储只支持mysql/dm");
        }
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        // 仅首次初始化空库时执行；正常集群启动不重复建表。
        if (initialize) {
            new ResourceDatabasePopulator(new ClassPathResource("db/" + type + "/schema.sql")).execute(source);
        }
        try {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setJdbcTypeForNull(JdbcType.NULL);
            // 每次检索必须看到其他实例已提交的版本；禁止一级/二级缓存复用旧查询。
            configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            configuration.setCacheEnabled(false);
            // 人脸原图与特征属于敏感数据，不通过 Mapper SQL 调试日志输出参数。
            configuration.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);
            MybatisPlusInterceptor plugins = new MybatisPlusInterceptor();
            PaginationInnerInterceptor pagination = new PaginationInnerInterceptor("dm".equals(type) ? DbType.DM : DbType.MYSQL);
            pagination.setMaxLimit(10000L);
            plugins.addInnerInterceptor(pagination);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(configuration);
            GlobalConfig global = GlobalConfigUtils.defaults();
            global.setBanner(false);
            factory.setGlobalConfig(global);
            factory.setPlugins(plugins);
            sessionFactory = factory.getObject();
            // 显式注册 Mapper，仅外部数据库模式创建会话工厂，SQLite 不依赖外部数据源。
            for (Class<?> mapper : new Class<?>[]{MetadataMapper.class, FaceMapper.class, ReferenceMapper.class, NodeMapper.class, HistoryMapper.class}) {
                configuration.addMapper(mapper);
            }
            SqlSessionTemplate session = new SqlSessionTemplate(sessionFactory);
            metadata = session.getMapper(MetadataMapper.class);
            faces = session.getMapper(FaceMapper.class);
            samples = session.getMapper(ReferenceMapper.class);
            nodes = session.getMapper(NodeMapper.class);
            history = session.getMapper(HistoryMapper.class);
        } catch (Exception failure) {
            throw new IllegalStateException("初始化 MyBatis-Plus 失败", failure);
        }
    }

    public SqlSessionFactory getSessionFactory() {
        return sessionFactory;
    }

    public <T> T transaction(Supplier<T> action) {
        return transactions.execute(status -> action.get());
    }

    /**
     * 必须在事务内调用，持有元数据行锁直到快照读取或档案写入完成。
     */
    public void lockModel(String space) {
        MetadataEntity row = metadata.lockMetadata();
        if (row == null || row.getSchemaVersion() != 1) {
            throw new IllegalStateException("共享数据库表结构版本不兼容");
        }
        if (row.getFeatureSpaceId() == null) {
            metadata.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<MetadataEntity>()
                    .eq("id", 1).set("feature_space_id", space));
        } else if (!row.getFeatureSpaceId().equals(space)) {
            throw new IllegalStateException("共享数据库模型指纹不匹配；所有实例必须使用相同模型，升级需统一停机后从原图迁移");
        }
    }
}
