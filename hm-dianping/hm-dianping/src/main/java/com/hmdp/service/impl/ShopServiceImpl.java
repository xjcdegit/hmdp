package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;//注入工具类
    /**
     * 实现查询缓存的过程
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        //缓存穿透＋ 缓存击穿
        //Shop shop = queryWithMutex(id);

        //使用逻辑过期的方式解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);

        //使用工具类
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL,TimeUnit.MINUTES);
        Shop shop = cacheClient
                .queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,5L,TimeUnit.SECONDS);

        //Class<R> type, Function<ID,R> dbFallback, Long time , TimeUnit unit)
        if(shop == null){
            return Result.fail("店铺不存在");
        }
        // TODO 7.返回
        return Result.ok(shop);
    }

    /**
     * 针对缓存击穿：一个被高并发访问并且缓存重建业务较复杂的key突然失效了，无数的请求访问会在瞬间给数据库带来巨大的冲击
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        // TODO 1.从redis查询商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // TODO 2.判断是否存在
        if (!StringUtils.isBlank(shopJson)){
            // TODO 3.存在，直接返回
            Shop shop = BeanUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        // TODO 3.2 判断命中的是否为空
        if(shopJson != null){//即 shopJson.equals("");
            return null;
        }

        // TODO 4 实现缓存重建
        // TODO 4.1 获取互斥锁
        String LockKey = LOCK_SHOP_KEY + id;//这是锁key，不是缓存的key
        Shop one = null;
        try {
            boolean isLock = tryLock(LockKey);
            //TODO 4.2 判断是否获取成功
            if(!isLock) {
                //TODO 4.3 失败，休眠并重试
                Thread.sleep(100);
                queryWithMutex(id);
            }
            //TODO 4.4 成功，根据id查询数据库

            // TODO 5.不存在，在MySql中去查找
            LambdaQueryWrapper<Shop> law = new LambdaQueryWrapper<>();
            law.eq(Shop::getId, id);
            one = getOne(law);

            //模拟重建缓存的延时
            Thread.sleep(200);

            // TODO 6.不存在，返回错误
            if(one == null){
                // TODO 6.1 添加空缓存到redis,并设置一个较短的声明周期
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            // TODO 7.存在，写入redis中
            //把shop对象转为Json格式
            String Json = JSONUtil.toJsonStr(one);
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,Json,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 8 释放锁
            UnLock(LockKey);
        }

        // TODO 9.返回
        return one;
    }


    //线程池，分配十个线程
    //private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    /**
     * queryWithLogicalExpire：该方法以装配为工具类
     * 使用逻辑过期的方法解决缓存击穿
     * 使用逻辑过期不用考虑缓存穿透的问题，缓存中查找不存在直接返回null
     * @param id
     * @return
     */
    /*
    public Shop queryWithLogicalExpire(Long id){
        //1.从redis查询商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        if (StrUtil.isBlank(shopJson)){
            //3.不存在，直接返回
            return null;
        }

        // 4.存在，,将json反序列化
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.并判断缓存是否过期
        if(expireTime.isAfter(LocalDateTime.now())){//该时间是否在当前时间之后
            // 5.1 未过期：直接返回
            return shop;
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
                    //查询数据库，将商户数据写入redis
                    this.saveShopToRedis(id, 3L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 6.4 释放锁
                    UnLock(LockKey);
                }
            });
        }
        return shop;

    }*/

    /**
     * 针对缓存穿透：用户请求的数据在缓存中和数据库中都不存在，不断发起这样的请求，给数据库带来巨大压力
     * @param id
     * @return
     */
    /*
    public Shop queryWithPassThrough(Long id){
        // TODO 1.从redis查询商户缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // TODO 2.判断是否存在
        if (!StringUtils.isBlank(shopJson)){
            // TODO 3.存在，直接返回
            Shop shop = BeanUtil.toBean(shopJson, Shop.class);
            return shop;
        }

        // TODO 3.2 判断命中的是否为空
        if(shopJson != null){//即 shopJson.equals("");
            return null;
        }

        // TODO 4.不存在，在MySql中去查找
        LambdaQueryWrapper<Shop> law = new LambdaQueryWrapper<>();
        law.eq(Shop::getId, id);
        Shop one = getOne(law);
        // TODO 5.不存在，返回错误
        if(one == null){
            // TODO 5.1 添加空缓存到redis,并设置一个较短的声明周期
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        // TODO 6.存在，写入redis中
        //把shop对象转为Json格式
        String Json = JSONUtil.toJsonStr(one);
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,Json,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // TODO 7.返回
        return one;
    }
*/
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

    /**
     * 逻辑过期方式解决击穿问题：将过期时间存入redis
     */
     /*public void saveShopToRedis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
         //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }*/


    @Override
    @Transactional // 实现事务控制，保证操作同时正确
    public Result update(Shop shop) {
        Long id = shop.getId();
        //对key进行判断
        if(id == null){
            return Result.fail("店铺Id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();

    }
}
