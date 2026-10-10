package com.sunny.aiwear_b.dto.response;

import lombok.Data;

/**
 * Python 图片审核接口的响应结果。
 */
@Data
public class PythonImageValidationResponse {

    /** Python 接口返回的业务状态码。 */
    private Integer code;

    /** 图片是否通过服装或人物图片审核。 */
    private Boolean allow;
}
