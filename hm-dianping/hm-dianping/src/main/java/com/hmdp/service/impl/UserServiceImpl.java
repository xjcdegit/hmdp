package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONException;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.BeanUtils;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.qcloudsms.SmsSingleSender;
import com.github.qcloudsms.SmsSingleSenderResult;
import com.github.qcloudsms.httpclient.HTTPException;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.jws.Oneway;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    //注入api
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            // 如果不符合
            return Result.fail("手机号格式不正确");
        }
/*

        // 短信应用 SDK AppID
        int appid = 1400746885; // SDK AppID 以1400开头
        // 短信应用 SDK AppKey
        String appkey = "6c83ed56aebf7f1f4ec2834a4d1f0489";
        // 需要发送短信的手机号码
        String phoneNumbers = "19382550148";

        // 短信模板 ID，需要在短信应用中申请
        int templateId = 1564555; // NOTE: 这里的模板 ID`7839`只是示例，真实的模板 ID 需要在短信控制台中申请
// 签名
        String smsSign = "xjcedu个人公众号"; // NOTE: 签名参数使用的是`签名内容`，而不是`签名ID`。这里的签名"腾讯云"只是示例，真实的签名需要在短信控制台申请

        try {
            //生成随机的四位验证码
            String code = RandomUtil.randomNumbers(6);
            String[] params = {code};
            //将生成的验证码保存到Session中
            session.setAttribute("code",code);


            SmsSingleSender sender = new SmsSingleSender(appid, appkey);
            SmsSingleSenderResult result = sender.sendWithParam("86", phoneNumbers,
                    templateId, params, smsSign, "", "");
            log.info("发送验证码成功 验证码：" + code);
        } catch (HTTPException e) {
            // HTTP 响应码错误
            e.printStackTrace();
        } catch (JSONException e) {
            // JSON 解析错误
            e.printStackTrace();
        } catch (IOException e) {
            // 网络 IO 错误
            e.printStackTrace();
        }
*/
        //代替短信发送业务
        String code = RandomUtil.randomNumbers(6);

        //4.1将生成的验证码保存到Session中
        //session.setAttribute("code",code);

        //4.2把生成的验证码保存到redis中，且以手机号为key,同时加上业务前缀以表示区分
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        log.info("发送验证码成功 验证码：" + code);
        return Result.ok();
    }

    /**
     * 实现用户登录
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号和验证码
        String code = loginForm.getCode();
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号码格式错误");
        }

        //2.从session中获取验证码并验证 不一致：报错
        /*Object cacheCode = session.getAttribute("code");

        if(cacheCode == null || !cacheCode.toString().equals(code)){
            return Result.fail("验证码错误");
        }*/
        // TODO 2.2. 从redis中获取验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if(cacheCode == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }
        //3.一致：根据手机号查询用户
        LambdaQueryWrapper<User> law = new LambdaQueryWrapper<>();
        law.eq(User::getPhone,phone);

        User user = getOne(law);

        //4.该手机号无对应用户，默认注册
        if(user == null){
            user.setPhone(phone);
            user.setPassword(phone.substring(0,5));
            //用户前缀加 随机10位字符串
            user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
            save(user);
        }



        // TODO 7改进，将数据保存到redis中
        // TODO 7.1 随机生成一个token，作为登录令牌(推荐使用UUID生成token)
        String token = UUID.randomUUID().toString();
        // TODO 7.2 将User对象装维hash存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        // TODO 7.3 存储
        String tokenKey = LOGIN_USER_KEY + token;


        //Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        //直接使用会报错：java Long对象不能直接转为String对象
        //stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);

        //方法一
        /*HashMap<String, Object> userMap = new HashMap<>();
        userMap.put("id",userDTO.getId().toString());
        userMap.put("Icon",userDTO.getIcon());
        userMap.put("NickName",userDTO.getNickName());*/

        //方法二：
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>() ,
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((filedName,fieldValue) -> fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        // TODO 7.4 设置redis信息有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);





        // TODO 8.0 返回token
        return Result.ok(token);


        //7.将用户信息保存到session中
        //把user中数据复制到UserDTO中，并返回一个UserDTO对象
        //session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        //每一个session都会有一个sessionID，访问tomcat时，sessionID已经自动写到cokkie中，只有请求会带着sessionID，
        // 就可以找到session与其对应的user
        //return Result.ok();
    }

    /**
     * 通过id访问用户主页
     * @param
     * @return
     */
    @Override
    public Result queryUserById(Long userId) {
        String userInRedis = stringRedisTemplate.opsForValue().get("user:" + userId);
        if(null != userInRedis) {
            UserDTO userDTO = JSONUtil.toBean(userInRedis,UserDTO.class);
            return Result.ok(userDTO);
        }
            // 查询详情
            User user = getById(userId);
            if (user == null) {
                return Result.ok();
            }
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            stringRedisTemplate.opsForValue().set("user:" + userId, JSONUtil.toJsonStr(userDTO));

        // 返回
        return Result.ok(userDTO);
    }

    @Override
    public Result sign() {
        //获取用户
        Long id = UserHolder.getUser().getId();
        //获取日期
        LocalDateTime now = LocalDateTime.now();
        //拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + id + keySuffix;
        //获取当前是本月的第几天(这里得到的为1~31，redis存入的以0开头，所以加入到redis时需要-1)
        int day = now.getDayOfMonth();
        //写入redis select key offset 1
        stringRedisTemplate.opsForValue().setBit(key, day - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1.获取用户
        Long id = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + id + keySuffix;
        //4.获取当前是本月的第几天(这里得到的为1~31，redis存入的以0开头，所以加入到redis时需要-1)
        int day = now.getDayOfMonth();

        //获取截至本日所有签到记录，返回一个十进制的数字 BITFIELD sign : 5:202203 GET ui4o
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(day)).valueAt(0)
        );
        if(result == null || result.isEmpty()){
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num == null || num == 0){
            return Result.ok(0);
        }
        //6.虚幻遍历
        int count = 0;//计数器
        while(true) {
            //6.1 让这个数字与1进行与运算，得到最后一次的bit位
            if((num & 1) == 0){
                //如果为0
                break;
            }else{
                //如果不为0
                count++;
            }
            //把数字右移一位
            num >>>= 1;
        }
        return Result.ok(count);
    }
}
