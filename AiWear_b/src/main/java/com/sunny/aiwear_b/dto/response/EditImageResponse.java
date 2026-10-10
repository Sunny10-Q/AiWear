package com.sunny.aiwear_b.dto.response;


import jakarta.validation.constraints.NotBlank;
import lombok.Data;


/**
 * 图片编辑响应实体类
 */
@Data
public class EditImageResponse {

    private String url;

    private String saveUrl;
}
