package com.face.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import javax.validation.constraints.Pattern;

/** 三选一存储后端；外部数据库显式配置连接，不与SQLite双写。 */
@Validated
@ConfigurationProperties("facedesk.storage")
public class StorageProperties {
    @Pattern(regexp="sqlite|mysql|dm")
    private String type="sqlite";
    // 默认不自动建表；首次初始化空库可启用，集群正常启动应关闭。
    private boolean initializeSchema;
    public String getType(){return type;}
    public void setType(String type){this.type=type;}
    public boolean isInitializeSchema(){return initializeSchema;}
    public void setInitializeSchema(boolean value){initializeSchema=value;}
}
