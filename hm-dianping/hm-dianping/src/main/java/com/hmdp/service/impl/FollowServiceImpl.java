package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    /**
     * 关注和取关
     * @param followUserId
     * @param isFollow
     * @return
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //1.获取用户Id
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;

        //2.判断到底是关注还是取关
        if(isFollow){
            //关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);

            boolean isSuccess = save(follow);
            if(isSuccess) {

                // sadd userId followUserId
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else{
            //取关 delete from tb_follow where userId = ? and follow_user_id = ?
            LambdaQueryWrapper<Follow> law = new LambdaQueryWrapper<>();
            law.eq(Follow::getFollowUserId,followUserId).eq(Follow::getUserId,userId);
            boolean isSuccess = remove(law);
            if(isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 判断用户是否关注
     * @param followUserId
     * @return
     */
    @Override
    public Result isFollow(Long followUserId) {
        //1.获取用户Id
        Long userId = UserHolder.getUser().getId();

        //查询是否关注
        LambdaQueryWrapper<Follow> law = new LambdaQueryWrapper<>();
        law.eq(Follow::getFollowUserId,followUserId).eq(Follow::getUserId,userId);
        int count = count(law);

        return Result.ok(count > 0);
    }

    /**
     * 获取共同关注的博主id集合
     * @param id：博主id
     * @return
     */
    @Override
    public Result followCommons(Long id) {
        Long userID = UserHolder.getUser().getId();
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect("follows:" +userID, "follows:" + id);
        //获取id集合
        if(intersect == null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //根据id批量查询
            List<User> users = userService.listByIds(ids);
            users.stream().map(x -> BeanUtil.copyProperties(x, UserDTO.class)).collect(Collectors.toList());
            return Result.ok(users);
    }
}
