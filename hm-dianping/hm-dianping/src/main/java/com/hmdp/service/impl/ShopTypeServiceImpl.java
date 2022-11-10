package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_LIST_KEY;

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

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result SelectList() {
        /*
        // 1.在Redis中查找list
        List<String> range = stringRedisTemplate.opsForList().range(CACHE_LIST_KEY, 0, -1);
        List<ShopType> newRange = null;
        // 2.存在，直接返回
        // TODO 存在问题：直接使用会报错：java Long对象不能直接转为String对象
        if(!range.isEmpty()){
            range.stream().map(x -> JSONUtil.toBean(x,ShopType.class)).collect(Collectors.toList());
            return Result.ok(range);
        }

        // 3.不存在，在MySQL中进行查找
        List<ShopType> list = query().orderByAsc("sort").list();
        if(list.isEmpty()){
            return Result.fail("不存在该分类");
        }

        // 4.如果存在，添加到redis中
        stringRedisTemplate.opsForList().leftPushAll(CACHE_LIST_KEY,
                list.stream().map(x -> JSONUtil.toJsonStr(x)).collect(Collectors.toList()));

        return Result.ok(list);
        */


        String key =  CACHE_LIST_KEY;
        // 1.从redis中查询数据
        String typeJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (typeJson != null){
            // 3.存在，返回数据
            List<ShopType> shopTypeList = JSONUtil.toList(typeJson, ShopType.class);
            return Result.ok(shopTypeList);
        }
        // 4.不存在，查询数据库
        List<ShopType> list = query().orderByAsc("sort").list();
        // 5.不存在，返回错误
        if (list == null) {
            return Result.fail("分类不存在");
        }
        // 6.存在存入redis中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(list));
        // 7.返回
        return Result.ok(list);
    }
}
