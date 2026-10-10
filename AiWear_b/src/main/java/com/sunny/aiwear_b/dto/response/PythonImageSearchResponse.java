package com.sunny.aiwear_b.dto.response;

import lombok.Data;

import java.util.List;

/**
 * Python /api/search-image 接口的响应结构。
 */
@Data
public class PythonImageSearchResponse {

    /** Python 接口返回的业务状态码。 */
    private Integer code;

    /** Python 接口返回的说明信息。 */
    private String message;

    /** 匹配到的图片列表。 */
    private List<SearchImageResponse> data;
}
