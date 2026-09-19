package com.sunny.aiwear_b.controller;


import com.sunny.aiwear_b.common.Result;
import com.sunny.aiwear_b.dto.request.AuthRequest;
import com.sunny.aiwear_b.dto.request.SendVerificationCodeRequest;
import com.sunny.aiwear_b.dto.response.AuthResponse;
import com.sunny.aiwear_b.dto.response.SendVerificationCodeResponse;
import com.sunny.aiwear_b.log.ApiLog;
import com.sunny.aiwear_b.service.UserService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.validator.internal.constraintvalidators.bv.time.futureorpresent.FutureOrPresentValidatorForYear;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RequestMapping("/api/user")
@Slf4j
@RestController
public class UserController {


    @Autowired
    private UserService userService;
    @Autowired
    private FutureOrPresentValidatorForYear futureOrPresentValidatorForYear;


    /**
     * 发送验证码
     * @param sendVerificationCodeRequest
     * @return
     */
    @ApiLog
    @PostMapping("/send-code")
    public Result<SendVerificationCodeResponse> SendVerificationCode(@RequestBody @Valid SendVerificationCodeRequest sendVerificationCodeRequest) {
        boolean result = userService.SendVerificationCode(sendVerificationCodeRequest);
        if (result) {
            return Result.success("验证码发送成功",SendVerificationCodeResponse.builder().sendTo("***").expireTime(300).build());
        }
        return Result.serverError("验证码发送失败，请稍后重试");
    }


    /**
     * 用户登录/注册
     * @param request
     * @return
     */
    @ApiLog
    @PostMapping("/auth")
    public Result<AuthResponse> auth(@RequestBody @Valid AuthRequest request){
        return Result.success("操作成功",userService.auth(request));
    }


    /**
     * 用户登出
     * @param authorization
     * @return
     */
    @ApiLog
    @PostMapping("/logout")
    public Result<String> logout(@RequestHeader(value = "Authorization") String authorization){
        boolean success=userService.logout(authorization);
        if (success) {
            return Result.success("退出成功","退出成功");
        }else{
            return Result.serverError("退出失败，请稍后重试");
        }
    }


}
