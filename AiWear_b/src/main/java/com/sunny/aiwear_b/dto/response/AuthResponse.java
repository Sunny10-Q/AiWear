package com.sunny.aiwear_b.dto.response;


import lombok.Data;

import java.io.Serializable;


/**
 * 统一认证响应实体类
 */
@Data
public class AuthResponse implements Serializable {

    private Long userId;

    private String username;

    private String email;

    private String token;

}
