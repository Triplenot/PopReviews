package com.hmdp.service.impl;

import com.hmdp.config.RedissonConfig;
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
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private IVoucherOrderService voucherProxy;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_SERVICE = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_SERVICE.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while(true){
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    voucherProxy.createVoucherOrder(voucherOrder);
                    return;
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT, Collections.EMPTY_LIST,voucherId.toString(),userId);
        //2.判断结果是否是0
        int r = result.intValue();
        //2.1 不为0，说明没有下单资格
        if(r!=0){
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        //2.1 为0，可以下单
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);
        voucherProxy = (IVoucherOrderService)AopContext.currentProxy();
        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){

        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 判断
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }

        try {
            // 5.1.查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 5.2.判断是否存在
            if (count > 0) {
                // 用户已经购买过了
                log.error("不允许重复下单！");
                return;
            }

            // 6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                // 扣减失败
                log.error("库存不足！");
                return;
            }

            // 7.创建订单
            save(voucherOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }

    }

    //    @Override
//    public Result seckillVoucher(Long voucherId) {
//        SeckillVoucher seckillVoucher = iSeckillVoucherService.getById(voucherId);
//        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀未开始");
//        }
//        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束");
//        }
//        if (seckillVoucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//
//        //1.synchronized实现锁，但不是分布式锁
////        //由于create方法加了事务，所以要对整个create方法加锁，避免释放锁后虽然方法执行完了但是事务仍未提交到数据库而又有另一个线程进来
////        synchronized (userId.toString().intern()) {
////            //手动使用动态代理对象，解决事务失效问题
////            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId,userId);
////        }
//        //SimpleRedisLock lock = new SimpleRedisLock("order:"+ userId,stringRedisTemplate);
//        RLock lock =redissonClient.getLock("order:"+ userId);
//        boolean isLock = lock.tryLock();
//        if(!isLock){
//            return Result.fail("一人只能一单！");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId,userId);
//        }finally {
//            lock.unlock();
//        }
//    }

    @Transactional
    public Result createVoucherOrder(Long voucherId,Long userId){

        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("用户已经购买过此秒杀券");
        }
        boolean success = seckillVoucherService.update().setSql("stock = stock - 1").eq("voucher_id", voucherId).gt("stock", 0).update();
        if (!success) {
            // 扣减失败
            return Result.fail("库存不足");
        }
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setVoucherId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(orderId);

    }
}
