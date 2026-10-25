package com.face.api.config;

import com.face.api.cluster.*;
import com.face.api.engine.FaceEngine;
import com.face.api.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.*;
import javax.sql.DataSource;

/** 只在选中MySQL或达梦时创建连接池；SQLite模式完全不要求外部数据库。 */
@Configuration
@ConditionalOnExpression("'${facedesk.storage.type:sqlite}' == 'mysql' or '${facedesk.storage.type:sqlite}' == 'dm'")
public class RelationalStorageConfiguration {
    @Bean @ConfigurationProperties("spring.datasource")
    public DataSourceProperties externalDataSourceProperties(){return new DataSourceProperties();}
    // 明确限定连接属性 Bean，避免与自动配置的同类型属性发生注入歧义。
    @Bean(destroyMethod="close") @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource externalDataSource(@org.springframework.beans.factory.annotation.Qualifier("externalDataSourceProperties") DataSourceProperties properties){return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();}
    @Bean public JdbcDatabase jdbcDatabase(DataSource dataSource,StorageProperties properties){return new JdbcDatabase(dataSource,properties.getType(),properties.isInitializeSchema());}
    @Bean(destroyMethod="close") public FaceRepository sharedFaceRepository(JdbcDatabase db,FaceEngine engine,FaceProperties properties){
        if(properties.getImportJson()!=null&&!properties.getImportJson().trim().isEmpty())throw new IllegalArgumentException("外部数据库不接受SQLite/JSON自动导入，请使用原图迁移工具");
        return new JdbcFaceRepository(db,engine.featureSpaceId());
    }
    // 先完成档案仓储初始化及模型校验，再注册节点租约。
    @Bean(destroyMethod="close") public NodeLease nodeLease(JdbcDatabase db,ClusterProperties cluster,FaceEngine engine,FaceRepository initializedRepository){return new NodeLease(db,cluster,engine.featureSpaceId());}
    @Bean public org.springframework.boot.actuate.health.HealthIndicator nodeLeaseHealthIndicator(NodeLease lease){return ()->lease.healthy()?org.springframework.boot.actuate.health.Health.up().build():org.springframework.boot.actuate.health.Health.outOfService().build();}
    @Bean(destroyMethod="close") public RecognitionHistoryRepository sharedHistoryRepository(JdbcDatabase db,ObjectMapper json,NodeLease lease){return new JdbcRecognitionHistoryRepository(db,json,lease);}
}
