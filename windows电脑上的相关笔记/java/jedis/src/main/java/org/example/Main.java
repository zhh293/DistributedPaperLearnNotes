package org.example;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

//TIP 要<b>运行</b>代码，请按 <shortcut actionId="Run"/> 或
// 点击装订区域中的 <icon src="AllIcons.Actions.Execute"/> 图标。
public class Main {
    private static final JedisPool jedispool;
    static {
        //配置连接池
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(10);
        config.setMaxIdle(5);
        config.setMaxWaitMillis(1000);
        config.setMinIdle(0);
        //创建连接池对象
        jedispool = new JedisPool(config, "192.168.1.100", 6379);
    }
   public static Jedis getJedis(){
       return jedispool.getResource();
   }
}