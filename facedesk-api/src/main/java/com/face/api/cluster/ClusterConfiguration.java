package com.face.api.cluster;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.face.api.config.StorageProperties;
import com.face.api.engine.FaceEngine;
import org.springframework.beans.factory.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
public class ClusterConfiguration {
    /** 在服务注册生命周期启动前写入进程路由令牌，网关只路由到Nacos已知实例。 */
    @Bean public SmartInitializingSingleton clusterRegistration(ClusterProperties cluster,StorageProperties storage,
            ObjectProvider<NacosDiscoveryProperties> nacos,Environment environment,FaceEngine engine){
        return ()->{
            if(!cluster.isEnabled())return;
            // SQLite 只适用于单实例；集群必须显式选用共享关系库。
            if("sqlite".equals(storage.getType()))throw new IllegalStateException("SQLite只支持单机；分布式模式请选择mysql或dm");
            if(!environment.getProperty("spring.cloud.nacos.discovery.enabled",Boolean.class,false))throw new IllegalStateException("分布式部署必须启用Nacos服务发现");
            NacosDiscoveryProperties properties=nacos.getIfAvailable();
            if(properties==null)throw new IllegalStateException("未加载Nacos服务发现配置");
            properties.getMetadata().put("facedesk-route-id",cluster.getRouteId());
            // 注册模型指纹便于排查配置，一致性约束仍由共享库元数据校验。
            properties.getMetadata().put("facedesk-model",engine.featureSpaceId());
        };
    }
}
