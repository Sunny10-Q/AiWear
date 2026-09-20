package com.sunny.aiwear_b.controller;

import com.sunny.aiwear_b.common.Result;
import com.sunny.aiwear_b.dto.response.FileUploadResponse;
import com.sunny.aiwear_b.log.ApiLog;
import com.sunny.aiwear_b.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

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
}
