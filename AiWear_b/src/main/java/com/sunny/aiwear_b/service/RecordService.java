package com.sunny.aiwear_b.service;

import com.sunny.aiwear_b.dto.request.EditImageRequest;
import com.sunny.aiwear_b.dto.request.MergeImageRequest;
import com.sunny.aiwear_b.dto.response.EditImageResponse;
import com.sunny.aiwear_b.dto.response.MergeImageResponse;
import com.sunny.aiwear_b.entity.Record;

import java.util.List;

public interface RecordService {

    /**
     * 查看调用记录
     * @param userId
     * @param action
     * @return
     */
    List<Record> my(Long userId, String action);

    /**
     * 编辑生成调用记录
     * @param userId
     * @param editImageRequest
     * @param editImageResponse
     */
    void editSave(Long userId, EditImageRequest editImageRequest, EditImageResponse editImageResponse);

    /**
     * 合并生成调用记录
     * @param userId
     * @param mergeImageRequest
     * @param mergeImageResponse
     */
    void mergeSave(Long userId, MergeImageRequest mergeImageRequest, MergeImageResponse mergeImageResponse);
}
