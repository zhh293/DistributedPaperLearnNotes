package com.zhh.handsome.超卖问题;

import com.zhh.handsome.Config.RedissonClientFactory;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;

public class Demo {
    private RedissonClient redissonClient = RedissonClientFactory.getRedissonClient();
/*    public String seckill(Long productId, Long userId){
        // 1. 获取分布式锁（锁的名称需唯一，用商品ID标识）
        RLock lock = redissonClient.getLock("seckill:lock:" + productId);

        try {
            // 2. 尝试加锁（最多等待10秒，10秒后自动释放锁，防止死锁）
            boolean locked = lock.tryLock(10, 10, TimeUnit.SECONDS);
            if (!locked) {
                return "抢购太火爆，请重试";
            }

            // 3. 加锁成功后，查询库存
            Product product = productMapper.selectById(productId);
            if (product.getStock() <= 0) {
                return "商品已抢完";
            }

            // 4. 扣减库存（数据库操作）
            product.setStock(product.getStock() - 1);
            productMapper.updateById(product);

            // 5. 记录订单（省略订单逻辑）
            return "抢购成功";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "系统异常";
        } finally {
            // 6. 释放锁（仅持有锁的线程能释放）
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}*/
}
