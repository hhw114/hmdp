package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedisTemplate<Object, Object> redisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号，如果不符合规则返回错误信息
        if(RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号码错误");
        }
        //如合规，生成验证码
        String code = RandomUtil.randomNumbers(6);
        //验证码存入redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY +phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.debug("发送验证码成功，验证码:{}",code);
        //发送验证码
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号码错误");
        }
        //从redis获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY +phone);
        String code = loginForm.getCode();
        if(cacheCode == null || !cacheCode.equals(code)){
            //验证码不一致，报错
            return Result.fail("验证码错误");
        }
        //根据手机号查询用户)
        User user = query().eq("phone", phone).one();

        if(user == null){
            //不存在新建
            user=createUserWithPhone(phone);
        }


        //保存用户信息入redis
        //生成token作为key
        String token = UUID.randomUUID().toString(true);

        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);

        // 将 id 字段从 Long 转为 String
        if (userMap.containsKey("id") && userMap.get("id") instanceof Long) {
            userMap.put("id", String.valueOf(userMap.get("id")));
        }
        //作为hash存储
        String tokenKey=LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //设置token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //TODO 新增密码登录功能
        //返回token
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //1.查询当前用户id
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key =  USER_SIGN_KEY+ userId + ":"+keySuffix;
        //4.几天是这个月第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.写入redis
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth-1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1.查询当前用户id
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String key =  USER_SIGN_KEY+ userId + ":"+keySuffix;
        //4.几天是这个月第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.获取本月截至今天所有签到记录
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }

        Long num = result.get(0);
        if (num == null || num == 0){
            return  Result.ok(0);
        }

        //6.循环遍历
        int count = 0;
        while (true){
            //7.与1做与运算，获得最后一个bit位
            if ((num & 1) == 0){
                //判断是否为0，为0结束
                break;
            }else{
                //为1计数器+1
                count ++;
            }

            //把数字右移一位
            num >>>=1;
        }
        return  Result.ok(count);
    }

    /*
    * 创建新用户，使用手机号
    * */
    private User createUserWithPhone(String phone) {
        //新建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));

        //保存用户
        save(user);
        return user;
    }
}
