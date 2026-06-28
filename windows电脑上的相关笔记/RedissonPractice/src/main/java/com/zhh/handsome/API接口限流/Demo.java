package com.zhh.handsome.API接口限流;

public class Demo {

    /*场景
    用户登录接口需限制流量：每秒最多允许 100 次请求，防止恶意刷接口或流量峰值压垮服务。
    痛点
    无限制的请求可能导致服务资源耗尽（如数据库连接池占满）。
    传统限流方案（如 Guava 限流器）仅能限制单台服务器，分布式部署时总流量无法控制。*/



//    用 RRateLimiter 分布式限流器（基于令牌桶算法），控制所有服务器的总请求量，确保每秒不超过 100 次


    /*@RestController
    @RequestMapping("/user")
    public class UserController {
        @Autowired
        private RedissonClient redissonClient;
        @Autowired
        private UserService userService;

        // 初始化限流器（项目启动时执行）
        @PostConstruct
        public void initRateLimiter() {
            // 1. 获取限流器（名称：login:rate:limiter）
            RRateLimiter rateLimiter = redissonClient.getRateLimiter("login:rate:limiter");
            // 2. 配置：每秒产生100个令牌（即每秒最多100次请求）
            rateLimiter.trySetRate(RateType.OVERALL, 100, 1, RateUnit.SECONDS);
        }

        // 用户登录接口
        @PostMapping("/login")
        public Result login(@RequestBody LoginDTO dto) {
            // 1. 获取限流器
            RRateLimiter rateLimiter = redissonClient.getRateLimiter("login:rate:limiter");

            // 2. 尝试获取1个令牌（如果没有令牌，直接拒绝）
            boolean canPass = rateLimiter.tryAcquire(1);
            if (!canPass) {
                return Result.fail("请求过于频繁，请稍后再试");
            }

            // 3. 限流通过，执行登录逻辑
            return userService.login(dto);
        }
    }*/



//这个是针对这个接口而言的，不需要提供用户id之类的，就是单纯为了保护一个接口的请求频率

   /* RateType.OVERALL 表示所有服务器共享此限流规则（总流量控制）；若用 RateType.PER_CLIENT，则按客户端 IP 单独限流。
    令牌桶算法允许「突发流量」（如瞬间有 100 个请求，只要令牌足够就会通过），适合实际业务场景。
*/















}
