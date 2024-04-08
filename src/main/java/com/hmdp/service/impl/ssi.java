//package com.hmdp.service.impl;
//
//import cn.hutool.core.util.BooleanUtil;
//import cn.hutool.core.util.StrUtil;
//import cn.hutool.json.JSONObject;
//import cn.hutool.json.JSONUtil;
//import com.hmdp.dto.Result;
//import com.hmdp.entity.Shop;
//import com.hmdp.mapper.ShopMapper;
//import com.hmdp.service.IShopService;
//import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
//import com.hmdp.utils.RedisData;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.data.redis.core.StringRedisTemplate;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.time.LocalDateTime;
//import java.util.concurrent.ExecutorService;
//import java.util.concurrent.Executors;
//import java.util.concurrent.TimeUnit;
//
//import static com.hmdp.utils.RedisConstants.*;
//
///**
// * <p>
// *  服务实现类
// * </p>
// *
// * @author 虎哥
// * @since 2021-12-22
// */
//@Service
//public class ssi extends ServiceImpl<ShopMapper, Shop> implements IShopService {
//    @Autowired
//    private StringRedisTemplate stringRedisTemplate;
//
//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
//    @Override
//    public Result queryById(Long id) {
//        Shop shop = queryWithMutex(id);
//        if(shop == null){
//            Result.fail("店铺不存在");
//        }
//        return Result.ok(shop);
//    }
//
//    //逻辑过期方式解决缓存穿透和缓存击穿
//    public Shop queryWithLogicalExpire(Long id){
//        String key = CACHE_SHOP_KEY+id;
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        //缓存未命中
//        if(StrUtil.isBlank(shopJson)){
//            return null;
//            //由于缓存中的数据不会真正过期，所以如果缓存里查不到数据那么就说明数据库里也查不到，可以直接返回空
//        }
//
//        //缓存命中，则看这个缓存数据是否逻辑过期
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//        if(expireTime.isAfter(LocalDateTime.now())) {
//            //未过期，直接返回店铺信息
//            return shop;
//        }
//            //过期了，则尝试获取锁
//        String lockKey = LOCK_SHOP_KEY+id;
//        boolean getLock = tryLock(lockKey);
//        if(getLock){
//                //获取锁成功
//            CACHE_REBUILD_EXECUTOR.submit(() -> {
//                try {
//                    // 重建缓存
//                    this.saveShopToRedis(id, 30L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                }finally {
//                    // 释放锁
//                    unLock(lockKey);
//                }
//            });
//        }
//        return shop;
//    }
//
//    //缓存穿透和互斥锁方式解决缓存击穿
//    public Shop queryWithMutex(Long id) {
//        String key = CACHE_SHOP_KEY+id;
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        if(shopJson!=null){
//            if(shopJson==""){
//                return null;//第二次以及之后查到空数据时
//            }
//            return JSONUtil.toBean(shopJson,Shop.class);
//        }
//        //实现缓存重建
//        String lockKey = null;
//        Shop shop = null;
//        try {
//            lockKey = LOCK_SHOP_KEY+id;
//            boolean getLock = tryLock(lockKey);
//            if(!getLock){
//                //获取锁失败
//                Thread.sleep(50);
//                return queryWithMutex(id);
//            }
//            shop = getById(id);
//            if(shop == null){
//                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//                return null;//第一次查到空数据时
//            }
//            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shopJson),30, TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }finally {
//            unLock(lockKey);
//        }
//
//        return shop;
//    }
//
//    //缓存穿透
//    public Shop queryWithPassThrough(Long id){
//        String key = CACHE_SHOP_KEY+id;
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        if(shopJson!=null){
//            if(shopJson==""){
//                return null;//第二次以及之后查到空数据时
//            }
//            return JSONUtil.toBean(shopJson,Shop.class);
//        }
//        Shop shop = getById(id);
//        if(shop == null){
//            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return null;//第一次查到空数据时
//        }
//        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shopJson),30, TimeUnit.MINUTES);
//        return shop;
//    }
//
//    private boolean tryLock(String key){
//        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
//        return BooleanUtil.isTrue(flag);
//    }
//
//    private void unLock(String key){
//        stringRedisTemplate.delete(key);
//    }
//
//    public void saveShopToRedis(Long id,Long expireSeconds){
//        Shop shop = getById(id);
//        RedisData redisData = new RedisData();
//        redisData.setData(shop);
//        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop));
//    }
//    @Override
//    @Transactional
//    public Result update(Shop shop) {
//        Long id = shop.getId();
//        if(id == null){
//            return Result.fail("店铺id不能为空");
//        }
//        updateById(shop);
//        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
//        return Result.ok();
//    }
//}
