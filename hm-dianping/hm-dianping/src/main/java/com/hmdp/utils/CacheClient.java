package com.hmdp.utils;/*
 *
 * @Param
 */

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.sql.Time;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {


    private StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 创建缓存
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time , TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }


    /**
     * 使用逻辑过期创建缓存
     */
    public void setWithLogicalExpire(String key, Object value, Long time , TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     *
     * @param keyPrefix
     * @param id
     * @param type
     * @param time
     * @param unit
     * @param dbFallback 查询数据库的逻辑
     * @param <R>  查询类型
     * @param <ID> 传入id的类型
     * @return
     */
    public <R, ID> R queryWithPassThrough(
             String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time , TimeUnit unit){
        // 1.从redis查询商户缓存
        String key = keyPrefix + id;
        String Json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2.判断是否存在
        if (!StringUtils.isBlank(Json)){
            // 3.存在，直接返回
            return BeanUtil.toBean(Json, type);
        }

        // 3.2 判断命中的是否为空
        if(Json != null){//即 shopJson.equals("");
            return null;
        }

        // 4.不存在，在MySql中去查找
        R r = dbFallback.apply(id);
        // 5.不存在，返回错误
        if(r == null){
            // 5.1 添加空缓存到redis,并设置一个较短的声明周期
            this.set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        // 6.存在，写入redis中
        //把shop对象转为Json格式
        this.set(key, r, time, unit);
        // 7.返回
        return r;
    }



    //线程池，分配十个线程
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    /**
     * queryWithLogicalExpire：该方法以装配为工具类
     * 使用逻辑过期的方法解决缓存击穿
     * 使用逻辑过期不用考虑缓存穿透的问题(因为使用之前必须要将逻辑过期时间添加到缓存)，缓存中查找不存在直接返回null
     * @param id
     * @return
     */
    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id,
                                       Class<R> type, Function<ID,R> dbFallback, Long time , TimeUnit unit){
        //1.从redis查询商户缓存
        String key = keyPrefix + id;
        String Json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isBlank(Json)){
            //3.不存在，直接返回
            return null;
        }

        // 4.存在，,将json反序列化
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.并判断缓存是否过期
        if(expireTime.isAfter(LocalDateTime.now())){//该时间是否在当前时间之后
            // 5.1 未过期：直接返回
            return r;
        }

        // 5.2 过期，进行缓存重建

        // 6 缓存重建
        // 6.1 获取互斥锁
        String LockKey = LOCK_SHOP_KEY + id;//这是锁key，不是缓存的key
        boolean isLock = tryLock(LockKey);
        //6.3 获取成功。开启独立线程
        if(isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询数据库，将商户数据写入redis，并传入新的逻辑过期时间
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 6.4 释放锁
                    UnLock(LockKey);
                }
            });
        }
        return r;

    }

    /**
     * 尝试获取互斥锁,防止缓存击穿
     * @param key
     * @return
     */
    public boolean tryLock(String key){
        //等同于setex，只有其中不存在值才能够进行操作，这里等同于获取锁
        //因为使用setex创建的值不能进行更改，等同于锁的功能
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //将flag装为基本类型返回。注意：这里不能直接进行返回(会做拆箱，可能返回空指针)，需要使用工具类
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 解除锁
     * @return
     */
    public void UnLock(String key){
        stringRedisTemplate.delete(key);
    }


}
