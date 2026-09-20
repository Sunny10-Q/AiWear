package com.sunny.aiwear_b.service;

import com.sunny.aiwear_b.dto.response.FileUploadResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件业务服务接口。
 */
public interface FileService {

    /**
     * 上传图片并保存文件记录。
     *
     * @param file          上传的图片文件
     * @param authorization Bearer 格式的登录令牌
     * @return 图片上传结果
     */
    FileUploadResponse uploadImage(MultipartFile file, String authorization);
}
