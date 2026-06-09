package com.hmdp;

import cn.hutool.core.lang.UUID;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.service.IShopService;
import com.hmdp.service.IUserService;
import lombok.var;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;


@SpringBootTest
public class HmDianPingApplicationTests {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private IUserService userService;

    @Autowired
    private IShopService shopService;

    @Test
    public void loadShopData() {
        // 1.查询店铺信息
        List<Shop> list = shopService.list();
        // 2.把店铺分组，按照typeId分组，typeId一致的放到一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3.分批完成写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 3.1.获取类型id
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            // 3.2.获取同类型的店铺的集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            // 3.3.写入redis GEOADD key 经度 纬度 member
            for (Shop shop : value) {
                // stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    @Test
    public void batchGenerateTokens() throws Exception {
        // 1. 查询所有用户（你已经有1000多个了）
        List<User> users = userService.list();
        System.out.println("找到用户数：" + users.size());

        // 2. 准备存储 token 和 userId 的列表
        StringBuilder csvContent = new StringBuilder();
        csvContent.append("token,userId\n");  // CSV 头

        for (User user : users) {
            // 3. 生成 token（和你的登录逻辑一样）
            String token = UUID.randomUUID().toString(true); // 去掉横线

            // 4. 转换为 UserDTO
            UserDTO userDTO = new UserDTO();
            userDTO.setId(user.getId());
            userDTO.setNickName(user.getNickName());
            userDTO.setIcon(user.getIcon());

            // 5. 转成 Map（和你的登录逻辑一样）
            Map<String, String> userMap = new HashMap<>();
            userMap.put("id", String.valueOf(userDTO.getId()));
            userMap.put("nickName", userDTO.getNickName());
            userMap.put("icon", userDTO.getIcon());

            // 6. 存入 Redis（和你的登录逻辑完全一致）
            String tokenKey = LOGIN_USER_KEY + token;
            stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
            stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, java.util.concurrent.TimeUnit.MINUTES);

            // 7. 记录到 CSV
            csvContent.append(token).append(",").append(user.getId()).append("\n");

            if (user.getId() % 100 == 0) {
                System.out.println("已生成 " + user.getId() + " 个 token");
            }
        }

        // 8. 保存到文件，供 JMeter 使用
        String filePath = "tokens.csv";
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(filePath))) {
            writer.write(csvContent.toString());
        }

        System.out.println("✅ 生成完成！共 " + users.size() + " 个 token");
        System.out.println("文件保存位置：" + filePath);
    }

    /**
     * 清理所有测试 token（用完记得清理）
     */
    @Test
    public void cleanAllTestTokens() {
        // 注意：这个操作会删除所有 login:token:* 的 key
        var keys = stringRedisTemplate.keys(LOGIN_USER_KEY + "*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
            System.out.println("已删除 " + keys.size() + " 个 token");
        }
    }


}
