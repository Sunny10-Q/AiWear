package com.sunny.aiwear_b.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;


/**
 * 图片编辑请求实体类
 */
@Data
public class EditImageRequest {

    @NotBlank(message = "图片不能为空")
    private String image;

    @NotBlank(message = "编辑指令不能为空")
    private String instruction;
}
