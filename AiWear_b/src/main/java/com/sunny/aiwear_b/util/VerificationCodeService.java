package com.sunny.aiwear_b.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 验证码服务类
 */
@Service
@Slf4j
public class VerificationCodeService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    //redis缓存前缀
    private static final String CODE_KEY_PRIFIX = "verification:code:";

    //过期时间
    private static final int TIMEOUT_MINUTES = 5;

    //生成6位随机验证码
    public String generateCode(){
        Random random = new Random();
        int code = 100000 + random.nextInt(900000);
        log.info("本次生成的验证码为：{}",code);
        return String.valueOf(code);
    }


    //保存验证码
    public void saveCode(String email,String code){
        String key = CODE_KEY_PRIFIX + email;
        //字符串结构
        redisTemplate.opsForValue().set(key, code,TIMEOUT_MINUTES, TimeUnit.MINUTES);
    }


    //验证码是否存在
    public boolean hasCode(String email){
        String key = CODE_KEY_PRIFIX + email;
        return redisTemplate.hasKey(key);
    }


    //校验验证码
    public boolean verifyCode(String email,String code){
        String key = CODE_KEY_PRIFIX + email;
        if(redisTemplate.hasKey(key)){

            String oldCode = redisTemplate.opsForValue().get(key);
            redisTemplate.delete(key);
            return code.equals(oldCode);
        }
        log.warn("验证码不存在或已过期，邮箱{}",email);
        return false;
    }

}
