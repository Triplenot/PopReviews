package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //生成验证码
        String code = RandomUtil.randomNumbers(6);

        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,3, TimeUnit.MINUTES);

        log.debug("发送验证码成功，验证码：{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //校验验证码
        Object cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);

        String code = loginForm.getCode();
        if(cacheCode == null || !cacheCode.toString().equals(code)){
            return Result.fail("验证码错误");
        }
        //一致，根据手机号查询用户
        User user = query().eq("phone",phone).one();
        if(user == null){
            user = createUserwithPhone(phone);
        }
        //保存用户信息
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 7.4.设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDate now = LocalDate.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern("YYYY-MM"));
        String key = "sign:"+userId+":"+keySuffix;
        stringRedisTemplate.opsForValue().setBit(key,now.getDayOfMonth()-1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDate now = LocalDate.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern("YYYY-MM"));
        String key = "sign:"+userId+":"+keySuffix;
        List<Long> records = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(now.getDayOfMonth()))
                        .valueAt(0)
        );
        if(records==null||records.isEmpty()){
            return Result.fail("没有任何签到结果");
        }
        Long record = records.get(0);
        if(record==null||record==0){
            return Result.ok(0);
        }
        int count = 0;
        while (record>0){
            if((record&1)==0){
                break;
            }else{
                count++;
            }
            record>>=1;
        }
        return Result.ok(count);
    }

    private User createUserwithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
