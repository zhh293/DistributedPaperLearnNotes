package com.zhh.handsome.缓存穿透防护;

public class Demo {

   /* 场景
    电商商品详情查询接口，用户可能查询不存在的商品 ID（如恶意请求），导致请求穿透缓存直接打数据库，压垮 DB。
    痛点
    缓存穿透：缓存和数据库都没有数据，请求每次都命中 DB，高并发下 DB 压力剧增。
    传统方案（如缓存空值）可能导致缓存中堆积大量无效空值，浪费内存。
    Redisson 解决方案
    用 RBloomFilter 布隆过滤器，预先将所有存在的商品 ID 存入过滤器，查询时先通过过滤器判断 ID 是否存在：不存在则直接返回，避免访问 DB。*/


    //这里贴一下我之前在一个springboot项目里面实现的缓存穿透的代码，使用的就是redisson加布隆过滤器


    /*@Service
    public class ProductService {
        @Autowired
        private RedissonClient redissonClient;
        @Autowired
        private RedisTemplate<String, Object> redisTemplate; // 缓存工具
        @Autowired
        private ProductMapper productMapper;

        // 初始化布隆过滤器（项目启动时执行）
        @PostConstruct
        public void initBloomFilter() {
            // 1. 获取布隆过滤器（名称：product:exist，存储商品ID）
            RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter("product:exist");
            // 2. 初始化：预期插入100万条数据，误判率0.01（误判率越低，占用Redis空间越大）
            bloomFilter.tryInit(1000000, 0.01);

            // 3. 从数据库加载所有商品ID，存入过滤器
            List<Long> allProductIds = productMapper.selectAllProductIds();
            allProductIds.forEach(bloomFilter::add);
        }

        // 查询商品详情
        public Product getProductDetail(Long productId) {
            // 1. 先查布隆过滤器：如果不存在，直接返回null
            RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter("product:exist");
            if (!bloomFilter.contains(productId)) {
                return null; // 不存在的商品，直接返回
            }

            // 2. 布隆过滤器判断存在，再查缓存
            String cacheKey = "product:detail:" + productId;
            Product product = (Product) redisTemplate.opsForValue().get(cacheKey);
            if (product != null) {
                return product;
            }

            // 3. 缓存未命中，查数据库
            product = productMapper.selectById(productId);
            if (product != null) {
                // 4. 存入缓存（设置过期时间，避免缓存雪崩）
                redisTemplate.opsForValue().set(cacheKey, product, 1, TimeUnit.HOURS);
            }
            return product;
        }
    }*/




    /*关键说明
    布隆过滤器有「误判率」（可能把不存在的 ID 判为存在），但不会漏判（存在的 ID 一定能判为存在），适合允许少量误判的场景。
    新商品上架时，需调用 bloomFilter.add(newProductId) 同步更新过滤器。*/


}
