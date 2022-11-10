package com.hmdp.utils;/*
 *
 * @Param
 */

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 基于redis的Id生成器
 */
@Component
public class RedisIdWorker {
    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //2022-1-1 00:00:00 时的时间戳
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    //序列号位数
    private static final int COUNT_BITS = 32;



    //生成策略：基于redis自增长。redis需要一个key
    public long nextId(String keyPrefix){
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;
        //2.生成序列号
        //使用redis，对key进行自增
        //2.1：获取日期，精确到天
        String data = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2.2:自增长
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + data);
        //最后加上时间戳，防止key超过上限(32个比特位)

        //3.拼接并返回
        //位运算     或运算
        return timeStamp << COUNT_BITS | count;
    }


}
