package com.sunny.aiwear_b.service.impl;

import com.sunny.aiwear_b.dto.request.SendVerificationCodeRequest;
import com.sunny.aiwear_b.service.UserService;
import com.sunny.aiwear_b.util.EmailService;
import com.sunny.aiwear_b.util.VerificationCodeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UesrServiceImpl implements UserService {


    @Autowired
    private VerificationCodeService verificationCodeService;

    @Autowired
    private EmailService emailService;

    @Override
    public boolean SendVerificationCode(SendVerificationCodeRequest sendVerificationCodeRequest) {

        String email = sendVerificationCodeRequest.getEmail();
        //判断是否发送过验证码
       if(verificationCodeService.hasCode(email)){
           throw new RuntimeException("验证码尚为过期，请勿重复发送");
       }
       String code = verificationCodeService.generateCode();
       verificationCodeService.saveCode(email, code);
       //发送验证码给用户
        return emailService.sendVerificationCode(email, code);
    }

}
