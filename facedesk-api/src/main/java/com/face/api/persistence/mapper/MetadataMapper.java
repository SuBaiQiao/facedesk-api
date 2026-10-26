package com.face.api.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.face.api.persistence.entity.MetadataEntity;
import org.apache.ibatis.annotations.*;

/** Metadata 表访问，通用增删改查由 MyBatis-Plus 注入。 */
public interface MetadataMapper extends BaseMapper<MetadataEntity> {
    // 调用方必须开启 Spring 事务，锁持续到档案读写和版本更新一起提交。
    @Select("SELECT id,schema_version,feature_space_id,revision FROM face_metadata WHERE id=1 FOR UPDATE")
    MetadataEntity lockMetadata();

    @Update("UPDATE face_metadata SET revision=revision+1 WHERE id=1")
    int incrementRevision();

}
