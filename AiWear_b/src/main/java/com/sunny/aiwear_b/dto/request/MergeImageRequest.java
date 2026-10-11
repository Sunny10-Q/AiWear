package com.sunny.aiwear_b.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

// 合并图⽚的请求实体类
@Data
public class MergeImageRequest {
    @NotBlank(message = "图⽚1不能为空")
    private String image1;
    @NotBlank(message = "图⽚2不能为空")
    private String image2;
    @NotBlank(message = "合并指令不能为空")
    private String instruction;
}