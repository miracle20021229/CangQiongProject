package com.sky.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.Map;
/**
 * 生成jwt使用Hs256算法, 私匙使用固定秘钥
 * @param secretKey jwt秘钥
 * @param ttlMillis jwt过期时间(毫秒)
 * @param claims    设置的信息
 * @return
 */
public class JwtUtil {

    public static String createJWT(String secretKey, long ttlMillis, Map<String, Object> claims) {
        long expMillis = System.currentTimeMillis() + ttlMillis;
        Date exp = new Date(expMillis);

        return Jwts.builder().claims(claims).signWith(createSigningKey(secretKey)).expiration(exp).compact();
    }

    /**
     * Token解密
     *
     * @param secretKey jwt秘钥 此秘钥一定要保留好在服务端, 不能暴露出去, 否则sign就可以被伪造, 如果对接多个客户端建议改造成多个
     * @param token     加密后的token
     * @return
     */
    public static Claims parseJWT(String secretKey, String token) {
        return Jwts.parser().verifyWith(createSigningKey(secretKey)).build().parseSignedClaims(token).getPayload();
    }

    /**
     * 将配置中的字符串秘钥转换为满足HS256长度要求的JDK17标准密钥。
     */
    private static SecretKey createSigningKey(String secretKey) {
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(secretKey.getBytes(StandardCharsets.UTF_8));
            return Keys.hmacShaKeyFor(keyBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前JDK不支持SHA-256", e);
        }
    }

}
