package org.example.redisdemo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.redisdemo.pojo.user;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class RedisDemoApplicationTests {
  /*@Autowired
  private org.springframework.data.redis.core.RedisTemplate<String,Object> redisTemplate;*/
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    @Test
    void contextLoads() throws JsonProcessingException {
        /*redisTemplate.opsForValue().set("name","虎哥");
        System.out.println(redisTemplate.opsForValue().get("name"));*/
        /*redisTemplate.opsForValue().set("user",new user("虎哥", "18"));
        user user = (user) redisTemplate.opsForValue().get("user");
        System.out.println(user);*/
        user user = new user("虎哥", "18");
        String s = objectMapper.writeValueAsString(user);
        stringRedisTemplate.opsForValue().set("user",s);
        String user1 = stringRedisTemplate.opsForValue().get("user");
        user user2 = objectMapper.readValue(user1, user.class);
        System.out.println(user2);

    }
    @Test
    void test02() throws JsonProcessingException {
        stringRedisTemplate.opsForHash().put("user:1","name","虎哥");
        stringRedisTemplate.opsForHash().put("user:1","age","18");
        System.out.println(stringRedisTemplate.opsForHash().entries("user:1"));
    }
//    SpringDataRedis的序列化方式 RedisTemplate可以接收任意0bject作为值写入Redis，只不过写入前会把0bject序列化为字节形式，默认是采用JDK  序列化
}






//可读性太差，所以我们需要换序列化器key一般用string序列化器，value一般用json序列化器
//但是，之哦后又发现了问题，我们把对象写入Redis后，取出来时，对象属性值是乱码，因为RedisTemplate默认的序列化器是jdk序列化器，而jdk序列化器只能序列化基本数据类型，对象无法序列化，所以需要换序列化器
//而json序列化器也有问题，浪费内存
//所以统一使用string序列化器
//对于需要存储java对象的情况，手动完成序列化和反序列化


/*StringRedisTemplate

Spring默认提供了一个StringRedisTemplate类，它的key和value的序列化方式默认就是String方式。省去了我们自定

义RedisTemplate的过程:

@Autowired

private StringRedisTemplate stringRedisTemplate;

//JSONT具

private static final 0bjectMapper mapper = new ObjectMapper();

@Test

void testStringTemplate()throws JsonProcessingException{

    11准备对象

    User user =new User("虎哥"，18);

    11手动序列化

    String json = mapper.writeValueAsString(user);

//号入-条数据到redis

    stringRedisTemplate.opsForValue().set("user:200",json);

    1l 读取数据

    String val =stringRedisTemplate.opsForValue().get("user:200");

    11反序列化

    User userl =mapper.readValue(val,User.class);

    System.out.println("user1 =+userl);*/
