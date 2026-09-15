package org.apache.seatunnel.web.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.seatunnel.web.dao.entity.FileResource;

import java.util.List;

@Mapper
public interface FileResourceMapper extends BaseMapper<FileResource> {

    List<FileResource> selectActiveByOwnerId(@Param("ownerId") Integer ownerId);

    FileResource selectByOwnerAndLogicalPath(
            @Param("ownerId") Integer ownerId,
            @Param("logicalPath") String logicalPath);

    FileResource selectByOwnerAndObjectKey(
            @Param("ownerId") Integer ownerId,
            @Param("objectKey") String objectKey);
}
