package com.sunny.aiwear_b.dto.response;

import lombok.Data;

/**
 * Python 图片搜索处理接口的响应结果。
 */
@Data
public class PythonImageProcessResponse {

    /** qwen-vl-max 返回的图片描述。 */
    private String description;

    /** CLIP 图片向量维度，正常应为 512。 */
    private Integer embeddingDim;

    /** Redis 中图片记录对应的唯一 ID。 */
    private String imageId;

    /** Python 服务是否处理成功。 */
    private Boolean success;

    /** 处理失败时的错误信息。 */
    private String error;
}
