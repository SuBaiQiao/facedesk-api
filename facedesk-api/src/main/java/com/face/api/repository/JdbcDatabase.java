package com.face.api.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.function.Supplier;

/** 关系库公共事务边界；两种数据库使用同一领域仓储，差异集中在DDL与分页。 */
public final class JdbcDatabase {
    public final JdbcTemplate jdbc;
    public final TransactionTemplate transactions;
    public final String type;
    public JdbcDatabase(DataSource source,String type,boolean initialize) {
        if(!"mysql".equals(type)&&!"dm".equals(type))throw new IllegalArgumentException("关系型存储只支持mysql/dm");
        this.type=type;jdbc=new JdbcTemplate(source);
        transactions=new TransactionTemplate(new DataSourceTransactionManager(source));
        // 建表仅用于首次初始化空库，集群正常启动时应关闭，避免多个实例重复执行。
        if(initialize)new ResourceDatabasePopulator(new ClassPathResource("db/"+type+"/schema.sql")).execute(source);
    }
    public <T>T transaction(Supplier<T> action){return transactions.execute(status->action.get());}
    // 必须在事务中调用；元数据行锁同时保护模型指纹、档案写入和一致快照读取。
    public void lockModel(String space){
        String actual=jdbc.queryForObject("SELECT feature_space_id FROM face_metadata WHERE id=1 FOR UPDATE",String.class);
        if(jdbc.queryForObject("SELECT schema_version FROM face_metadata WHERE id=1",Integer.class)!=1)throw new IllegalStateException("共享数据库表结构版本不兼容");
        if(actual==null){jdbc.update("UPDATE face_metadata SET feature_space_id=? WHERE id=1",space);}
        else if(!actual.equals(space))throw new IllegalStateException("共享数据库模型指纹不匹配；所有实例必须使用相同模型，升级需统一停机后从原图迁移");
    }
    // 调用方需按数据库类型调整分页参数顺序，达梦与 MySQL 的占位符顺序不同。
    public String pageSql(String query){return query+("dm".equals(type)?" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY":" LIMIT ? OFFSET ?");}
}
