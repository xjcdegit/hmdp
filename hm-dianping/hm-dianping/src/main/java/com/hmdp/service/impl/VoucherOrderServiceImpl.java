package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopConfigException;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private RedisIdWorker redisIdWorker;//Id生成器：生成订单id
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 实现秒杀优惠券
     * @return
     */
    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
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
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId,stringRedisTemplate);
        boolean isLock = lock.tryLocK(1200);
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

    }

    @Transactional
    public Result createVoucherOrder(Long voucherId){//synchronized:添加鎖
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //TODO 4.进行一人一单的判断
        //4.1 获取用户Id
        Long userId = UserHolder.getUser().getId();

        //即一個用戶使用一把鎖

            //4.2 查询id
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            //4.3 判断是否存在，存在就报错，只能下一单
            System.out.println("count =" + count);
            if (count > 0) {
                return Result.fail("该用户已领取过该卷");
            }


            //5.判断库存是否充足
            Integer stock = voucher.getStock();
            if (stock < 1) {
                return Result.fail("优惠券已被抢光");
            }
            //6.扣减库存
            //boolean success = seckillVoucherService.update()
            //.setSql("stock = stock - 1")
            //.eq("voucher_id", voucherId).update();

            // 添加乐观锁
            // 即在对库存进行修改时，需要再进行一次判断，如果两次库存相同，才进行操作
        /*stock -= 1;
        voucher.setStock(stock);
        boolean success = seckillVoucherService.updateById(voucher);*/
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId).gt("stock", 0) //where id = ? and stock>0
                    .update();


            if (!success) {
                //扣除失败
                return Result.fail("库存不足");
            }


            //6.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //6.1 订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);

            //6.2 用户id

            voucherOrder.setUserId(userId);

            //6.3 代金券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            return Result.ok(orderId);
        }

}
