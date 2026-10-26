package com.face.api.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.face.api.persistence.entity.FaceEntity;
import org.apache.ibatis.annotations.*;

/** Face 表访问，通用增删改查由 MyBatis-Plus 注入。 */
public interface FaceMapper extends BaseMapper<FaceEntity> {
}
