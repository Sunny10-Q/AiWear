package com.sunny.aiwear_b.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户上传文件记录。
 */
@Data
@TableName("files")
public class FileRecord implements Serializable {

    /** 文件记录主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 上传文件所属用户 ID。 */
    private Long userId;

    /** 原始文件名。 */
    private String fileName;

    /** 文件大小，单位为字节。 */
    private Long fileSize;

    /** 文件在 OSS 中的访问地址。 */
    private String ossUrl;
}
