package com.sunny.aiwear_b.service;

import com.sunny.aiwear_b.dto.request.AuthRequest;
import com.sunny.aiwear_b.dto.request.SendVerificationCodeRequest;
import com.sunny.aiwear_b.dto.response.AuthResponse;
import jakarta.validation.Valid;

/**
 * 用户模块服务接口
 */
public interface UserService {

    //发送邮箱验证码
    boolean SendVerificationCode(SendVerificationCodeRequest sendVerificationCodeRequest);

    //用户登录
    AuthResponse auth(@Valid AuthRequest request);

    //用户退出
    boolean logout(String authorization);
}
