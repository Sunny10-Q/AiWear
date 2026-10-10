package com.sunny.aiwear_b.controller;

import com.sunny.aiwear_b.common.Result;
import com.sunny.aiwear_b.dto.request.EditImageRequest;
import com.sunny.aiwear_b.dto.request.SearchImageRequest;
import com.sunny.aiwear_b.dto.response.EditImageResponse;
import com.sunny.aiwear_b.dto.response.FileUploadResponse;
import com.sunny.aiwear_b.dto.response.SearchImageResponse;
import com.sunny.aiwear_b.entity.FileRecord;
import com.sunny.aiwear_b.log.ApiLog;
import com.sunny.aiwear_b.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文件接口控制器。
 *
 * 负责接收前端上传的图片请求，并将具体业务交给文件服务层处理。
 */
@RequestMapping("/api/file")
@RestController
@Slf4j
public class FileController {

    @Autowired
    private FileService fileService;

    /**
     * 上传图片到阿里云 OSS，并保存图片记录。
     *
     * @param authorization Bearer 格式的登录令牌
     * @param file           前端上传的图片文件
     * @return 图片访问地址、原文件名和文件大小
     */
    @ApiLog
    @PostMapping(value = "/upload/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<FileUploadResponse> uploadImage(
            @RequestHeader(value = "Authorization") String authorization,
            @RequestPart("file") MultipartFile file) {
        try {
            FileUploadResponse response = fileService.uploadImage(file, authorization);
            return Result.success("图片上传成功", response);
        } catch (IllegalArgumentException e) {
            return Result.clientError(e.getMessage());
        } catch (Exception e) {
            log.error("图片上传失败", e);
            return Result.serverError("图片上传失败，请稍后重试");
        }
    }


    /**
     * 获取图片列表
     * @param authorization
     * @return
     */
    @ApiLog
    @GetMapping("/my-images")
    public Result<List<FileRecord>> getMyImages(@RequestHeader(value = "Authorization") String authorization){
        List<FileRecord> fileRecords=fileService.myImages(authorization);
        return Result.success("查询成功",fileRecords);
    }


    /**
     * 图片搜索（支持文搜图和图搜图）
     * @param authorization
     * @param query
     * @param file
     * @return
     */
    @ApiLog
    @PostMapping("/search")
    public Result<List<SearchImageResponse>> search(
            @RequestHeader(value = "Authorization") String authorization,
            @RequestParam(value = "query",required = false) String query,
            @RequestParam(value = "file",required = false) MultipartFile file
    ){
        SearchImageRequest searchImageRequest=new SearchImageRequest();
        searchImageRequest.setQuery(query);
        searchImageRequest.setFile(file);
        return Result.success("查询成功",fileService.search(authorization,searchImageRequest));

    }


    /**
     * 图片编辑
     * @param editImageRequest
     * @param authorization
     * @return
     */
    @ApiLog
    @PostMapping("/edit")
    public Result<EditImageResponse> edit(
            @RequestBody @Validated EditImageRequest editImageRequest,
            @RequestHeader(value = "Authorization") String authorization
    ){

        return Result.success("编辑成功",fileService.edit(authorization,editImageRequest));

    }




}
