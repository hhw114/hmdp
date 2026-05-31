package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

@Slf4j
@Component
public class CacheClient {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    // 线程池（类成员变量）
    private static final ExecutorService CACHE_EXECUTOR = Executors.newFixedThreadPool(10);
    //存入任意类型序列化为String进入redis，包括ttl
    public void set(String key,Object value,Long time, TimeUnit timeUnit){
        //序列化value为json
        String jsonValue = JSONUtil.toJsonStr(value);
        stringRedisTemplate.opsForValue().set(key, jsonValue, time, timeUnit);
    }


    //存入任意类型序列化为String进入redis，但是逻辑过期版本
    public void setWithLogicalExpire(String key,Object value,Long time, TimeUnit timeUnit){
        //封装入RedisData
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        String jsonValue = JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(key, jsonValue);
    }

    //查询redis，且在缓存未命中的情况下重建缓存，如果为查询到数据，将空值写入避免缓存穿透
    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id , Class<R> type , Function<ID,R> dbFallback,Long time, TimeUnit timeUnit
    ){
        String cacheKey = keyPrefix + id;
        String lockKey = "lock:" + cacheKey;

        //1. 查询redis
        String redisString = stringRedisTemplate.opsForValue().get(cacheKey);

        //2. 判断是否存在，存在直接返回
        if(StrUtil.isNotBlank(redisString)){//isNotBlank方法里，空字符串，只有换行符也算空
            //存在，返回
            R bean = JSONUtil.toBean(redisString, type);
            return bean;
        }

        //3. 判断是否为空值（空值也算不存在）
        if(redisString != null){
            return null;
        }

        //4. 缓存未命中，尝试获取锁（防止缓存击穿）
        R result = null;
        boolean hasLock = false;

        try {
            //4.1 获取锁
            hasLock = tryLock(lockKey);

            if (!hasLock) {
                //获取锁失败，休眠一段时间后重试（自旋）
                Thread.sleep(50);
                return queryWithPassThrough(keyPrefix, id, type, dbFallback, time, timeUnit);
            }

            //4.2 双重检查：可能其他线程已经重建了缓存
            redisString = stringRedisTemplate.opsForValue().get(cacheKey);
            if(StrUtil.isNotBlank(redisString)){
                //其他线程已重建，直接返回
                return JSONUtil.toBean(redisString, type);
            }
            if(redisString != null){
                //其他线程已写入空值
                return null;
            }

            //4.3 真的不存在，查询数据库
            result = dbFallback.apply(id);

            //4.4 写入缓存
            if(result == null){
                //写入空值，避免缓存穿透
                stringRedisTemplate.opsForValue().set(cacheKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }

            //存在，写入缓存
            stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(result), time, timeUnit);
            return result;

        } catch (InterruptedException e) {
            //恢复中断状态
            Thread.currentThread().interrupt();
            log.error("缓存查询被中断", e);
            //发生异常时，直接查询数据库（降级处理）
            result = dbFallback.apply(id);
            if(result != null){
                stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(result), time, timeUnit);
            }
            return result;
        } finally {
            //4.5 释放锁（只有当前线程持有锁时才释放）
            if(hasLock){
                unlock(lockKey);
            }
        }
    }

    /**
     * 查询缓存（逻辑过期版本）
     * 适用于热点数据，缓存永不过期，通过逻辑过期字段控制更新
     * 优点：永远不会出现缓存击穿，查询永远不阻塞
     * 缺点：可能返回旧数据（短暂不一致）
     */
    //TODO 这里有严重问题，如热点商品没预热，降级为物理ttl查询后会在redis里构建普通缓存，下次热点缓存试图把json转为redisdata格式会异常
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Long time, TimeUnit timeUnit
    ) {
        String cacheKey = keyPrefix + id;
        String lockKey = "lock:" + cacheKey;

        // 1. 查询Redis，获取逻辑过期数据
        String redisString = stringRedisTemplate.opsForValue().get(cacheKey);

        // 2. 未命中（理论上逻辑过期方案缓存应该一直存在，如果不存在说明不是热点数据）
        if (StrUtil.isBlank(redisString)) {
            // 降级到普通查询（或直接查库）
            return queryWithPassThrough(keyPrefix, id, type, dbFallback, time, timeUnit);
        }

        // 3. 命中，解析数据
        RedisData redisData = JSONUtil.toBean(redisString, RedisData.class);
        R result = JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 4. 判断逻辑时间是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 未过期，直接返回
            return result;
        }

        // 5. 已过期，需要重建缓存
        // 尝试获取锁（只让一个线程去重建）
        boolean hasLock = tryLock(lockKey);

        if (hasLock) {
            // 获取锁成功，开启独立线程重建缓存（不阻塞当前请求）
            CACHE_EXECUTOR.submit(() -> {
                try {
                    // 双重检查：可能其他线程已经重建
                    String newRedisString = stringRedisTemplate.opsForValue().get(cacheKey);
                    if (StrUtil.isNotBlank(newRedisString)) {
                        RedisData newRedisData = JSONUtil.toBean(newRedisString, RedisData.class);
                        if (newRedisData.getExpireTime().isAfter(LocalDateTime.now())) {
                            return; // 已被其他线程重建
                        }
                    }

                    // 查询数据库
                    R newResult = dbFallback.apply(id);
                    if (newResult != null) {
                        // 写入缓存（逻辑过期）
                        setWithLogicalExpire(cacheKey, newResult, time, timeUnit);
                    }
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }

        // 6. 返回旧数据（无论是否获取到锁，都返回旧数据）
        return result;
    }



    //加锁，用于查询时对读操作防止缓存击穿
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //解锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

}
