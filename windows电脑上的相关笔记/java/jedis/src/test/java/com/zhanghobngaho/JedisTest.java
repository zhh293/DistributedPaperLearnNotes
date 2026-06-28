package com.zhanghobngaho;

import org.example.Main;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.util.Map;

public class JedisTest {
    private Jedis jedis;
    @BeforeEach
    public void setUp() {
        //jedis = new Jedis("localhost", 6379);
        // 设置密码
        //jedis.auth("123456");
        jedis = Main.getJedis();
        // 选择数据库
        jedis.select(0);
    }
    @Test
    void testString(){
        String set = jedis.set("name", "zhangsan");
        System.out.println(set);
        String name = jedis.get("name");
        System.out.println(name);
    }
    @Test
    void testHash(){
        jedis.hset("user:1", "name", "zhangsan");
        jedis.hset("user:1", "age", "18");
        String name = jedis.hget("user:1", "name");
        System.out.println(name);
        Map<String, String> stringStringMap = jedis.hgetAll("user:1");
        System.out.println(stringStringMap);
    }
    @AfterEach
    public  void tearDown() {
        if (jedis != null) {
            jedis.close();
        }
    }
}
/*Jedis本身是线程不安全的，并且频繁的创建和销毁连接会有性能损耗，因此我们推荐大家使用Jedis连接池代替ledis的

直连方式。

public class JedisConnectionFactory{

    private static final JedisPool jedisPool;

    static {

        JedisPoolConfig jedisPoolconfig= new JedisPoolconfig();

        11最大连接

        jedisPoolconfig.setMaxTotal(8);

        11最大空闲连接

        jedisPoolConfig.setMaxIǎle(8);

        11最小空闲连接

        jedisPoolConfig.setMinIdle(0);

        设置最长等待时间，ms

        jedisPoolConfig.setMaxWaitMillis(200);

        jedisPool = new JedisPool(jedisPoolconfig,"192.168.150.101"，6379,

                1000，“123321");

                1!获取Jedis对象

        public static Jedis getJedis(){

            return jedisPool.getResource();*/


