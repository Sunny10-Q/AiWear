package com.sunny.aiwear_b.util;


import com.sunny.aiwear_b.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Jwt工具类
 */
@Component
@Slf4j
public class JwtUtil {


    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private Long expiration;

    //缓存前缀
    private static final String USER_TOKEN_KEY_PREFIX = "jwt:user:";


    /**
     * 生成令牌
     * @param user
     * @return
     */
    public String generateToken(User user){

        String userKey= USER_TOKEN_KEY_PREFIX + user.getId();

        //判断redis中是否存在旧的token
        if(redisTemplate.hasKey(userKey)){
            return redisTemplate.opsForValue().get(userKey);
        }

        Map<String,Object> map = new HashMap<>();
        map.put("userId", user.getId());
        // 保留旧字段，兼容已经签发的令牌。
        map.put("uesrId", user.getId());
        map.put("username",user.getUsername());

        Date now = new Date();
        Date expireDate = new Date(now.getTime() + expiration*1000*60*60*2);

        //jwt密钥
        SecretKey secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));

        //生成jwt令牌
        String token=Jwts.builder()
                .setClaims(map) //用户信息
                .setIssuedAt(now) //jwt颁发时间
                .setExpiration(expireDate) //过期时间
                .signWith(secretKey, SignatureAlgorithm.HS256) //密钥+算法
                .compact();

        //存入redis中
        redisTemplate.opsForValue().set(
            userKey,token,expiration, TimeUnit.HOURS
        );
        return token;
    }

    //获取token
    public String parseToken(String authorization) {
        if (authorization == null || authorization.isBlank()
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new RuntimeException("缺少请求头令牌");
        }
        return authorization.substring(7);
    }

    /**
     * 从 Authorization 请求头中解析用户 ID。
     */
    public Long getUserId(String authorization) {
        Claims claims = getClaims(parseToken(authorization));
        Object userId = claims.get("userId");
        if (userId == null) {
            userId = claims.get("uesrId");
        }
        if (userId == null) {
            throw new IllegalArgumentException("登录信息无效");
        }
        try {
            return Long.valueOf(userId.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("登录信息无效", e);
        }
    }

    //从token中解析userId
    private Claims getClaims(String token){
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    //删除令牌
    public boolean removeToken (String token){
        if(token == null || token.isBlank()){
            return true;
        }
        Claims claims = getClaims(token);
        Long uesrId=Long.valueOf(claims.get("uesrId").toString());
        String tokenKey=USER_TOKEN_KEY_PREFIX + uesrId;
        if(redisTemplate.hasKey(tokenKey)){
            redisTemplate.delete(tokenKey);
        }
        return true;
    }

}
