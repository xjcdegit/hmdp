package com.hmdp.utils;/*
 *
 * @Param
 */

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    //释放锁的lua脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLocK(long timeoutSec) {
        //获取线程标示，更改线程标示
        //防止线程阻塞在锁过期，该进程误删了下一个进程的锁
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name,
                threadId, timeoutSec, TimeUnit.SECONDS);
        //直接返回可能拆箱异常，返回空
        //return success;
        return Boolean.TRUE.equals(success);
    }

    /**
     * 基于Lua脚本
     * UNLOCK_SCRIPT ：lua脚本
     * Collections.singletonList(KEY_PREFIX + name)：含锁的key的单元素集合
     * ID_PREFIX + Thread.currentThread().getId()：线程标示
     */
    @Override
    public void unlock() {
        //调用lua脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }
    /*@Override
    public void unlock() {
        //获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁中的标示
        String threadIdInLock = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);

        //TODO 仍然存在漏洞，在我们判断确认释放锁之后，释放锁之前。进程陷入了阻塞(JVM垃圾回收)
        // 阻塞时间超过了锁的TTL，超时释放了锁，那么就会就会造成线程并行
        // redis提供了Lua脚本功能，我们使用Lua脚本语言实现命令执行的原子性

        //Lua常见调用命令： EVAL "return redis.call('set','name','jack')" 0
        //                      脚本内容                                脚本需要的key类型的三叔个数
        if(threadId.equals(threadIdInLock)) {
            //释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
     }*/
}
