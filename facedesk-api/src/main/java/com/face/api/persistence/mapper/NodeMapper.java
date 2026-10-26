package com.face.api.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.face.api.persistence.entity.NodeEntity;
import org.apache.ibatis.annotations.*;

/** Node 表访问，通用增删改查由 MyBatis-Plus 注入。 */
public interface NodeMapper extends BaseMapper<NodeEntity> {
    @Select("SELECT node_id,heartbeat_ms,feature_space_id FROM facedesk_nodes WHERE node_id=#{id} FOR UPDATE")
    NodeEntity lockNode(@Param("id") String id);

    @Select("SELECT node_id,heartbeat_ms,feature_space_id FROM facedesk_nodes WHERE heartbeat_ms < #{cutoff} FOR UPDATE")
    java.util.List<NodeEntity> lockExpired(@Param("cutoff") long cutoff);

}
