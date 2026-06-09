package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    //private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /*@PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }*/
    //mq版本
    @RabbitListener(queues = "seckillQueue")
    public void handleMessage(Map<String, Object> message) {
        try {
            log.info("收到消息: {}", message);

            // 1. 解析消息（替代原来的 MapRecord.getValue()）
            Long userId = Long.valueOf(message.get("userId").toString());
            Long voucherId = Long.valueOf(message.get("voucherId").toString());
            Long orderId = Long.valueOf(message.get("id").toString());

            // 2. 创建订单对象（替代原来的 BeanUtil.fillBeanWithMap）
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);
            voucherOrder.setId(orderId);

            // 3. 写入数据库
            save(voucherOrder);

            // 4. RabbitMQ 会自动 ACK，不需要手动调用
            // 方法正常返回 = 自动 ACK 确认

        } catch (DuplicateKeyException e) {
            // 唯一键冲突，说明消息已经被处理过了
            log.warn("订单已存在，跳过处理: {}", message.get("id"));
            // 正常返回，让 MQ 确认消息

        } catch (Exception e) {
            log.error("订单处理异常:", e);
            // 抛出异常，消息会重新入队（类似 pending-list 的效果）
            throw new RuntimeException("处理失败，消息重新入队", e);
        }
    }
//    private class VoucherOrderHandler implements Runnable {
//        String queueName = "stream.orders";
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    //1.获取消息队列里的信息
//                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
//                            Consumer.from("g1", "c1"),
//                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
//                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
//                    );
//                    //2.判断消息是否获取成功
//                    if(list==null || list.isEmpty()){
//                        //2.1.失败，继续下一次循环
//                        continue;
//                    }
//
//
//                    //3.成功，写入数据库
//                    //3.1.解析
//                    MapRecord<String, Object, Object> record = list.get(0);
//                    Map<Object, Object> values = record.getValue();
//                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
//                    //写库
//                    handleVoucherOrder(voucherOrder);
//                    //4.ACK确认 SACK
//                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
//
//                }catch (Exception e){
//                    log.error("订单处理异常",e);
//                    handlePendingList();
//                }
//            }
//        }
//
//        private void handlePendingList() {
//            while (true) {
//
//                MapRecord<String, Object, Object> record = null;
//                try {
//                    //1.获取消息队列里的信息
//                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
//                            Consumer.from("g1", "c1"),
//                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
//                            StreamOffset.create(queueName, ReadOffset.from("0"))
//                    );
//                    //2.判断消息是否获取成功
//                    if(list==null || list.isEmpty()){
//                        //pending-list无异常消息
//                        break;
//                    }
//                    //3.成功，写入数据库
//                    //3.1.解析
//                    record = list.get(0);
//                    Map<Object, Object> values = record.getValue();
//                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
//                    //写库,这里有可能重复写入报错
//                    handleVoucherOrder(voucherOrder);
//                    //4.ACK确认 SACK
//                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());
//
//                }
//                catch (DuplicateKeyException e) {
//                    // 唯一键冲突 = 消息已经处理过了
//                    log.warn("订单已存在（重复消费），直接ACK: "+record.getId());
//                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
//                }
//                catch (Exception e){
//                    log.error("pending-list处理异常",e);
//                    try {
//                        Thread.sleep(20);
//                    }catch (InterruptedException e1){
//                        e1.printStackTrace();
//                    }
//                }
//
//            }
//        }
//
//        private void handleVoucherOrder(VoucherOrder voucherOrder) {
//            save(voucherOrder);
//        }
//    }

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT ;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //获取订单id
        long orderId = redisIdWorker.nextId("order");
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString());
        //2.判断结果是否为0
        int r = result.intValue();
        //2.1.不为0
        if(r == 1){
            return Result.fail("库存不足！");
        }
        if(r == 2){
            return Result.fail("不能重复下单！");
        }

        //2.2订单成功，写入mq
        Map<String, Object> message = new HashMap<>();
        message.put("userId", userId);
        message.put("voucherId", voucherId);
        message.put("id", orderId);
        rabbitTemplate.convertAndSend("seckillExchange",  "",message);
        //3.返回订单id
        return Result.ok(orderId);
    }
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始！");
        }
        //3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀尚未结束！");
        }
        //4.判断库存是否充足
        if(voucher.getStock()<1){
            return Result.fail("库存不足！");
        }
        Long userId = UserHolder.getUser().getId();
        //获取redis分布式锁工具类
        //SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate,"order:"+userId);
        //使用redisson获取锁
        RLock lock = redissonClient.getLock("lock:order" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        if(!isLock){
            //获锁失败
            return Result.fail("一人只能下一单！");
        }
        try {
            //获取代理对象（代理对象才能实现事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }finally {
            lock.unlock();
        }

    }*/
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //用户id
        Long userId = UserHolder.getUser().getId();
            //5.一人一单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0) {
                return Result.fail("用户已经购买过了！");
            }
            //6.扣减库存
            boolean success = seckillVoucherService.update().setSql("stock = stock - 1 ").eq("voucher_id", voucherId).gt("stock", 0).update();
            if (!success) {
                return Result.fail("库存不足！");
            }
            //7.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();

            //订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            //用户id
            userId = UserHolder.getUser().getId();
            voucherOrder.setUserId(userId);
            //代金券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);

            //8.返回订单id
            return Result.ok(orderId);
        }


}
