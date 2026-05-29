package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE_LIST;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public List<ShopType> getTypeList() {
        //1.从redis里查询商户列表
        String shopTypeList = stringRedisTemplate.opsForValue().get(CACHE_SHOPTYPE_LIST);

        //2.查到了，返回前端
        if (shopTypeList != null) {
            List<ShopType> list = JSONUtil.toList(shopTypeList, ShopType.class);
            return list;
        }
        //3.没查到，去数据库查
        List<ShopType> list = list();
        //4.数据库查询，查到了就放入redis
        if (!list.isEmpty()) {
            stringRedisTemplate.opsForValue().set(CACHE_SHOPTYPE_LIST, JSONUtil.toJsonStr(list));
        }
        //5.返回前端
        return list;

    }
}
