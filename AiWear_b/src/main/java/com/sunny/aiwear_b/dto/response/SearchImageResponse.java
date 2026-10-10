package com.sunny.aiwear_b.dto.response;


import lombok.Data;

//搜索图片响应实体类
@Data
public class SearchImageResponse {

    private String filePath;

    private String fileName;

    /** 图片与搜索条件之间的相似度。 */
    private Double similarity;
}
