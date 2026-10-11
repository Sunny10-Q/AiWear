package com.sunny.aiwear_b.service;

import com.sunny.aiwear_b.dto.request.EditImageRequest;
import com.sunny.aiwear_b.dto.request.MergeImageRequest;
import com.sunny.aiwear_b.dto.request.SearchImageRequest;
import com.sunny.aiwear_b.dto.response.EditImageResponse;
import com.sunny.aiwear_b.dto.response.FileUploadResponse;
import com.sunny.aiwear_b.dto.response.MergeImageResponse;
import com.sunny.aiwear_b.dto.response.SearchImageResponse;
import com.sunny.aiwear_b.entity.FileRecord;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

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

    /**
     * 获取图片列表
     * @param authorization
     * @return
     */
    List<FileRecord> myImages(String authorization);


    /**
     * 调用 Python 服务审核图片。
     *
     * @param file 待审核的图片文件
     * @return true 表示审核通过
     */
    boolean validateImage(MultipartFile file);


    /**
     * 搜索图片
     * @param authorization
     * @param searchImageRequest
     * @return
     */
    List<SearchImageResponse> search(String authorization, SearchImageRequest searchImageRequest);

    /**
     * 图片编辑
     * @param authorization
     * @param editImageRequest
     * @return
     */
    EditImageResponse edit(String authorization, EditImageRequest editImageRequest);

    /**
     * 图片合并
     * @param authorization
     * @param mergeImageRequest
     * @return
     */
    MergeImageResponse merge(String authorization, MergeImageRequest mergeImageRequest);
}
