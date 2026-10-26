package com.face.api.cluster;

import com.face.api.repository.MybatisPlusDatabase;
import java.util.List;
import com.face.api.persistence.entity.NodeEntity;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.util.concurrent.*;

/** 共享库节点租约：只中断已过期节点的任务，不因其他实例启动误中断正常会话。 */
public final class NodeLease implements AutoCloseable {
    private final MybatisPlusDatabase db;
    private final ClusterProperties config;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"facedesk-node-lease");t.setDaemon(true);return t;});
    private volatile long lastSuccess;
    public NodeLease(MybatisPlusDatabase db,ClusterProperties config,String space){
        // 至少允许三个心跳周期，减少短暂调度抖动造成的误判。
        if(config.getLeaseMs()<3*config.getHeartbeatMs())throw new IllegalArgumentException("节点租约至少为心跳间隔的3倍");
        this.db=db;this.config=config;
        NodeEntity node = new NodeEntity();
        node.setNodeId(id()); node.setHeartbeatMs(System.currentTimeMillis()); node.setFeatureSpaceId(space);
        db.nodes.insert(node);
        lastSuccess=System.currentTimeMillis();
        timer.scheduleWithFixedDelay(this::heartbeat,config.getHeartbeatMs(),config.getHeartbeatMs(),TimeUnit.MILLISECONDS);
    }
    public String id(){return config.getRouteId();}
    // 入口按最近续约时间快速判断，历史写入时仍需在事务内检查数据库租约。
    public boolean healthy(){return System.currentTimeMillis()-lastSuccess<config.getLeaseMs();}
    /** 必须在写历史的事务内调用，使用行锁与租约过期处理互斥。 */
    public void requireWritable(){
        NodeEntity node = db.nodes.lockNode(id());
        Long beat = node == null ? null : node.getHeartbeatMs();
        if(beat==null||System.currentTimeMillis()-beat>=config.getLeaseMs())throw new IllegalStateException("节点租约已过期，请重新创建任务");
    }
    public void heartbeat(){
        try {
            db.transaction(()->{
                // 先校验再续约，过期节点不能自行复活并继续处理旧任务。
                requireWritable();
                db.nodes.update(null, new UpdateWrapper<NodeEntity>().eq("node_id", id()).set("heartbeat_ms", System.currentTimeMillis()));return null;
            });
            // 续约事务提交后才更新本地时间；数据库故障时请求最终会被租约门禁拒绝。
            lastSuccess=System.currentTimeMillis();expireStale(System.currentTimeMillis());
        }catch(RuntimeException failure){System.err.println("FaceDesk节点心跳或失效任务回收失败；将按租约拒绝请求，检查共享数据库连接");}
    }
    public void expireStale(long now){
        db.transaction(()->{
            // 锁住待回收节点，与该节点的续约和历史写入互斥。
            List<NodeEntity> stale=db.nodes.lockExpired(now-config.getLeaseMs());
            // 只中断过期节点仍在运行的任务，保留已完成结果及健康节点的任务。
            for(NodeEntity node:stale){
                db.history.interruptNode(node.getNodeId(), "处理实例租约过期，任务已中断，请重新提交");
                db.nodes.deleteById(node.getNodeId());
            }return null;
        });
    }
    @Override public void close(){
        // 主动关闭时尽力结束本节点任务；异常退出则由其他实例按超时回收。
        timer.shutdownNow();
        try{db.transaction(()->{
            db.history.interruptNode(id(), "处理实例已关闭，任务未完成");
            db.nodes.deleteById(id());return null;
        });}catch(RuntimeException ignored){}
    }
}
