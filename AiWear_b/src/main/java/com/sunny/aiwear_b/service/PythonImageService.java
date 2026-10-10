package com.sunny.aiwear_b.service;

import com.sunny.aiwear_b.dto.request.EditImageRequest;
import com.sunny.aiwear_b.dto.request.SearchImageRequest;
import com.sunny.aiwear_b.dto.response.EditImageResponse;
import com.sunny.aiwear_b.dto.response.PythonImageProcessResponse;
import com.sunny.aiwear_b.dto.response.SearchImageResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Python 图片搜索处理服务。
 *
 * <p>该服务负责把 Java 上传完成后的 OSS 地址和用户 ID 发送给 Flask，
 * 由 Flask 生成图片描述、CLIP 向量并保存到 Redis。</p>
 */
public interface PythonImageService {

    /**
     * 调用 Python 审核接口检查上传图片。
     *
     * @param file 待审核的图片文件
     * @return true 表示审核通过，false 表示审核不通过
     */
    boolean validateImage(MultipartFile file);

    /**
     * 请求 Python 服务处理一张已上传到 OSS 的图片。
     *
     * @param userId 用户 ID
     * @param ossUrl OSS 图片地址
     * @return Python 服务处理结果
     */
    PythonImageProcessResponse processImage(Long userId, String ossUrl);


    /**
     * 搜索图片
     * @param userId
     * @param searchImageRequest
     * @return
     */
    List<SearchImageResponse> search(Long userId, SearchImageRequest searchImageRequest);

    /**
     * 图片搜索
     * @param userId
     * @param editImageRequest
     * @return
     */
    EditImageResponse edit(Long userId, EditImageRequest editImageRequest);
}
