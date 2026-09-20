package com.sunny.aiwear_b.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 图片上传成功后的响应数据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileUploadResponse {

    /** OSS 图片访问地址。 */
    private String url;

    /** 用户上传时使用的原文件名。 */
    private String fileName;

    /** 文件大小，单位为字节。 */
    private Long fileSize;
}
