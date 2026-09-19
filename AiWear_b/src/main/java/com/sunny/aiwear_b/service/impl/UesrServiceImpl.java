package com.sunny.aiwear_b.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sunny.aiwear_b.dto.request.AuthRequest;
import com.sunny.aiwear_b.dto.request.SendVerificationCodeRequest;
import com.sunny.aiwear_b.dto.response.AuthResponse;
import com.sunny.aiwear_b.entity.User;
import com.sunny.aiwear_b.mapper.UserMapper;
import com.sunny.aiwear_b.service.UserService;
import com.sunny.aiwear_b.util.EmailService;
import com.sunny.aiwear_b.util.JwtUtil;
import com.sunny.aiwear_b.util.VerificationCodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class UesrServiceImpl implements UserService {


    @Autowired
    private VerificationCodeService verificationCodeService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private UserMapper userMapper;


    @Autowired
    private JwtUtil jwtUtil;


    //密码处理对象
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 发送验证码
     * @param sendVerificationCodeRequest
     * @return
     */
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


    /**
     * 用户注册/登录
     * @param request
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AuthResponse auth(AuthRequest request) {
        //获取用户名
        String account=request.getAccount();
        //判断当前认证方式
        boolean isEmail = account.contains("@");
        //查询当前用户是否存在
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUsername, account);
        User user = userMapper.selectOne(queryWrapper);

        //邮箱+验证码的方式
        if(isEmail){
            String code= request.getVerificationCode();
            if(code==null||code.isEmpty()){
                throw new RuntimeException("验证码不能为空！");
            }
            //校验验证码
            if(!verificationCodeService.verifyCode(account,code)){
                throw new RuntimeException("验证码不存在或者已经过期");
            }
            //处理新老用户
            if(user==null){
                //注册新用户
                user=new User();
                user.setUsername(account);
                user.setEmail(account);
                userMapper.insert(user);
                log.info("新用户注册成功,邮箱{}",user.getEmail());
            }
        }else{
            //用户名+密码方式
            if(user==null){
                //注册新用户
                user=new User();
                user.setUsername(account);
                if(request.getPassword()==null||request.getPassword().isEmpty()){
                    throw new RuntimeException("密码不能为空");
                }
                user.setPasswordhash(passwordEncoder.encode(request.getPassword()));
                userMapper.insert(user);
            }else{
                //老用户校验密码
                if(user.getPasswordhash()==null|| !passwordEncoder.matches(request.getPassword(),user.getPasswordhash())){
                    throw new RuntimeException("用户名或密码错误");
                }
            }
        }
        return createResponse(user);
    }

    /**
     * 用户登出
     * @param authorization
     * @return
     */
    @Override
    public boolean logout(String authorization) {
        String token=jwtUtil.parseToken(authorization);
        return jwtUtil.removeToken(token);
    }


    private AuthResponse createResponse(User user){
        AuthResponse response=new AuthResponse();
        response.setUserId(user.getId());
        response.setUsername(user.getUsername());
        response.setEmail(user.getEmail());
        response.setToken(jwtUtil.generateToken(user));
        return response;
    }

}
