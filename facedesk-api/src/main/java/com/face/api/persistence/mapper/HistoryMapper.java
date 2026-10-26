package com.face.api.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.face.api.persistence.entity.HistoryEntity;
import org.apache.ibatis.annotations.*;

/** History 表访问，通用增删改查由 MyBatis-Plus 注入。 */
public interface HistoryMapper extends BaseMapper<HistoryEntity> {
    // 失效回收只改任务终态，已记录的人脸判定保持原值；参数全部绑定。
    @Update("UPDATE recognition_history SET status=CASE WHEN kind='TASK' THEN 'ERROR' ELSE status END,task_state='INTERRUPTED',task_error=#{error} WHERE node_id=#{node} AND task_state='RUNNING'")
    int interruptNode(@Param("node") String node, @Param("error") String error);

}
