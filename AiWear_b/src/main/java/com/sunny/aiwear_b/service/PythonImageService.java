package com.sunny.aiwear_b.service;

import com.sunny.aiwear_b.dto.response.PythonImageProcessResponse;

/**
 * Python 图片搜索处理服务。
 *
 * <p>该服务负责把 Java 上传完成后的 OSS 地址和用户 ID 发送给 Flask，
 * 由 Flask 生成图片描述、CLIP 向量并保存到 Redis。</p>
 */
public interface PythonImageService {

    /**
     * 请求 Python 服务处理一张已上传到 OSS 的图片。
     *
     * @param userId 用户 ID
     * @param ossUrl OSS 图片地址
     * @return Python 服务处理结果
     */
    PythonImageProcessResponse processImage(Long userId, String ossUrl);
}
