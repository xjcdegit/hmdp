package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            //获取博主的用户信息
            queryBlogUser(blog);
            //判断该用户是否对该点评点赞
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 完成点赞操作
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        //1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        Blog blog = getById(id);
        String key = BLOG_LIKED_KEY + id;
        //2.判断当前登录用户是否已经点赞  ISMEMBER key member
        //Boolean isMember = stringRedisTemplate.opsForSet().isMember(BLOG_LIKED_KEY + id, userId.toString());

        //返回null说明未点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //3. 如果未点赞（注意：不能直接判断 isMember可能为空）
        if(score == null) {
            //3.1 进行点赞，保存点赞信息到redis的set集合
            update().setSql("liked = liked + 1").eq("id", id).update();
            //stringRedisTemplate.opsForSet().add(BLOG_LIKED_KEY + id,userId.toString());

            stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            //key   value   分数(这里用时间戳实现排序)
        }else {
            //3.2 取消点赞，取消redis中的set的点赞信息
            update().setSql("liked = liked - 1").eq("id", id).update();
            //stringRedisTemplate.opsForSet().remove(BLOG_LIKED_KEY + id,userId.toString());
            stringRedisTemplate.opsForZSet().remove(key,userId.toString());
        }
        isBlogLiked(blog);
        return Result.ok();
    }

    /**
     * 获取点赞排行榜，按时间进行排序
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        //1.查询出点赞的top5用户
        Set<String> range = stringRedisTemplate.opsForZSet().range(key,0,4);
        //判断
        log.info("点赞人数 = " + range.size());
        if(range == null || range.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        ///2.解析其中的用户id
        List<Long> ids = range.stream().map(Long::valueOf).collect(Collectors.toList());

        String idStr = StrUtil.join(",",ids);
        //3.根据用户id查询用户
        List<UserDTO> userDTOS = userService.query()
                .in("id",ids).last("order by field(id," +idStr+ ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());


        //4.返回
        return Result.ok(userDTOS);
    }

    /**
     * 推送探店博文
     * @param blog
     * @return
     */
    @Override
    public Result saveBlog(Blog blog) {
        //1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        blog.setUserId(userId);
        //2.保存探店笔记
        boolean isSuccess = save(blog);
        if(!isSuccess) {
            return Result.fail("推送笔记失败");
        }
            //3.获取该用户的所有粉丝 select * from tb_follow where follow_user_id = ?
//            LambdaQueryWrapper<Follow> law = new LambdaQueryWrapper<>();
//            law.eq(Follow::getFollowUserId,userId);
//            List<Long> fansIds = followService.list(law)
//                    .stream().map(Follow::getUserId).collect(Collectors.toList());

        //获取所有粉丝的id集合
        List<Long> fansId = followService
                .query().eq("follow_user_id", userId).list()
                .stream().map(Follow::getUserId).collect(Collectors.toList());

        //探店推文的id
        Long blogId = blog.getId();
        for(Long id:fansId){
            String key = "feed:" + id;
            stringRedisTemplate.opsForZSet().add(key,blogId.toString(),System.currentTimeMillis());
        }
        //4.推送笔记id给所有粉丝
        return Result.ok(blogId);
    }

    /**
     * 实现滚动分页查询收件箱
     * @param max
     * @param offset
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        //2.查询收件箱
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //3.解析数据 key  blogId 时间戳 offset
        List<Long> BlogIds = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
         for(ZSetOperations.TypedTuple<String> tuple:typedTuples){
             //3.1 获取blogId
             BlogIds.add(Long.valueOf(tuple.getValue()));
             //3.2 时间戳
             long time = tuple.getScore().longValue();
             if(minTime == time){
                 os++;
             }else{
                 minTime = time;
                 os = 1;
             }
         }
         //4.根据id查询blog（注意：这里不能使用listByIds，它在SQL语句中使用的是in，返回结果无序）
        String idStr = StrUtil.join(",",BlogIds);
        List<Blog> blogs = query()
                .in("id", BlogIds).last("order by field(id," + idStr + ")").list();

        for (Blog blog : blogs) {
            //查询blog有关的用户
            queryBlogUser(blog);
            //查询是否被点赞
            isBlogLiked(blog);
        }
        //5.封装返回 返回ScrollResult类型
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        return Result.ok(scrollResult);
    }



    /**
     * 获取博主的用户信息
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("该笔记不存在");
        }
        //查询blog有关的用户
        queryBlogUser(blog);
        //查询是否被点赞
        isBlogLiked(blog);

        return Result.ok(blog);
    }

    /**
     * 判断是否被点赞
     * */
    private void isBlogLiked(Blog blog) {
        //1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if(user == null){
            log.info("user === null");
            return;
        }
        Long userId = user.getId();
        //判断当前登录用户是否已经点赞
        String key = BLOG_LIKED_KEY + blog.getId();

        //Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(!(score == null));
    }

    /**
     * 查询Blog功能函数
     * */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }


}
