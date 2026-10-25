package com.face.api.cluster;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import javax.validation.constraints.Min;
import java.util.UUID;

/** 路由令牌每次进程启动重新生成，避免重启后的新进程接收上次的内存任务。 */
@Validated
@ConfigurationProperties("facedesk.cluster")
public class ClusterProperties {
    private boolean enabled;
    @Min(1000) private long heartbeatMs=5000;
    @Min(10000) private long leaseMs=30000;
    // 每次进程启动使用新令牌，重启后的实例不能接管旧进程内存中的任务。
    private final String routeId=UUID.randomUUID().toString().replace("-","");
    public boolean isEnabled(){return enabled;}
    public void setEnabled(boolean value){enabled=value;}
    public long getHeartbeatMs(){return heartbeatMs;}
    public void setHeartbeatMs(long value){heartbeatMs=value;}
    public long getLeaseMs(){return leaseMs;}
    public void setLeaseMs(long value){leaseMs=value;}
    public String getRouteId(){return routeId;}
    // 集群任务携带所属进程前缀，供网关将轮询、停止等请求转回创建节点。
    public String newTaskId(){return (enabled?routeId+".":"")+UUID.randomUUID();}
}
