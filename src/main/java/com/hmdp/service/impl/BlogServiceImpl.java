package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;
    @Resource
    private IFollowService followService;
    @Resource
    private IBlogService blogService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在");
        }
        queryBlogUser(blog);
        isBlogLikedByCurrentUser(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            queryBlogUser(blog);
            isBlogLikedByCurrentUser(blog);
        });
        return Result.ok(records);
    }

    private boolean isBlogLikedByCurrentUser(Blog blog){
        UserDTO user = UserHolder.getUser();
        if(user==null){
            //未登录用户，默认为未点赞
            return false;
        }
        Long userId = user.getId();
        String key = BLOG_LIKED_KEY+blog.getId();
        Double isMember = stringRedisTemplate.opsForZSet().score(key,userId.toString());
        boolean result = !Objects.isNull(isMember);
        blog.setIsLike(result);
//        Long userId = UserHolder.getUser().getId();
//        String key = "blog:liked:"+blog.getId()+"by:"+userId;
//        Boolean isMember = !Objects.isNull(stringRedisTemplate.opsForValue().get(key));
//        boolean result = BooleanUtil.isTrue(isMember);
//        blog.setIsLike(result);
        return result;
    }
    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY+id;
        Double isMember = stringRedisTemplate.opsForZSet().score(key,userId.toString());
        if(!Objects.isNull(isMember)){
            //用户已经点赞过
            if(update(new UpdateWrapper<Blog>().setSql("liked = liked - 1").eq("id", id))){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }

        //用户未点赞过
        else {
            if (update(new UpdateWrapper<Blog>().setSql("liked = liked + 1").eq("id", id))) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());
            }
        }
        //String key = "blog:liked:"+id+"by:"+userId;
//        Boolean isMember = stringRedisTemplate.opsForValue().setIfAbsent(key,"1");
//        System.out.println("sdd"+isMember);
//        if(BooleanUtil.isFalse(isMember)){
//            //用户已经点赞过
//            if(update(new UpdateWrapper<Blog>().setSql("liked = liked - 1").eq("id", id))){
//                stringRedisTemplate.delete(key);
//                //stringRedisTemplate.opsForSet().remove(key,userId.toString());
//            }
//        }
//
//        //用户未点赞过
//        else {
//            if (update(new UpdateWrapper<Blog>().setSql("liked = liked + 1").eq("id", id))) {
//                //stringRedisTemplate.opsForSet().add(key, userId.toString());
//            }
//        }
        return Result.ok();
    }
    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        // 1.查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 3.根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 4.返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        if(!blogService.save(blog)){
            return Result.fail("发送失败");
        }
        Long userId = user.getId();
        List<Follow> fans = followService.list(new QueryWrapper<Follow>().eq("follow_user_id",userId));
        for (Follow fan: fans) {
            Long fanId = fan.getId();
            String key = "feed:"+fanId;
            //发给每个粉丝的博客
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        //2.查询收件箱
        String key = "feed:"+userId;//表示你作为粉丝会收到哪些博客
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        if(typedTuples==null||typedTuples.isEmpty()){
            return Result.ok();
        }
        List<Long> blogIds = new ArrayList<>(typedTuples.size());
        long minTime = 0;//最小时间戳
        int os = 1;//实际上我认为时间戳理论上是不会有相同的，但是还是判断一下为好
        for (ZSetOperations.TypedTuple<String> tuple:
                typedTuples) {
            String blogId = tuple.getValue();
            blogIds.add(Long.valueOf(blogId));
            long time = tuple.getScore().longValue();
            if(time==minTime){
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }
        String blogIdStr = StrUtil.join(",", blogIds);
        List<Blog> blogs = list(new QueryWrapper<Blog>().in("id",blogIds).last("ORDER BY FIELD(id," + blogIdStr + ")"));
        for (Blog blog:
             blogs) {
            queryBlogUser(blog);
            isBlogLikedByCurrentUser(blog);
        }
        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);

        return Result.ok(r);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

}
