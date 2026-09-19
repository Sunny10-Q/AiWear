package com.sunny.aiwear_b.controller;


import com.sunny.aiwear_b.common.Result;
import com.sunny.aiwear_b.dto.request.SendVerificationCodeRequest;
import com.sunny.aiwear_b.dto.response.SendVerificationCodeResponse;
import com.sunny.aiwear_b.log.ApiLog;
import com.sunny.aiwear_b.service.UserService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/api/user")
@Slf4j
@RestController
public class UserController {


    @Autowired
    private UserService userService;

    @ApiLog
    @PostMapping("/send-code")
    public Result<SendVerificationCodeResponse> SendVerificationCode(@RequestBody @Valid SendVerificationCodeRequest sendVerificationCodeRequest) {
        boolean result = userService.SendVerificationCode(sendVerificationCodeRequest);
        if (result) {
            return Result.success("验证码发送成功",SendVerificationCodeResponse.builder().sendTo("***").expireTime(300).build());
        }
        return Result.serverError("验证码发送失败，请稍后重试");
    }

}
