package com.hmdp.utils;/*
 *
 * @Param
 */

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

/**
 * 拦截器，同时需要在config配置拦截器才能生效
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

        //这个类不是由Spring创建，所以不能用注解
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // TODO 注意：这里不能够使用注解注入，因为这个对象不是由spring进行创建的

    //前置拦截器
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取session对象
        //HttpSession session = request.getSession();

        //2.获取session中的用户
        //Object user = session.getAttribute("user");
        //3.判断用户是否存在
//        if(user == null) {
//            //4.不存在拦截  返回状态码  或者也可以直接返回异常
//            response.setStatus(401);
//            return false;
//        }
//        //5.存在保存用户信息到ThreadLocal
//        UserHolder.saveUser((UserDTO) user);
//        //6.放行
//        return HandlerInterceptor.super.preHandle(request, response, handler);


        // TODO 1 获取请求头中的token
        String token = request.getHeader("authorization");
        if(StrUtil.isBlank(token)){//判断是否为空
            return true;//直接放行，后续拦截器会进行拦截，这样就可以实现不登陆访问主页了
        }
        // TODO 2 基于token获取redis中的用户
        String key = LOGIN_USER_KEY + token;
        System.out.println(key);
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        // TODO 3 判断用户是否存在
        if(userMap.isEmpty()){
            // TODO 4 不存在进行拦截
            return true;
        }

        // TODO 5 将查询到的Hash数据装为UserDDTO对象信息
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);//最后的false代表是否忽视异常
        // TODO 6 存在，保存用户信息到TheadLocal
            UserHolder.saveUser(userDTO);
        // TODO 7 刷新token有效期
        stringRedisTemplate.expire(key,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        // TODO 8 放行
        return true;

    }


    //
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
