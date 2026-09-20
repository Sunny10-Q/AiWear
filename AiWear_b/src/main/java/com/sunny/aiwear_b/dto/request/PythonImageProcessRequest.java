package com.sunny.aiwear_b.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Java 上传服务调用 Python 图片搜索处理接口时的请求参数。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PythonImageProcessRequest {

    /** OSS 图片访问地址。 */
    private String ossUrl;

    /** 上传用户 ID。 */
    private Long userId;
}
