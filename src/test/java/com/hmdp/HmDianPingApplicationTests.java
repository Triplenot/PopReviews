package com.hmdp;

import com.hmdp.service.impl.ssi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@SpringBootTest
class HmDianPingApplicationTests {

    @Autowired
    private RedisTemplate redisTemplate;

    @Resource
    private ssi shopService;

    @Test
    public void test(){
        System.out.println(redisTemplate);
    }

    @Test
    public void jiaoji(){
//        List<String> list1 = new ArrayList<>();
//        list1.add("1");
//        list1.add("2");
//        list1.add("2");
//        list1.add("3");
//        list1.add("5");
//
//        List<String> list2 = new ArrayList<>();
//        list2.add("2");
//        list2.add("3");
//        list2.add("6");
//        list2.add("7");
//
//        // 交集
//        List<String> intersection = list1.stream().filter(item -> list2.contains(item)).distinct().collect(Collectors.toList());
//        System.out.println("---交集 intersection---");
//        intersection.parallelStream().forEach(System.out::println);

        LocalDate now = LocalDate.now();
        System.out.println(LocalDate.now());
        System.out.println(LocalDateTime.now());
        String format = now.format(DateTimeFormatter.ofPattern("YYYY-MM"));
        System.out.println(format);
    }
}
