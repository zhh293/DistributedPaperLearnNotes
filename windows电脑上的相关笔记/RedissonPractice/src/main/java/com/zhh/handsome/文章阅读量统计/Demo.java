package com.zhh.handsome.文章阅读量统计;

public class Demo {
    //场景资讯应用中，多篇文章的阅读量需要实时统计，支持高并发（同一时间 thousands 级用户阅读同一篇文章）。


    /*用数据库自增（UPDATE article SET read_count = read_count + 1）在高并发下会导致大量锁等待（写锁），性能低下。
    单台服务器用内存计数器（如 AtomicLong），多实例部署时数据无法汇总，最终统计不准确。*/


//    用 RAtomicLong 分布式计数器，所有服务器共享同一个计数器，每次阅读时原子性递增，性能远高于数据库。



    /*@Service
    public class ArticleService {
        @Autowired
        private RedissonClient redissonClient;
        @Autowired
        private ArticleMapper articleMapper;

        // 获取分布式计数器（按文章ID区分）
        private RAtomicLong getReadCountCounter(Long articleId) {
            return redissonClient.getAtomicLong("article:read:count:" + articleId);
        }

        // 文章阅读：递增阅读量
        public void readArticle(Long articleId) {
            // 1. 分布式计数器原子性+1（无需加锁，底层Redis单线程保证原子性）
            getReadCountCounter(articleId).incrementAndGet();

            // 2. （可选）定期同步到数据库（避免频繁写库）
            syncToDbIfNeeded(articleId);
        }

        // 获取实时阅读量
        public long getReadCount(Long articleId) {
            return getReadCountCounter(articleId).get();
        }

        // 定期同步到数据库（如每100次阅读同步一次）
        private void syncToDbIfNeeded(Long articleId) {
            RAtomicLong counter = getReadCountCounter(articleId);
            long current = counter.get();
            if (current % 100 == 0) { // 每100次同步一次
                articleMapper.updateReadCount(articleId, current);
            }
        }
    }*/


    /*RAtomicLong 底层基于 Redis 的 INCR 命令，单线程执行，天然支持原子性，性能极高（每秒可处理 10 万 + 次递增）。
    同步数据库采用「批量同步」策略，减少 DB 压力（最终一致性，适合阅读量这种非核心实时数据）。*/



}
