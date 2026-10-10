package com.sunny.aiwear_b.dto.request;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

//搜索图片请求
@Data
public class SearchImageRequest {
    private String query;

    private MultipartFile file;
}
