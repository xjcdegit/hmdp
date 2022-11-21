package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private RedisIdWorker redisIdWorker;//Id生成器：生成订单id
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;
    //秒杀脚本
    private final static DefaultRedisScript<Long> SECKILL_SCRIPT;
    private IVoucherOrderService proxy;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    //创建阻塞队列,队列中没有元素时会阻塞
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);//队列长度不宜过长
    //单线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //实现类初始化的时候就执行线程池
    @PostConstruct
    private void init(){
        log.info("开始执行线程池");
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }


    //创建线程任务
    private class VoucherOrderHandler implements Runnable{
        //将订单写入数据库
        @Override
        public void run() {
            while (true) {
                //获取队列中的头元素
                VoucherOrder voucherOrder = null;
                try {
                    log.info("开始处理订单");
                    voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常");
                }


            }
        }

        //处理订单
        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            //1. 获取对象
            Long userId = voucherOrder.getUserId();
            //获取锁
            //RLock lock = redissonClient.getLock("lock:order:" + userId);

            //3.获取锁无参：不等待，超时时间设置为30秒
//            boolean isLock = lock.tryLock();
//
//            if (!isLock) {
//                //获取锁失败
//                log.error("不允许重复下单");
//                return;
//            }

            //因为这里使用的是子线程，所以不能通过方法获取代理对象，需要在主线程获取
            proxy.createVoucherOrder(voucherOrder);
            //lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        log.info("开始下单");
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }


        //2.2 有购买资格，吧下单信息加入到阻塞队列中
        log.info("有购买资格，吧下单信息加入到阻塞队列中");
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        voucherOrder.setId(orderId);
        //用户id
        voucherOrder.setUserId(userId);
        //优惠券id
        voucherOrder.setVoucherId(voucherId);

        log.info("装入队列");
        orderTasks.add(voucherOrder);

        //线程代理对象初始化
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 3.返回订单id
        return Result.ok(orderId);
    }


    /**
     * 实现秒杀优惠券
     * @return
     * @param
     */
    /*@Override
    @Transactional
    public Result seckillVoucher(Long voucherId){
        //1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始");
        }
        //3.判断秒杀是否结束
        if(LocalDateTime.now().isAfter(voucher.getEndTime())){
            return Result.fail("秒杀已结束");
        }


        //7.返回订单id
        Long userId = UserHolder.getUser().getId();
        //注意：toString()底層是新建了一個String類型的對象，所以我們需要使用intern方法在常量池中找到值一樣的字符串地址

        //创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId,stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        //无参：不等待，超时时间设置为30秒
        boolean isLock = lock.tryLock();
        System.out.println("获取锁");
        if(!isLock){
            //获取锁失败
            return Result.fail("不允许重复下单");
        }

        try {
            //事务管理：spring使用代理对象进行的事务处理
            //获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }

    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){//synchronized:添加鎖

        //TODO 5.进行一人一单的判断
        Long voucherId = voucherOrder.getVoucherId();
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        Long userId = voucherOrder.getUserId();

            //5.1 查询id订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId)
                    .count();
            //5.2 判断是否存在，存在就报错，只8能下一单
        System.out.println("count = " + count);
            if (count > 0) {
                log.error("该用户已领取过该卷");
                return;
            }


            //5.3 判断库存是否充足
            int stock = voucher.getStock();
        System.out.println("stock +" + stock);
            if (stock < 1) {
                log.error("优惠券已被抢光");
                return;
            }

            //扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId).gt("stock", 0) //where id = ? and stock>0
                    .update();

            if (!success) {
                //扣除失败
                log.error("库存不足");
                return;
            }
            log.info("完成添加订单操作");
            save(voucherOrder);
        }

}
