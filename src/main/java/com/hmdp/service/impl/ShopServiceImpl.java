package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
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
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheClient cacheClient;
    @Override
    public Result queryById(Long id) {
        //TODO 加入布隆过滤器应对缓存穿透
        //TODO 加入随机值等方法解决缓存雪崩
        //调用CacheClient里的方法，其已经实现带缓存穿透和击穿防护的查询
        Shop shop = cacheClient.queryWithPassThrough(
                CACHE_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                CACHE_SHOP_TTL,
                TimeUnit.MINUTES
        );
        if(shop == null){
            //不存在
            return Result.fail("店铺信息不存在！");
        }
        //6.返回前端
        return Result.ok(shop);
    }
    //加锁，用于查询shop时对读操作防止缓存击穿
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //解锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }


    @Override
    @Transactional
    public Result update(Shop shop) {
        if(shop.getId()==null){
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY+shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.判断是否需要根据坐标查询
        if(x==null||y==null){
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return  Result.ok(page.getRecords());
        }
        //2.计算分页参数
        int from = (current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current*SystemConstants.DEFAULT_PAGE_SIZE;

        //3.查询redis
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().radius(SHOP_GEO_KEY + typeId, new Circle(new Point(x, y), new Distance(5000)), RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().limit(end));
        //4.解析
        if (results == null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size()<=from){
            //没有下一页了
            return Result.ok(Collections.emptyList());
        }
        //4.1截取从from到end部分
        List<Long> ids= new ArrayList<>(list.size());
        Map<String,Distance> distanceMap= new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            //获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        //5.根据id批量查询
        String join = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("order by field(id," + join + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }
}
