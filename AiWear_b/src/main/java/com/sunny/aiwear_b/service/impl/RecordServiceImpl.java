package com.sunny.aiwear_b.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sunny.aiwear_b.dto.request.EditImageRequest;
import com.sunny.aiwear_b.dto.request.MergeImageRequest;
import com.sunny.aiwear_b.dto.response.EditImageResponse;
import com.sunny.aiwear_b.dto.response.MergeImageResponse;
import com.sunny.aiwear_b.entity.Record;
import com.sunny.aiwear_b.mapper.RecordMapper;
import com.sunny.aiwear_b.service.RecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class RecordServiceImpl implements RecordService {

    @Autowired
    private RecordMapper recordMapper;

    /**
     * 查看调用记录
     * @param userId
     * @param action
     * @return
     */
    @Override
    public List<Record> my(Long userId, String action) {

        LambdaQueryWrapper<Record> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Record::getUserId, userId);
        if(action!=null && !action.isBlank()){
            queryWrapper.eq(Record::getAction, action);
        }
        return recordMapper.selectList(queryWrapper);
    }

    /**
     * 编辑调用记录新增
     * @param userId
     * @param editImageRequest
     * @param editImageResponse
     */
    @Override
    public void editSave(Long userId, EditImageRequest editImageRequest, EditImageResponse editImageResponse) {
        Record record = new Record();
        record.setUserId(userId);
        record.setAction("edit");
        record.setInstruction(editImageRequest.getInstruction());
        record.setInputOssUrl1(editImageRequest.getImage());
        record.setResultUrl(editImageResponse.getUrl());
        record.setOutputOssUrl(editImageResponse.getSaveUrl());
        recordMapper.insert(record);
    }

    /**
     * 合并调用记录新增
     * @param userId
     * @param mergeImageRequest
     * @param mergeImageResponse
     */
    @Override
    public void mergeSave(Long userId, MergeImageRequest mergeImageRequest, MergeImageResponse mergeImageResponse) {
        Record record = new Record();
        record.setUserId(userId);
        record.setAction("merge");
        record.setInstruction(mergeImageRequest.getInstruction());
        record.setInputOssUrl1(mergeImageRequest.getImage1());
        record.setInputOssUrl2(mergeImageRequest.getImage2());
        record.setResultUrl(mergeImageResponse.getUrl());
        record.setOutputOssUrl(mergeImageResponse.getSaveUrl());
        recordMapper.insert(record);
    }
}
