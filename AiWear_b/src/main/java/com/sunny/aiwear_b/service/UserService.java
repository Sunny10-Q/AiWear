package com.sunny.aiwear_b.service;

import com.sunny.aiwear_b.dto.request.SendVerificationCodeRequest;

/**
 * 用户模块服务接口
 */
public interface UserService {

    //发送邮箱验证码
    boolean SendVerificationCode(SendVerificationCodeRequest sendVerificationCodeRequest);

}
