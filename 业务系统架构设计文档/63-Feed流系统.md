# Feed流系统架构设计

## 一、问题背景

### 1.1 业务场景

Feed流系统是社交内容平台的核心，用户关注了一些人/话题后，打开App时看到的就是这些人/话题最新发布的内容列表。由于内容按时间排列，Feed流也称为Timeline（时间线）。

典型场景：用户A关注了用户B，用户B发了一条动态，用户A的Feed流中需要实时看到这条动态。

### 1.2 核心挑战

| 挑战维度 | 具体问题 |
|---------|---------|
| 实时性 | 关注的人发了内容，用户要在短时间内看到 |
| 高并发读 | Feed流是首页，QPS可达几十万 |
| 明星问题 | 明星有千万级粉丝，发一条内容要推给所有人 |
| 存储成本 | 推模式需要给每个用户维护收件箱，数据量巨大 |
| 聚合复杂 | 每条Feed需要聚合用户信息、点赞数、评论数等 |
| 取消关注 | 取消关注后要从Feed流中过滤掉对方内容 |

### 1.3 三种方案对比

| 方案 | 原理 | 优点 | 缺点 | 适用场景 |
|------|------|------|------|---------|
| 推模式 | 发内容时写入所有粉丝收件箱 | 读性能好（只查自己的收件箱） | 写放大严重、存储成本高 | 粉丝数有限（如朋友圈） |
| 拉模式 | 读Feed时拉取所有关注人的发件箱 | 无写放大、存储成本低 | 读性能差（聚合多个发件箱） | 粉丝数无上限 |
| 推拉结合 | 普通用户推、大V拉 | 平衡读写性能 | 架构复杂 | 大规模社交平台 |

---

## 二、整体架构设计

### 2.1 推拉结合架构

```
┌──────────────────────────────────────────────────────────────────┐
│                    Feed流系统架构（推拉结合）                       │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────┐                    ┌─────────────┐             │
│  │ 发布动态    │                    │ 拉取Feed    │             │
│  │ (写链路)    │                    │ (读链路)    │             │
│  └──────┬──────┘                    └──────┬──────┘             │
│         │                                  │                      │
│  ┌──────┴──────────────────────────────────┴──────┐             │
│  │            Feed核心服务                          │             │
│  │                                                  │             │
│  │  普通用户(< 1000粉丝)  →  推模式                │             │
│  │  ·发内容时写入粉丝收件箱                         │             │
│  │                                                  │             │
│  │  大V用户(>= 1000粉丝)  →  拉模式                │             │
│  │  ·发内容只写自己的发件箱                         │             │
│  │  ·粉丝读Feed时实时拉取                           │             │
│  └────────────────────┬───────────────────────────┘             │
│                       │                                          │
│          ┌────────────┼────────────┐                            │
│          ▼            ▼            ▼                            │
│    ┌──────────┐ ┌──────────┐ ┌──────────┐                      │
│    │ Redis    │ │ MySQL    │ │ MQ       │                      │
│    │收件箱    │ │Feed表    │ │异步推送  │                      │
│    │(ZSet)   │ │Timeline表│ │          │                      │
│    └──────────┘ └──────────┘ └──────────┘                      │
│                                                                  │
│  外部依赖: 用户系统 内容系统 计数系统(点赞/评论数)                  │
└──────────────────────────────────────────────────────────────────┘
```

### 2.2 数据结构设计

```sql
-- Feed内容表（存储动态内容）
CREATE TABLE feed (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    feed_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL COMMENT '发布者',
    content TEXT COMMENT '内容',
    type TINYINT DEFAULT 0 COMMENT '0图文 1视频 2纯文本',
    status TINYINT DEFAULT 1 COMMENT '0删除 1正常',
    create_time DATETIME,
    UNIQUE KEY uk_feed_id (feed_id),
    INDEX idx_user_time (user_id, create_time)
) ENGINE=InnoDB;

-- 发件箱表（用户自己发布的内容）
CREATE TABLE outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    feed_id BIGINT NOT NULL,
    create_time DATETIME,
    INDEX idx_user_time (user_id, create_time)
) ENGINE=InnoDB;

-- 收件箱表（推模式：粉丝的收件箱）
-- 使用Redis ZSet存储，member=feedId, score=timestamp
-- Key: inbox:{userId}
```

---

## 三、核心链路设计

### 3.1 发布动态（写链路）

```java
/**
 * 发布动态服务
 *
 * 推拉结合策略：
 * 1. 普通用户（粉丝<1000）：推模式，写入所有粉丝收件箱
 * 2. 大V用户（粉丝>=1000）：拉模式，只写自己的发件箱
 * 3. 无论哪种模式，都写自己的发件箱
 */
@Service
public class FeedPublishService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private FeedMapper feedMapper;

    @Autowired
    private OutboxMapper outboxMapper;

    @Autowired
    private FollowFeignClient followClient;

    @Autowired
    private RocketMQTemplate mqTemplate;

    private static final int BIG_V_THRESHOLD = 1000; // 大V阈值

    /**
     * 发布动态
     */
    public Long publish(PublishRequest request, Long userId) {
        // 1. 写入Feed内容表
        long feedId = idGenerator.nextId();
        Feed feed = new Feed();
        feed.setFeedId(feedId);
        feed.setUserId(userId);
        feed.setContent(request.getContent());
        feed.setType(request.getType());
        feed.setStatus(1);
        feed.setCreateTime(new Date());
        feedMapper.insert(feed);

        // 2. 写入发件箱
        Outbox outbox = new Outbox();
        outbox.setUserId(userId);
        outbox.setFeedId(feedId);
        outbox.setCreateTime(feed.getCreateTime());
        outboxMapper.insert(outbox);

        // 3. 判断是否为大V
        int followerCount = followClient.getFollowerCount(userId);

        if (followerCount < BIG_V_THRESHOLD) {
            // 普通用户：推模式
            pushToFollowers(feedId, userId, feed.getCreateTime().getTime());
        } else {
            // 大V：拉模式，只写发件箱，不做推送
            // 粉丝读Feed时会实时拉取大V的发件箱
            log.info("大V发布动态, 不推送给粉丝, userId={}, feedId={}",
                userId, feedId);
        }

        return feedId;
    }

    /**
     * 推模式：写入所有粉丝的收件箱
     */
    private void pushToFollowers(Long feedId, Long publisherId, long timestamp) {
        // 通过MQ异步推送，避免阻塞发布操作
        PushMessage msg = new PushMessage();
        msg.setFeedId(feedId);
        msg.setPublisherId(publisherId);
        msg.setTimestamp(timestamp);
        mqTemplate.asyncSend("feed-push-topic", msg, null);
    }
}

/**
 * 推送消费者：将Feed写入粉丝收件箱
 */
@Component
@RocketMQMessageListener(topic = "feed-push-topic",
    consumerGroup = "feed-push-consumer-group")
public class FeedPushConsumer implements RocketMQListener<PushMessage> {

    @Override
    public void onMessage(PushMessage msg) {
        // 1. 获取发布者的所有粉丝
        List<Long> followerIds = followClient.getFollowerIds(msg.getPublisherId());

        // 2. 批量写入粉丝收件箱（Redis ZSet）
        // Key: inbox:{userId}, Score: timestamp, Member: feedId
        for (Long followerId : followerIds) {
            String inboxKey = "inbox:" + followerId;
            redisTemplate.opsForZSet().add(inboxKey,
                String.valueOf(msg.getFeedId()),
                msg.getTimestamp());

            // 限制收件箱大小（只保留最近1000条）
            Long size = redisTemplate.opsForZSet().zCard(inboxKey);
            if (size != null && size > 1000) {
                redisTemplate.opsForZSet().removeRange(inboxKey, 0, 0);
            }
        }
    }
}
```

### 3.2 拉取Feed流（读链路）

```java
/**
 * Feed流读取服务
 *
 * 读链路：
 * 1. 从收件箱获取推送的Feed（普通用户发的）
 * 2. 从关注的大V的发件箱拉取最新Feed
 * 3. 合并、排序、去重
 * 4. 过滤已删除/取消关注的内容
 * 5. 聚合用户信息、计数信息
 */
@Service
public class FeedQueryService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private OutboxMapper outboxMapper;

    @Autowired
    private FeedMapper feedMapper;

    @Autowired
    private FollowFeignClient followClient;

    @Autowired
    private UserFeignClient userClient;

    @Autowired
    private CountFeignClient countClient;

    /**
     * 获取Feed流
     * @param userId 用户ID
     * @param lastFeedId 上一页最后一条Feed ID（游标分页）
     * @param pageSize 每页数量
     */
    public FeedListVO getFeedList(Long userId, Long lastFeedId, int pageSize) {
        // 1. 从收件箱获取推送的Feed ID（普通用户发的）
        Set<ZSetOperations.TypedTuple<String>> inboxFeeds;
        String inboxKey = "inbox:" + userId;

        if (lastFeedId == null) {
            // 首页：获取最新的
            inboxFeeds = redisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(inboxKey,
                    0, System.currentTimeMillis(), 0, pageSize);
        } else {
            // 翻页：获取上一页最后一条之前的
            Double lastScore = redisTemplate.opsForZSet()
                .score(inboxKey, String.valueOf(lastFeedId));
            if (lastScore == null) {
                inboxFeeds = redisTemplate.opsForZSet()
                    .reverseRangeByScoreWithScores(inboxKey,
                        0, System.currentTimeMillis(), 0, pageSize);
            } else {
                inboxFeeds = redisTemplate.opsForZSet()
                    .reverseRangeByScoreWithScores(inboxKey,
                        0, lastScore - 1, 0, pageSize);
            }
        }

        // 2. 获取关注的大V列表
        List<Long> bigVIds = followClient.getFollowingBigVs(userId);

        // 3. 从大V的发件箱拉取最新Feed
        long queryBefore = lastFeedId != null ?
            getLastFeedTime(lastFeedId) : System.currentTimeMillis();

        List<Feed> bigVFeeds = new ArrayList<>();
        for (Long bigVId : bigVIds) {
            List<Outbox> outboxItems = outboxMapper.selectRecent(
                bigVId, new Date(queryBefore), pageSize);
            for (Outbox outbox : outboxItems) {
                Feed feed = feedMapper.selectByFeedId(outbox.getFeedId());
                if (feed != null && feed.getStatus() == 1) {
                    bigVFeeds.add(feed);
                }
            }
        }

        // 4. 合并收件箱的Feed和大V的Feed
        List<Feed> allFeeds = new ArrayList<>();

        // 收件箱的Feed需要查询内容
        for (ZSetOperations.TypedTuple<String> tuple : inboxFeeds) {
            Long feedId = Long.parseLong(tuple.getValue());
            Feed feed = feedMapper.selectByFeedId(feedId);
            if (feed != null && feed.getStatus() == 1) {
                allFeeds.add(feed);
            }
        }
        allFeeds.addAll(bigVFeeds);

        // 5. 按时间倒序排序
        allFeeds.sort((a, b) -> b.getCreateTime().compareTo(a.getCreateTime()));

        // 6. 截取一页
        List<Feed> pageFeeds = allFeeds.stream()
            .limit(pageSize)
            .collect(Collectors.toList());

        // 7. 聚合用户信息和计数
        List<FeedVO> voList = enrichFeeds(pageFeeds);

        FeedListVO result = new FeedListVO();
        result.setFeedList(voList);
        result.setHasMore(allFeeds.size() > pageSize);
        if (!pageFeeds.isEmpty()) {
            result.setLastFeedId(pageFeeds.get(pageFeeds.size() - 1).getFeedId());
        }
        return result;
    }

    /**
     * 聚合用户信息和计数
     */
    private List<FeedVO> enrichFeeds(List<Feed> feeds) {
        if (feeds.isEmpty()) return Collections.emptyList();

        // 批量查询用户信息
        Set<Long> userIds = feeds.stream()
            .map(Feed::getUserId).collect(Collectors.toSet());
        Map<Long, UserVO> userMap = userClient.batchQuery(userIds);

        // 批量查询Feed计数（点赞、评论、转发）
        Set<Long> feedIds = feeds.stream()
            .map(Feed::getFeedId).collect(Collectors.toSet());
        Map<Long, CountVO> countMap = countClient.batchQueryCounts(feedIds);

        return feeds.stream().map(feed -> {
            FeedVO vo = new FeedVO();
            vo.setFeedId(feed.getFeedId());
            vo.setContent(feed.getContent());
            vo.setType(feed.getType());
            vo.setCreateTime(feed.getCreateTime());
            vo.setUser(userMap.get(feed.getUserId()));
            vo.setCount(countMap.get(feed.getFeedId()));
            return vo;
        }).collect(Collectors.toList());
    }
}
```

### 3.3 取消关注处理

```java
/**
 * 取消关注处理
 *
 * 策略：不物理删除收件箱中对方的内容
 * 在读取时过滤掉已取消关注的内容
 * 原因：物理删除开销太大（对方可能发了上千条内容）
 */
@Service
public class UnfollowService {

    /**
     * 取消关注
     */
    public void unfollow(Long userId, Long targetUserId) {
        // 1. 删除关注关系
        followClient.unfollow(userId, targetUserId);

        // 2. 不删除收件箱中的内容，读取时过滤
        // 这样做的原因：
        // - 物理删除需要遍历收件箱找出targetUserId的所有Feed，开销大
        // - 用户可能长时间不登录，延迟删除浪费资源
        // - 读取时过滤更简单高效
    }
}
```

---

## 四、性能优化

### 4.1 收件箱缓存策略

```java
/**
 * 收件箱Redis ZSet优化
 *
 * 优化点：
 * 1. 限制收件箱大小（最多1000条），超过则删除最旧的
 * 2. 设置TTL（7天不活跃用户清理收件箱）
 * 3. 使用Pipeline批量写入
 */
@Service
public class InboxCacheService {

    private static final int MAX_INBOX_SIZE = 1000;
    private static final long INBOX_TTL = 7 * 24 * 3600; // 7天

    /**
     * 写入收件箱
     */
    public void addToInbox(Long userId, Long feedId, long timestamp) {
        String key = "inbox:" + userId;

        // Pipeline批量操作
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            // 1. 添加Feed
            connection.zAdd(key.getBytes(), timestamp,
                String.valueOf(feedId).getBytes());
            // 2. 限制大小
            connection.zRemRange(key.getBytes(), 0, 0);
            // 3. 刷新TTL
            connection.expire(key.getBytes(), INBOX_TTL);
            return null;
        });
    }
}
```

### 4.2 聚合查询优化

```java
/**
 * Feed聚合查询优化
 *
 * 优化策略：
 * 1. 用户信息走本地缓存（Caffeine）
 * 2. 计数信息走Redis缓存
 * 3. 批量查询代替逐条查询
 */
@Service
public class FeedEnrichService {

    // 用户信息本地缓存
    private final Cache<Long, UserVO> userCache = Caffeine.newBuilder()
        .maximumSize(50000)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .build();

    /**
     * 批量聚合
     */
    public List<FeedVO> enrichBatch(List<Feed> feeds) {
        // 1. 本地缓存命中的用户
        Set<Long> missedUserIds = new HashSet<>();
        Map<Long, UserVO> userMap = new HashMap<>();

        for (Feed feed : feeds) {
            UserVO cached = userCache.getIfPresent(feed.getUserId());
            if (cached != null) {
                userMap.put(feed.getUserId(), cached);
            } else {
                missedUserIds.add(feed.getUserId());
            }
        }

        // 2. 批量查询未命中用户
        if (!missedUserIds.isEmpty()) {
            Map<Long, UserVO> fetched = userClient.batchQuery(missedUserIds);
            userMap.putAll(fetched);
            fetched.forEach(userCache::put);
        }

        // 3. 批量查询计数（走Redis MGET）
        Set<Long> feedIds = feeds.stream()
            .map(Feed::getFeedId).collect(Collectors.toSet());
        Map<Long, CountVO> countMap = batchQueryCountsFromRedis(feedIds);

        // 4. 组装结果
        return feeds.stream().map(feed -> {
            FeedVO vo = new FeedVO();
            vo.setFeedId(feed.getFeedId());
            vo.setContent(feed.getContent());
            vo.setUser(userMap.get(feed.getUserId()));
            vo.setCount(countMap.get(feed.getFeedId()));
            return vo;
        }).collect(Collectors.toList());
    }
}
```

---

## 五、最佳实践与总结

### 5.1 核心设计决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 推拉策略 | 普通用户推、大V拉 | 平衡写放大和读性能 |
| 收件箱存储 | Redis ZSet | 天然按score排序、高效范围查询 |
| 收件箱大小 | 限制1000条 | 控制内存、用户只看最近内容 |
| 取消关注 | 读取时过滤 | 避免物理删除开销 |
| 聚合查询 | 批量+多级缓存 | 降低RPC调用次数 |
| 分页方式 | 游标分页 | 避免深度分页问题 |

### 5.2 容易踩的坑

1. **大V推模式**：明星发一条动态推给千万粉丝，写放大严重导致消息延迟数小时
2. **收件箱无限增长**：不限制大小，Redis内存爆炸
3. **取消关注物理删除**：遍历收件箱删除大量数据，影响性能
4. **聚合查询逐条查**：N条Feed发N次RPC查询，应该批量查询
5. **深度分页**：用offset分页，翻到后面越来越慢，应该用游标分页

---

## 六、全链路实战案例

### 案例一：用户发布动态的写扩散全链路

#### 6.1.1 全链路流程图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      发布动态写扩散全链路                                  │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  用户App                                                                 │
│    │                                                                    │
│    ▼                                                                    │
│  ┌──────────────┐                                                       │
│  │ API Gateway   │  限流、鉴权、参数预校验                                │
│  └──────┬───────┘                                                       │
│         ▼                                                               │
│  ┌──────────────┐                                                       │
│  │ FeedPublish   │  1. 参数校验（文本/图片/视频/@用户/话题）               │
│  │ Controller    │  2. 幂等检查（防重复提交）                              │
│  └──────┬───────┘                                                       │
│         ▼                                                               │
│  ┌──────────────┐                                                       │
│  │ ContentAudit  │  3. 敏感词过滤（DFA算法）                              │
│  │ Service       │  4. 图片鉴黄（AI审核异步回调）                           │
│  └──────┬───────┘                                                       │
│         ▼                                                               │
│  ┌──────────────┐                                                       │
│  │ FeedPublish   │  5. 动态内容入库（feed表）                              │
│  │ Service       │  6. 写发件箱（outbox表）                               │
│  │               │  7. 判断大V/普通用户                                   │
│  └──────┬───────┘                                                       │
│         │                                                               │
│    ┌────┴────┐                                                          │
│    ▼         ▼                                                          │
│  普通用户    大V用户                                                      │
│    │         │                                                          │
│    ▼         ▼                                                          │
│  ┌──────────┐ ┌──────────┐                                             │
│  │ MQ异步    │ │ 仅写发件箱 │  粉丝读Feed时拉取                            │
│  │ 推送      │ │ 不推送    │                                             │
│  └────┬─────┘ └──────────┘                                             │
│       ▼                                                                 │
│  ┌──────────────┐                                                       │
│  │ FeedPush      │  8. 分批获取粉丝列表                                   │
│  │ Consumer      │  9. Pipeline批量写入Redis ZSet                        │
│  │               │  10. 限制收件箱大小                                    │
│  └──────┬───────┘                                                       │
│         ▼                                                               │
│  ┌──────────────┐                                                       │
│  │ 失败补偿      │  11. 重试3次 → 死信队列 → 人工补偿                       │
│  │ Service       │                                                       │
│  └──────────────┘                                                       │
└─────────────────────────────────────────────────────────────────────────┘
```

#### 6.1.2 Controller入口与参数校验

```java
/**
 * 动态发布Controller
 *
 * 职责：
 * 1. 参数校验（JSR303 + 业务校验）
 * 2. 幂等控制（防止用户重复提交）
 * 3. 限流（单用户发布频率限制）
 */
@RestController
@RequestMapping("/api/feed")
@Slf4j
public class FeedPublishController {

    @Autowired
    private FeedPublishService feedPublishService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 幂等锁过期时间 */
    private static final long IDEMPOTENT_LOCK_TTL = 10; // 秒

    /** 单用户发布限流：1分钟最多5条 */
    private static final int PUBLISH_RATE_LIMIT = 5;
    private static final long RATE_LIMIT_WINDOW = 60; // 秒

    /**
     * 发布动态
     */
    @PostMapping("/publish")
    public Result<Long> publish(@RequestBody @Valid PublishRequest request,
                                 @RequestHeader("userId") Long userId) {
        log.info("收到发布动态请求, userId={}, request={}", userId, request);

        // 1. 幂等控制：基于请求指纹防重复提交
        String requestFingerprint = DigestUtils.md5Hex(
            userId + ":" + request.getContent() + ":" + request.getImages());
        String idempotentKey = "feed:publish:lock:" + requestFingerprint;
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(idempotentKey, "1", IDEMPOTENT_LOCK_TTL, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(locked)) {
            log.warn("重复提交被拦截, userId={}, fingerprint={}", userId, requestFingerprint);
            return Result.fail("请勿重复提交，稍后再试");
        }

        // 2. 限流：滑动窗口限流
        String rateLimitKey = "feed:publish:rate:" + userId;
        Long count = redisTemplate.opsForValue().increment(rateLimitKey);
        if (count != null && count == 1) {
            redisTemplate.expire(rateLimitKey, RATE_LIMIT_WINDOW, TimeUnit.SECONDS);
        }
        if (count != null && count > PUBLISH_RATE_LIMIT) {
            log.warn("发布限流, userId={}, count={}", userId, count);
            return Result.fail("发布过于频繁，请稍后再试");
        }

        // 3. 业务校验
        PublishRequestValidator.validate(request);

        // 4. 调用发布服务
        Long feedId = feedPublishService.publish(request, userId);

        log.info("发布动态成功, userId={}, feedId={}", userId, feedId);
        return Result.success(feedId);
    }
}

/**
 * 发布请求DTO
 */
@Data
public class PublishRequest {

    @NotBlank(message = "内容不能为空（纯图片动态需附带描述）")
    @Size(max = 2000, message = "内容不能超过2000字")
    private String content;

    @NotNull(message = "动态类型不能为空")
    private Integer type; // 0图文 1视频 2纯文本

    /** 图片URL列表，最多9张 */
    private List<String> images;

    /** 视频URL */
    private String videoUrl;

    /** @用户ID列表 */
    private List<Long> mentionUserIds;

    /** 话题标签列表 */
    private List<String> topicTags;

    /** 地理位置 */
    private String location;

    /** 客户端生成的幂等ID */
    @NotBlank(message = "clientId不能为空")
    private String clientId;
}

/**
 * 发布请求业务校验器
 */
public class PublishRequestValidator {

    private static final int MAX_IMAGES = 9;
    private static final int MAX_MENTIONS = 10;
    private static final int MAX_TOPICS = 5;
    private static final List<String> ALLOWED_IMAGE_SUFFIX =
        Arrays.asList(".jpg", ".jpeg", ".png", ".webp", ".gif");

    public static void validate(PublishRequest request) {
        // 图文动态至少有1张图片
        if (request.getType() == 0) {
            if (CollectionUtils.isEmpty(request.getImages())) {
                throw new BizException("图文动态至少需要1张图片");
            }
        }

        // 图片数量校验
        if (!CollectionUtils.isEmpty(request.getImages())) {
            if (request.getImages().size() > MAX_IMAGES) {
                throw new BizException("图片最多" + MAX_IMAGES + "张");
            }
            // 图片URL格式校验
            for (String url : request.getImages()) {
                if (!isValidImageUrl(url)) {
                    throw new BizException("图片URL格式不合法: " + url);
                }
            }
        }

        // 视频动态必须有视频URL
        if (request.getType() == 1) {
            if (StringUtils.isBlank(request.getVideoUrl())) {
                throw new BizException("视频动态必须包含视频URL");
            }
        }

        // @用户数量校验
        if (!CollectionUtils.isEmpty(request.getMentionUserIds())) {
            if (request.getMentionUserIds().size() > MAX_MENTIONS) {
                throw new BizException("@用户最多" + MAX_MENTIONS + "个");
            }
        }

        // 话题标签数量校验
        if (!CollectionUtils.isEmpty(request.getTopicTags())) {
            if (request.getTopicTags().size() > MAX_TOPICS) {
                throw new BizException("话题标签最多" + MAX_TOPICS + "个");
            }
            for (String tag : request.getTopicTags()) {
                if (tag.length() > 20) {
                    throw new BizException("单个话题标签不能超过20字: " + tag);
                }
            }
        }
    }

    private static boolean isValidImageUrl(String url) {
        try {
            new URL(url);
            String lowerUrl = url.toLowerCase();
            return ALLOWED_IMAGE_SUFFIX.stream().anyMatch(lowerUrl::endsWith);
        } catch (MalformedURLException e) {
            return false;
        }
    }
}
```

#### 6.1.3 内容审核（敏感词 + 图片鉴黄）

```java
/**
 * 内容审核服务
 *
 * 审核策略：
 * 1. 文本：DFA算法实时过滤敏感词
 * 2. 图片/视频：异步AI审核，先发布后审核（先审后发会严重影响体验）
 * 3. 审核不通过：动态状态置为"审核拒绝"，用户侧不可见
 */
@Service
@Slf4j
public class ContentAuditService {

    @Autowired
    private DFAFilter dfaFilter;

    @Autowired
    private MqProducer mqProducer;

    @Autowired
    private FeedMapper feedMapper;

    /** 敏感词命中后的替换文本 */
    private static final String MASK_TEXT = "***";

    /**
     * 文本实时审核（同步）
     *
     * @return 审核通过返回清洗后的文本，不通过抛出异常
     */
    public String auditText(String content) {
        long start = System.currentTimeMillis();

        // DFA算法检测敏感词
        List<String> matchedWords = dfaFilter.match(content);

        if (!matchedWords.isEmpty()) {
            log.warn("敏感词命中, words={}", matchedWords);

            // 根据命中词的严重程度决定处理方式
            if (containsHighRiskWords(matchedWords)) {
                // 高危敏感词：直接拒绝
                throw new BizException(ErrorCode.CONTENT_REJECTED,
                    "内容包含违规信息，请修改后重新发布");
            }

            // 普通敏感词：替换后放行
            String masked = dfaFilter.replace(content, MASK_TEXT);
            log.info("敏感词已替换, original={}, masked={}", content, masked);
            return masked;
        }

        log.debug("文本审核通过, cost={}ms", System.currentTimeMillis() - start);
        return content;
    }

    /**
     * 图片/视频异步审核
     * 发送MQ消息，由审核服务消费调用AI鉴黄接口
     */
    public void auditMediaAsync(Long feedId, List<String> imageUrls, String videoUrl) {
        MediaAuditMessage msg = new MediaAuditMessage();
        msg.setFeedId(feedId);
        msg.setImageUrls(imageUrls);
        msg.setVideoUrl(videoUrl);
        msg.setTimestamp(System.currentTimeMillis());

        mqProducer.send("media-audit-topic", msg);
        log.info("媒体审核任务已提交, feedId={}", feedId);
    }

    /**
     * 审核结果回调处理
     */
    public void handleAuditResult(AuditResult result) {
        try {
            if (!result.isPass()) {
                // 审核不通过：将动态状态置为"审核拒绝"
                int rows = feedMapper.updateStatus(
                    result.getFeedId(), FeedStatus.AUDIT_REJECTED.getCode());
                log.warn("动态审核不通过, feedId={}, reason={}, updatedRows={}",
                    result.getFeedId(), result.getReason(), rows);

                // TODO: 推送通知给用户"你的动态因违反社区规则已被下架"
            }
        } catch (Exception e) {
            log.error("审核结果处理异常, feedId={}", result.getFeedId(), e);
            // 审核结果处理失败不影响主流程，记录日志即可
        }
    }

    private boolean containsHighRiskWords(List<String> words) {
        // 涉政、涉恐等高危词库判断
        return words.stream().anyMatch(HighRiskWordSet::contains);
    }
}

/**
 * DFA敏感词过滤器
 *
 * 基于DFA（确定有限自动机）算法实现高效的多模式匹配
 * 时间复杂度：O(n * m)，n=文本长度，m=敏感词平均长度
 */
@Component
public class DFAFilter {

    /** DFA字典树根节点 */
    private final Map<String, Object> wordTree = new HashMap<>();

    /** 敏感词结束标记 */
    private static final String IS_END = "isEnd";

    /** 初始化敏感词库 */
    @PostConstruct
    public void init() {
        List<String> sensitiveWords = loadSensitiveWords();
        for (String word : sensitiveWords) {
            addWordToTree(word);
        }
        log.info("DFA敏感词库加载完成, size={}", sensitiveWords.size());
    }

    /**
     * 匹配敏感词
     */
    @SuppressWarnings("unchecked")
    public List<String> match(String text) {
        List<String> result = new ArrayList<>();
        int n = text.length();

        for (int i = 0; i < n; i++) {
            Map<String, Object> current = wordTree;
            int j = i;
            StringBuilder matched = new StringBuilder();

            while (j < n) {
                String ch = String.valueOf(text.charAt(j));
                if (!current.containsKey(ch)) {
                    break;
                }
                matched.append(ch);
                current = (Map<String, Object>) current.get(ch);
                if ("1".equals(current.get(IS_END))) {
                    result.add(matched.toString());
                }
                j++;
            }
        }
        return result;
    }

    /**
     * 替换敏感词
     */
    @SuppressWarnings("unchecked")
    public String replace(String text, String replacement) {
        StringBuilder result = new StringBuilder(text);
        int n = text.length();

        for (int i = 0; i < n; i++) {
            Map<String, Object> current = wordTree;
            int j = i;
            int end = -1;

            while (j < n) {
                String ch = String.valueOf(text.charAt(j));
                if (!current.containsKey(ch)) {
                    break;
                }
                current = (Map<String, Object>) current.get(ch);
                if ("1".equals(current.get(IS_END))) {
                    end = j + 1;
                }
                j++;
            }

            if (end > 0) {
                for (int k = i; k < end; k++) {
                    result.setCharAt(k, replacement.charAt(0));
                }
                i = end - 1; // 跳过已替换部分
            }
        }
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    private void addWordToTree(String word) {
        Map<String, Object> current = wordTree;
        for (int i = 0; i < word.length(); i++) {
            String ch = String.valueOf(word.charAt(i));
            if (!current.containsKey(ch)) {
                Map<String, Object> node = new HashMap<>();
                node.put(IS_END, "0");
                current.put(ch, node);
            }
            current = (Map<String, Object>) current.get(ch);
            if (i == word.length() - 1) {
                current.put(IS_END, "1");
            }
        }
    }

    private List<String> loadSensitiveWords() {
        // 从配置中心/数据库加载敏感词库
        // 实际项目中通常通过Apollo/Nacos动态更新
        return sensitiveWordRepository.findAll();
    }
}
```

#### 6.1.4 动态入库与写扩散核心逻辑

```java
/**
 * 动态发布服务（增强版）
 *
 * 完整写链路：
 * 1. 内容审核（文本同步审核）
 * 2. 动态入库（本地事务保证一致性）
 * 3. 写发件箱
 * 4. 媒体异步审核
 * 5. 判断大V/普通用户，决定推/拉策略
 * 6. 普通用户：MQ异步推送到粉丝收件箱
 * 7. 大V：仅写发件箱，粉丝读时拉取
 */
@Service
@Slf4j
public class FeedPublishService {

    @Autowired
    private FeedMapper feedMapper;

    @Autowired
    private OutboxMapper outboxMapper;

    @Autowired
    private FollowFeignClient followClient;

    @Autowired
    private ContentAuditService contentAuditService;

    @Autowired
    private RocketMQTemplate mqTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private CompensationMapper compensationMapper;

    /** 大V粉丝数阈值 */
    private static final int BIG_V_THRESHOLD = 1000;

    /** 写扩散每批推送数量 */
    private static final int PUSH_BATCH_SIZE = 500;

    /** 收件箱最大容量 */
    private static final int MAX_INBOX_SIZE = 1000;

    /**
     * 发布动态（完整链路）
     */
    public Long publish(PublishRequest request, Long userId) {
        long startTime = System.currentTimeMillis();

        try {
            // 1. 文本内容实时审核
            String auditedContent = contentAuditService.auditText(request.getContent());

            // 2. 生成FeedId（雪花算法，保证全局唯一且趋势递增）
            long feedId = SnowflakeIdGenerator.nextId();
            long timestamp = System.currentTimeMillis();

            // 3. 动态入库 + 写发件箱（本地事务）
            Feed feed = buildFeed(feedId, userId, auditedContent, request, timestamp);
            Outbox outbox = buildOutbox(userId, feedId, timestamp);

            // 使用编程式事务保证原子性
            transactionTemplate.execute(status -> {
                feedMapper.insert(feed);
                outboxMapper.insert(outbox);
                return null;
            });

            log.info("动态入库成功, feedId={}, userId={}, cost={}ms",
                feedId, userId, System.currentTimeMillis() - startTime);

            // 4. 媒体异步审核（图片鉴黄、视频审核）
            if (request.getType() != 2) { // 非纯文本
                contentAuditService.auditMediaAsync(feedId, request.getImages(), request.getVideoUrl());
            }

            // 5. 处理@用户和话题标签（异步）
            if (!CollectionUtils.isEmpty(request.getMentionUserIds())
                || !CollectionUtils.isEmpty(request.getTopicTags())) {
                publishMentionAndTopicEvent(feedId, userId, request);
            }

            // 6. 判断推/拉策略
            int followerCount = followClient.getFollowerCount(userId);

            if (followerCount < BIG_V_THRESHOLD) {
                // 普通用户：推模式（MQ异步推送）
                triggerFanoutPush(feedId, userId, timestamp, followerCount);
            } else {
                // 大V：拉模式，只写发件箱
                log.info("大V动态走拉模式, userId={}, feedId={}, followerCount={}",
                    userId, feedId, followerCount);
                // 更新大V标记缓存，方便读链路快速判断
                redisTemplate.opsForSet().add("bigv:set", String.valueOf(userId));
            }

            return feedId;

        } catch (BizException e) {
            log.warn("发布动态业务异常, userId={}, error={}", userId, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("发布动态系统异常, userId={}", userId, e);
            throw new SystemException("发布失败，请稍后重试");
        }
    }

    /**
     * 触发写扩散推送（MQ异步）
     */
    private void triggerFanoutPush(Long feedId, Long publisherId,
                                    long timestamp, int followerCount) {
        PushMessage msg = new PushMessage();
        msg.setFeedId(feedId);
        msg.setPublisherId(publisherId);
        msg.setTimestamp(timestamp);
        msg.setFollowerCount(followerCount);
        // 消息Tag用于消费端路由
        msg.setMsgId(UUID.randomUUID().toString());

        mqTemplate.asyncSend("feed-push-topic:fanout", msg, new SendCallback() {
            @Override
            public void onSuccess(SendResult sendResult) {
                log.info("写扩散MQ发送成功, feedId={}, publisherId={}",
                    feedId, publisherId);
            }

            @Override
            public void onException(Throwable e) {
                log.error("写扩散MQ发送失败, feedId={}, publisherId={}",
                    feedId, publisherId, e);
                // MQ发送失败：降级方案——存入补偿表，定时任务重试
                saveToCompensationTable(feedId, publisherId, timestamp);
            }
        });
    }

    private Feed buildFeed(Long feedId, Long userId, String content,
                           PublishRequest request, long timestamp) {
        Feed feed = new Feed();
        feed.setFeedId(feedId);
        feed.setUserId(userId);
        feed.setContent(content);
        feed.setType(request.getType());
        feed.setImages(JSON.toJSONString(request.getImages()));
        feed.setVideoUrl(request.getVideoUrl());
        feed.setMentionUserIds(JSON.toJSONString(request.getMentionUserIds()));
        feed.setTopicTags(JSON.toJSONString(request.getTopicTags()));
        feed.setLocation(request.getLocation());
        feed.setStatus(1); // 正常状态
        feed.setAuditStatus(0); // 审核中
        feed.setCreateTime(new Date(timestamp));
        return feed;
    }

    private Outbox buildOutbox(Long userId, Long feedId, long timestamp) {
        Outbox outbox = new Outbox();
        outbox.setUserId(userId);
        outbox.setFeedId(feedId);
        outbox.setCreateTime(new Date(timestamp));
        return outbox;
    }

    private void publishMentionAndTopicEvent(Long feedId, Long userId, PublishRequest request) {
        MentionTopicEvent event = new MentionTopicEvent();
        event.setFeedId(feedId);
        event.setUserId(userId);
        event.setMentionUserIds(request.getMentionUserIds());
        event.setTopicTags(request.getTopicTags());
        mqTemplate.asyncSend("feed-mention-topic", event, null);
    }

    private void saveToCompensationTable(Long feedId, Long publisherId, long timestamp) {
        // 降级：写入补偿表，由定时任务扫描重试
        CompensationRecord record = new CompensationRecord();
        record.setBizType("FEED_PUSH");
        record.setBizKey(String.valueOf(feedId));
        record.setPayload(JSON.toJSONString(
            new PushMessage(feedId, publisherId, timestamp)));
        record.setStatus(0); // 待重试
        record.setRetryCount(0);
        compensationMapper.insert(record);
    }
}
```

#### 6.1.5 写扩散消费者：分批推送与失败重试

```java
/**
 * 写扩散消费者（增强版）
 *
 * 职责：
 * 1. 分批获取粉丝列表（避免一次性加载百万粉丝导致OOM）
 * 2. Redis Pipeline批量写入收件箱
 * 3. 幂等控制（消息重复消费时不会重复写入）
 * 4. 失败重试 + 死信补偿
 */
@Component
@RocketMQMessageListener(
    topic = "feed-push-topic",
    consumerGroup = "feed-push-consumer-group",
    consumeMode = ConsumeMode.CONCURRENTLY,
    maxReconsumeTimes = 3 // 最多重试3次
)
@Slf4j
public class FeedPushConsumer implements RocketMQListener<PushMessage> {

    @Autowired
    private FollowFeignClient followClient;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CompensationMapper compensationMapper;

    private static final int FOLLOWER_BATCH_SIZE = 500;
    private static final int MAX_INBOX_SIZE = 1000;
    private static final long INBOX_TTL_SECONDS = 7 * 24 * 3600L;

    @Override
    public void onMessage(PushMessage msg) {
        long startTime = System.currentTimeMillis();
        Long feedId = msg.getFeedId();
        Long publisherId = msg.getPublisherId();

        try {
            log.info("开始写扩散, feedId={}, publisherId={}, msgId={}",
                feedId, publisherId, msg.getMsgId());

            // 1. 幂等检查：检查是否已处理过此消息
            String idempotentKey = "feed:push:done:" + feedId + ":" + msg.getMsgId();
            Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(idempotentKey, "1", 24, TimeUnit.HOURS);
            if (Boolean.FALSE.equals(isNew)) {
                log.warn("写扩散消息重复消费, 跳过, feedId={}, msgId={}", feedId, msg.getMsgId());
                return;
            }

            // 2. 分批获取粉丝列表并推送
            int totalCount = 0;
            int lastFollowerId = 0;

            while (true) {
                // 分页获取粉丝（游标分页，避免深度分页问题）
                List<Long> batchFollowerIds = followClient.getFollowerIdsByPage(
                    publisherId, lastFollowerId, FOLLOWER_BATCH_SIZE);

                if (CollectionUtils.isEmpty(batchFollowerIds)) {
                    break;
                }

                // 批量写入收件箱
                batchPushToInbox(batchFollowerIds, feedId, msg.getTimestamp());
                totalCount += batchFollowerIds.size();

                // 更新游标
                lastFollowerId = batchFollowerIds.get(batchFollowerIds.size() - 1);

                // 如果这批数据不足一页，说明已经到底了
                if (batchFollowerIds.size() < FOLLOWER_BATCH_SIZE) {
                    break;
                }
            }

            log.info("写扩散完成, feedId={}, publisherId={}, totalFollowers={}, cost={}ms",
                feedId, publisherId, totalCount, System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            log.error("写扩散异常, feedId={}, publisherId={}, msgId={}",
                feedId, publisherId, msg.getMsgId(), e);

            // 清除幂等标记，允许重试
            redisTemplate.delete("feed:push:done:" + feedId + ":" + msg.getMsgId());

            // 抛出异常触发RocketMQ重试机制
            throw new RuntimeException("写扩散失败", e);
        }
    }

    /**
     * 批量写入粉丝收件箱（Redis Pipeline）
     */
    private void batchPushToInbox(List<Long> followerIds, Long feedId, long timestamp) {
        String feedIdStr = String.valueOf(feedId);

        // 使用Pipeline减少网络往返
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Long followerId : followerIds) {
                byte[] key = ("inbox:" + followerId).getBytes();
                byte[] member = feedIdStr.getBytes();

                // 1. ZADD：添加Feed到收件箱
                connection.zAdd(key, timestamp, member);

                // 2. ZREMRANGEBYRANK：超限删除最旧的
                connection.zRemRangeByRank(key, 0, -(MAX_INBOX_SIZE + 1));

                // 3. EXPIRE：刷新TTL
                connection.expire(key, INBOX_TTL_SECONDS);
            }
            return null;
        });

        log.debug("批量推送完成, feedId={}, batchSize={}", feedId, followerIds.size());
    }
}

/**
 * 死信队列消费者：写扩散最终兜底
 */
@Component
@RocketMQMessageListener(
    topic = "%DLQ%feed-push-consumer-group",
    consumerGroup = "feed-push-dlq-consumer-group"
)
@Slf4j
public class FeedPushDLQConsumer implements RocketMQListener<MessageExt> {

    @Autowired
    private CompensationMapper compensationMapper;

    @Override
    public void onMessage(MessageExt messageExt) {
        String body = new String(messageExt.getBody(), StandardCharsets.UTF_8);
        PushMessage msg = JSON.parseObject(body, PushMessage.class);

        log.error("写扩散进入死信队列, feedId={}, publisherId={}",
            msg.getFeedId(), msg.getPublisherId());

        // 存入补偿表，等待人工介入或定时任务补偿
        CompensationRecord record = new CompensationRecord();
        record.setBizType("FEED_PUSH_DLQ");
        record.setBizKey(String.valueOf(msg.getFeedId()));
        record.setPayload(body);
        record.setStatus(0);
        record.setRetryCount(0);
        record.setCreateTime(new Date());
        compensationMapper.insert(record);

        // 发送告警
        AlertManager.send("写扩散最终失败，需人工处理", "feedId=" + msg.getFeedId());
    }
}
```

---

### 案例二：用户刷Feed流的读取全链路

#### 6.2.1 读取全链路流程图

```
┌──────────────────────────────────────────────────────────────────────────┐
│                      Feed流读取全链路                                       │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  用户打开App                                                              │
│    │                                                                     │
│    ▼                                                                     │
│  ┌───────────────┐                                                       │
│  │ FeedQuery      │  1. 判断请求类型（关注页/推荐页）                        │
│  │ Controller     │  2. 解析游标参数                                       │
│  └───────┬───────┘                                                       │
│          ▼                                                               │
│  ┌───────────────┐                                                       │
│  │ FeedQuery      │  3. 读收件箱 Redis ZREVRANGE（普通用户推送的动态）        │
│  │ Service        │  4. 读关注大V发件箱（拉模式实时拉取）                     │
│  │                │  5. 合并 + 去重 + 按时间排序                             │
│  └───────┬───────┘                                                       │
│          ▼                                                               │
│  ┌───────────────┐                                                       │
│  │ FeedDetail     │  6. 批量查询动态详情                                    │
│  │ Service        │     L1 Caffeine本地缓存 → L2 Redis → L3 MySQL           │
│  └───────┬───────┘                                                       │
│          ▼                                                               │
│  ┌───────────────┐                                                       │
│  │ FeedFilter     │  7. 过滤已删除动态                                      │
│  │ Service        │  8. 过滤已取关用户的内容                                 │
│  │                │  9. 过滤违规审核下架的动态                                │
│  └───────┬───────┘                                                       │
│          ▼                                                               │
│  ┌───────────────┐                                                       │
│  │ FeedEnrich     │  10. 批量聚合用户信息（Caffeine + Redis）               │
│  │ Service        │  11. 批量聚合计数（点赞/评论/转发）                       │
│  └───────┬───────┘                                                       │
│          ▼                                                               │
│  ┌───────────────┐                                                       │
│  │ ReadStatus     │  12. 异步标记已读                                       │
│  │ Service        │  13. 返回游标（下一页用）                                 │
│  └───────────────┘                                                       │
└──────────────────────────────────────────────────────────────────────────┘
```

#### 6.2.2 Controller入口与游标解析

```java
/**
 * Feed流查询Controller
 */
@RestController
@RequestMapping("/api/feed")
@Slf4j
public class FeedQueryController {

    @Autowired
    private FeedQueryService feedQueryService;

    /**
     * 获取关注页Feed流
     *
     * @param cursor 游标（Base64编码，包含lastFeedId和lastScore）
     * @param pageSize 每页数量，默认20
     */
    @GetMapping("/timeline")
    public Result<FeedListVO> getTimeline(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestHeader("userId") Long userId) {

        // 游标参数校验
        pageSize = Math.min(Math.max(pageSize, 1), 50); // 限制1~50

        CursorInfo cursorInfo = parseCursor(cursor);

        FeedListVO result = feedQueryService.getTimeline(userId, cursorInfo, pageSize);

        return Result.success(result);
    }

    /**
     * 解析游标
     * 游标格式：Base64({"lastFeedId": 123, "lastScore": 1700000000000})
     */
    private CursorInfo parseCursor(String cursor) {
        if (StringUtils.isBlank(cursor)) {
            return CursorInfo.firstPage();
        }
        try {
            String json = new String(Base64.getDecoder().decode(cursor),
                StandardCharsets.UTF_8);
            return JSON.parseObject(json, CursorInfo.class);
        } catch (Exception e) {
            log.warn("游标解析失败, cursor={}, 降级为首页", cursor);
            return CursorInfo.firstPage();
        }
    }
}

/**
 * 游标信息
 */
@Data
public class CursorInfo {
    private Long lastFeedId;
    private Double lastScore;

    public static CursorInfo firstPage() {
        CursorInfo info = new CursorInfo();
        info.lastFeedId = null;
        info.lastScore = null;
        return info;
    }

    public boolean isFirstPage() {
        return lastFeedId == null;
    }
}
```

#### 6.2.3 收件箱读取与读扩散合并

```java
/**
 * Feed流读取服务（增强版）
 *
 * 完整读链路：
 * 1. 读取Redis收件箱（推模式推送的普通用户动态）
 * 2. 读取关注大V的发件箱（拉模式实时拉取）
 * 3. 合并去重排序
 * 4. 多级缓存查询动态详情
 * 5. 过滤已删除/已取关/违规内容
 * 6. 聚合用户信息和计数
 * 7. 异步标记已读
 */
@Service
@Slf4j
public class FeedQueryService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private OutboxMapper outboxMapper;

    @Autowired
    private FeedDetailService feedDetailService;

    @Autowired
    private FeedFilterService feedFilterService;

    @Autowired
    private FeedEnrichService feedEnrichService;

    @Autowired
    private FollowFeignClient followClient;

    @Autowired
    private ReadStatusService readStatusService;

    /** 读扩散时拉取大V动态的时间窗口 */
    private static final long BIGV_PULL_WINDOW_MS = 7 * 24 * 3600 * 1000L;

    /**
     * 获取Feed流（关注页）
     */
    public FeedListVO getTimeline(Long userId, CursorInfo cursor, int pageSize) {
        long startTime = System.currentTimeMillis();

        try {
            // 1. 读取收件箱（推模式推送的动态）
            List<FeedItem> inboxItems = readInbox(userId, cursor, pageSize);

            // 2. 读取关注大V的发件箱（拉模式）
            List<FeedItem> bigVItems = readBigVOutbox(userId, cursor, pageSize);

            // 3. 合并 + 去重 + 按时间倒序排序
            List<FeedItem> mergedItems = mergeAndSort(inboxItems, bigVItems);

            // 4. 多取一些以补偿过滤后的数量损失（过滤可能减少条数）
            int fetchSize = pageSize + 10;
            List<FeedItem> pageItems = mergedItems.stream()
                .limit(fetchSize)
                .collect(Collectors.toList());

            // 5. 批量查询动态详情（多级缓存）
            List<Feed> feeds = feedDetailService.batchGetFeed(
                pageItems.stream().map(FeedItem::getFeedId).collect(Collectors.toList()));

            // 6. 过滤已删除/已取关/违规内容
            List<Feed> filteredFeeds = feedFilterService.filter(userId, feeds);

            // 7. 截取目标页大小
            boolean hasMore = filteredFeeds.size() > pageSize;
            List<Feed> pageFeeds = filteredFeeds.stream()
                .limit(pageSize)
                .collect(Collectors.toList());

            // 8. 聚合用户信息和计数
            List<FeedVO> voList = feedEnrichService.enrichBatch(pageFeeds);

            // 9. 构建返回结果
            FeedListVO result = new FeedListVO();
            result.setFeedList(voList);
            result.setHasMore(hasMore);

            // 10. 生成下一页游标
            if (!pageFeeds.isEmpty()) {
                Feed lastFeed = pageFeeds.get(pageFeeds.size() - 1);
                CursorInfo nextCursor = new CursorInfo();
                nextCursor.setLastFeedId(lastFeed.getFeedId());
                nextCursor.setLastScore((double) lastFeed.getCreateTime().getTime());
                result.setCursor(encodeCursor(nextCursor));
            }

            // 11. 异步标记已读
            readStatusService.asyncMarkRead(userId,
                pageFeeds.stream().map(Feed::getFeedId).collect(Collectors.toList()));

            log.info("Feed流读取完成, userId={}, resultSize={}, cost={}ms",
                userId, voList.size(), System.currentTimeMillis() - startTime);

            return result;

        } catch (Exception e) {
            log.error("Feed流读取异常, userId={}", userId, e);
            // 降级：返回空列表，不抛异常影响用户体验
            FeedListVO fallback = new FeedListVO();
            fallback.setFeedList(Collections.emptyList());
            fallback.setHasMore(false);
            return fallback;
        }
    }

    /**
     * 读取收件箱（Redis ZSet分页）
     */
    private List<FeedItem> readInbox(Long userId, CursorInfo cursor, int pageSize) {
        String inboxKey = "inbox:" + userId;
        List<FeedItem> items = new ArrayList<>();

        Set<ZSetOperations.TypedTuple<String>> tuples;

        if (cursor.isFirstPage()) {
            // 首页：获取最新的pageSize条
            tuples = redisTemplate.opsForZSet()
                .reverseRangeWithScores(inboxKey, 0, pageSize);
        } else {
            // 翻页：获取游标之前的pageSize条
            double maxScore = cursor.getLastScore() - 1;
            tuples = redisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(inboxKey, 0, maxScore, 0, pageSize);
        }

        if (tuples != null) {
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                FeedItem item = new FeedItem();
                item.setFeedId(Long.parseLong(tuple.getValue()));
                item.setTimestamp(tuple.getScore().longValue());
                items.add(item);
            }
        }

        log.debug("读取收件箱, userId={}, size={}", userId, items.size());
        return items;
    }

    /**
     * 读取关注大V的发件箱（读扩散拉取）
     */
    private List<FeedItem> readBigVOutbox(Long userId, CursorInfo cursor, int pageSize) {
        // 获取关注的大V列表
        List<Long> bigVIds = followClient.getFollowingBigVs(userId);
        if (CollectionUtils.isEmpty(bigVIds)) {
            return Collections.emptyList();
        }

        // 确定查询时间范围
        long maxTimestamp = cursor.isFirstPage()
            ? System.currentTimeMillis()
            : cursor.getLastScore().longValue();
        long minTimestamp = maxTimestamp - BIGV_PULL_WINDOW_MS;

        List<FeedItem> allItems = new ArrayList<>();

        // 批量查询大V发件箱
        for (Long bigVId : bigVIds) {
            try {
                List<Outbox> outboxItems = outboxMapper.selectByTimeRange(
                    bigVId, new Date(minTimestamp), new Date(maxTimestamp), pageSize);

                for (Outbox outbox : outboxItems) {
                    FeedItem item = new FeedItem();
                    item.setFeedId(outbox.getFeedId());
                    item.setPublisherId(bigVId);
                    item.setTimestamp(outbox.getCreateTime().getTime());
                    allItems.add(item);
                }
            } catch (Exception e) {
                log.warn("查询大V发件箱失败, bigVId={}", bigVId, e);
                // 单个大V查询失败不影响整体
            }
        }

        log.debug("读取大V发件箱, userId={}, bigVCount={}, totalItems={}",
            userId, bigVIds.size(), allItems.size());
        return allItems;
    }

    /**
     * 合并 + 去重 + 排序
     */
    private List<FeedItem> mergeAndSort(List<FeedItem> inboxItems, List<FeedItem> bigVItems) {
        // 使用LinkedHashMap去重（同一条Feed可能既在收件箱又在发件箱）
        Map<Long, FeedItem> merged = new LinkedHashMap<>();
        for (FeedItem item : inboxItems) {
            merged.putIfAbsent(item.getFeedId(), item);
        }
        for (FeedItem item : bigVItems) {
            merged.putIfAbsent(item.getFeedId(), item);
        }

        // 按时间倒序排序
        return merged.values().stream()
            .sorted((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()))
            .collect(Collectors.toList());
    }

    private String encodeCursor(CursorInfo cursor) {
        String json = JSON.toJSONString(cursor);
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}

/**
 * Feed条目内部表示
 */
@Data
public class FeedItem {
    private Long feedId;
    private Long publisherId;
    private long timestamp;
}
```

#### 6.2.4 动态详情多级缓存查询

```java
/**
 * 动态详情服务（多级缓存）
 *
 * 缓存层级：
 * L1: Caffeine本地缓存（5分钟，减少网络调用）
 * L2: Redis分布式缓存（30分钟）
 * L3: MySQL数据库（持久化）
 *
 * 缓存策略：
 * - 读：L1 → L2 → L3，逐级回源
 * - 写：发布时只写DB，读取时按需回源
 * - 失效：动态删除/审核下架时主动删除L1+L2
 */
@Service
@Slf4j
public class FeedDetailService {

    /** L1: 本地缓存 */
    private final Cache<Long, Feed> l1Cache = Caffeine.newBuilder()
        .maximumSize(10000)
        .expireAfterWrite(5, TimeUnit.MINUTES)
        .recordStats()
        .build();

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private FeedMapper feedMapper;

    private static final long L2_TTL = 30 * 60; // 30分钟
    private static final String CACHE_PREFIX = "feed:detail:";

    /**
     * 批量获取动态详情
     */
    public List<Feed> batchGetFeed(List<Long> feedIds) {
        if (CollectionUtils.isEmpty(feedIds)) {
            return Collections.emptyList();
        }

        List<Feed> result = new ArrayList<>(feedIds.size());
        List<Long> missedIds = new ArrayList<>();

        // 1. L1本地缓存查询
        for (Long feedId : feedIds) {
            Feed cached = l1Cache.getIfPresent(feedId);
            if (cached != null) {
                result.add(cached);
            } else {
                missedIds.add(feedId);
            }
        }

        if (missedIds.isEmpty()) {
            return result;
        }

        // 2. L2 Redis批量查询（MGET）
        List<String> keys = missedIds.stream()
            .map(id -> CACHE_PREFIX + id)
            .collect(Collectors.toList());
        List<String> redisValues = redisTemplate.opsForValue().multiGet(keys);

        List<Long> dbIds = new ArrayList<>();
        for (int i = 0; i < missedIds.size(); i++) {
            String json = redisValues.get(i);
            if (json != null) {
                Feed feed = JSON.parseObject(json, Feed.class);
                result.add(feed);
                l1Cache.put(feed.getFeedId(), feed); // 回填L1
            } else {
                dbIds.add(missedIds.get(i));
            }
        }

        if (dbIds.isEmpty()) {
            return result;
        }

        // 3. L3 MySQL批量查询
        List<Feed> dbFeeds = feedMapper.batchSelectByFeedIds(dbIds);

        // 回填L2和L1
        for (Feed feed : dbFeeds) {
            result.add(feed);
            l1Cache.put(feed.getFeedId(), feed);
            redisTemplate.opsForValue().set(
                CACHE_PREFIX + feed.getFeedId(),
                JSON.toJSONString(feed),
                L2_TTL, TimeUnit.SECONDS);
        }

        return result;
    }

    /**
     * 缓存失效（动态删除/审核下架时调用）
     */
    public void invalidateCache(Long feedId) {
        l1Cache.invalidate(feedId);
        redisTemplate.delete(CACHE_PREFIX + feedId);
        log.info("动态缓存已失效, feedId={}", feedId);
    }
}
```

#### 6.2.5 过滤逻辑与已读标记

```java
/**
 * Feed过滤服务
 *
 * 过滤规则：
 * 1. 已删除的动态（status=0）
 * 2. 已取消关注的用户发的动态
 * 3. 审核未通过的动态（auditStatus!=1）
 * 4. 用户主动屏蔽的动态
 * 5. 用户拉黑的用户发的动态
 */
@Service
@Slf4j
public class FeedFilterService {

    @Autowired
    private FollowFeignClient followClient;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 屏蔽列表缓存Key前缀 */
    private static final String BLOCKED_FEED_PREFIX = "user:blocked:feed:";
    private static final String BLOCKED_USER_PREFIX = "user:blocked:user:";

    /**
     * 过滤Feed列表
     */
    public List<Feed> filter(Long userId, List<Feed> feeds) {
        if (CollectionUtils.isEmpty(feeds)) {
            return Collections.emptyList();
        }

        // 1. 获取用户关注集合（用于过滤已取关的）
        Set<Long> followingIds = followClient.getFollowingSet(userId);

        // 2. 获取用户拉黑列表
        Set<String> blockedUserKeys = redisTemplate.opsForSet()
            .members(BLOCKED_USER_PREFIX + userId);
        Set<Long> blockedUserIds = blockedUserKeys != null
            ? blockedUserKeys.stream().map(Long::parseLong).collect(Collectors.toSet())
            : Collections.emptySet();

        // 3. 获取用户主动屏蔽的动态ID
        Set<String> blockedFeedKeys = redisTemplate.opsForSet()
            .members(BLOCKED_FEED_PREFIX + userId);
        Set<Long> blockedFeedIds = blockedFeedKeys != null
            ? blockedFeedKeys.stream().map(Long::parseLong).collect(Collectors.toSet())
            : Collections.emptySet();

        // 4. 执行过滤
        List<Feed> filtered = feeds.stream()
            .filter(feed -> feed.getStatus() == 1)  // 过滤已删除
            .filter(feed -> feed.getAuditStatus() != 2)  // 过滤审核拒绝的
            .filter(feed -> followingIds.contains(feed.getUserId()))  // 过滤已取关的
            .filter(feed -> !blockedUserIds.contains(feed.getUserId()))  // 过滤拉黑用户
            .filter(feed -> !blockedFeedIds.contains(feed.getFeedId()))  // 过滤屏蔽动态
            .collect(Collectors.toList());

        log.debug("Feed过滤完成, userId={}, before={}, after={}",
            userId, feeds.size(), filtered.size());
        return filtered;
    }
}

/**
 * 已读状态服务
 *
 * 策略：
 * 1. 已读标记异步写入（不影响主链路性能）
 * 2. 使用Redis Bitmap存储已读状态，节省内存
 * 3. Bit位 = feedId % 100000000，每个用户一个Bitmap
 */
@Service
@Slf4j
public class ReadStatusService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RocketMQTemplate mqTemplate;

    private static final String READ_BITMAP_PREFIX = "feed:read:";
    private static final long BITMAP_MOD = 100000000L;

    /**
     * 异步标记已读
     */
    public void asyncMarkRead(Long userId, List<Long> feedIds) {
        if (CollectionUtils.isEmpty(feedIds)) {
            return;
        }

        ReadMarkMessage msg = new ReadMarkMessage();
        msg.setUserId(userId);
        msg.setFeedIds(feedIds);
        msg.setTimestamp(System.currentTimeMillis());

        mqTemplate.asyncSend("feed-read-topic", msg, new SendCallback() {
            @Override
            public void onSuccess(SendResult result) {
                log.debug("已读标记MQ发送成功, userId={}, feedCount={}",
                    userId, feedIds.size());
            }

            @Override
            public void onException(Throwable e) {
                log.warn("已读标记MQ发送失败, 降级为同步标记, userId={}", userId);
                // 降级：同步标记
                markReadSync(userId, feedIds);
            }
        });
    }

    /**
     * 同步标记已读（降级方案）
     */
    public void markReadSync(Long userId, List<Long> feedIds) {
        String bitmapKey = READ_BITMAP_PREFIX + userId;
        for (Long feedId : feedIds) {
            long offset = feedId % BITMAP_MOD;
            redisTemplate.opsForValue().setBit(bitmapKey, offset, true);
        }
        log.debug("已读标记完成, userId={}, feedCount={}", userId, feedIds.size());
    }

    /**
     * 检查某条Feed是否已读
     */
    public boolean isRead(Long userId, Long feedId) {
        String bitmapKey = READ_BITMAP_PREFIX + userId;
        long offset = feedId % BITMAP_MOD;
        Boolean bit = redisTemplate.opsForValue().getBit(bitmapKey, offset);
        return Boolean.TRUE.equals(bit);
    }
}
```

---

### 案例三：用户关注/取关后Feed流更新全链路

#### 6.3.1 关注/取关流程图

```
┌──────────────────────────────────────────────────────────────────────────┐
│                   关注/取关 Feed流更新全链路                                  │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌─────────────── 关注流程 ───────────────┐                               │
│  │                                       │                               │
│  │  用户A关注用户B                         │                               │
│  │    │                                  │                               │
│  │    ▼                                  │                               │
│  │  FollowService.follow()               │                               │
│  │    1. 写入关注关系表                    │                               │
│  │    2. 更新关注数/粉丝数缓存             │                               │
│  │    3. MQ异步：拉取B最近N条动态           │                               │
│  │    │                                  │                               │
│  │    ▼                                  │                               │
│  │  FollowEventConsumer                  │                               │
│  │    4. 查询B的发件箱（最近100条）          │                               │
│  │    5. 批量写入A的收件箱（Redis ZSet）    │                               │
│  │    6. 幂等控制（防重复关注导致重复写入）    │                               │
│  │                                       │                               │
│  └───────────────────────────────────────┘                               │
│                                                                          │
│  ┌─────────────── 取关流程 ───────────────┐                               │
│  │                                       │                               │
│  │  用户A取关用户B                         │                               │
│  │    │                                  │                               │
│  │    ▼                                  │                               │
│  │  FollowService.unfollow()             │                               │
│  │    1. 删除关注关系表                    │                               │
│  │    2. 更新关注数/粉丝数缓存             │                               │
│  │    3. 策略选择：                        │                               │
│  │       a. 标记取关（读取时过滤，推荐）     │                               │
│  │       b. 异步清理收件箱中B的动态          │                               │
│  │    │                                  │                               │
│  │    ▼                                  │                               │
│  │  FollowEventConsumer                  │                               │
│  │    4. 查询B最近发的动态ID                │                               │
│  │    5. 检查这些动态在收件箱中是否存在       │                               │
│  │    6. 批量ZREM删除                     │                               │
│  │    7. 动态过多时分批清理 + 延迟消息       │                               │
│  │                                       │                               │
│  └───────────────────────────────────────┘                               │
└──────────────────────────────────────────────────────────────────────────┘
```

#### 6.3.2 关注服务与动态回填

```java
/**
 * 关注/取关服务
 *
 * 关注流程：
 * 1. 写入关注关系
 * 2. 更新计数缓存
 * 3. 异步拉取被关注人最近动态写入收件箱
 *
 * 取关流程：
 * 1. 删除关注关系
 * 2. 更新计数缓存
 * 3. 异步清理收件箱中对方的动态（或读取时过滤）
 */
@Service
@Slf4j
public class FollowService {

    @Autowired
    private FollowMapper followMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RocketMQTemplate mqTemplate;

    @Autowired
    private OutboxMapper outboxMapper;

    /** 关注后回填动态数量 */
    private static final int BACKFILL_FEED_COUNT = 100;

    /** 收件箱最大容量 */
    private static final int MAX_INBOX_SIZE = 1000;

    /**
     * 关注某人
     */
    public void follow(Long userId, Long targetUserId) {
        if (userId.equals(targetUserId)) {
            throw new BizException("不能关注自己");
        }

        log.info("用户关注, userId={}, targetUserId={}", userId, targetUserId);

        try {
            // 1. 写入关注关系（幂等：已存在则跳过）
            FollowRelation relation = new FollowRelation();
            relation.setUserId(userId);
            relation.setTargetUserId(targetUserId);
            relation.setCreateTime(new Date());

            int inserted = followMapper.insertIfNotExists(relation);
            if (inserted == 0) {
                log.info("关注关系已存在, 跳过, userId={}, targetUserId={}",
                    userId, targetUserId);
                return;
            }

            // 2. 更新计数缓存（关注数 +1，粉丝数 +1）
            updateFollowCount(userId, targetUserId, true);

            // 3. 更新关注集合缓存（用于读取时快速判断是否关注）
            redisTemplate.opsForSet().add("user:following:" + userId,
                String.valueOf(targetUserId));

            // 4. 异步拉取被关注人最近动态写入收件箱
            FollowEvent event = new FollowEvent();
            event.setUserId(userId);
            event.setTargetUserId(targetUserId);
            event.setEventType("FOLLOW");
            event.setMsgId(UUID.randomUUID().toString());
            event.setTimestamp(System.currentTimeMillis());

            mqTemplate.asyncSend("follow-event-topic", event, new SendCallback() {
                @Override
                public void onSuccess(SendResult result) {
                    log.info("关注事件MQ发送成功, userId={}, targetUserId={}",
                        userId, targetUserId);
                }

                @Override
                public void onException(Throwable e) {
                    log.error("关注事件MQ发送失败, userId={}, targetUserId={}",
                        userId, targetUserId, e);
                    // 降级：同步回填
                    backfillInboxSync(userId, targetUserId);
                }
            });

        } catch (DuplicateKeyException e) {
            // 并发关注，幂等处理
            log.info("并发关注已处理, userId={}, targetUserId={}", userId, targetUserId);
        } catch (Exception e) {
            log.error("关注操作异常, userId={}, targetUserId={}", userId, targetUserId, e);
            throw new SystemException("关注失败，请稍后重试");
        }
    }

    /**
     * 取关某人
     */
    public void unfollow(Long userId, Long targetUserId) {
        log.info("用户取关, userId={}, targetUserId={}", userId, targetUserId);

        try {
            // 1. 删除关注关系
            int deleted = followMapper.delete(userId, targetUserId);
            if (deleted == 0) {
                log.info("关注关系不存在, 跳过, userId={}, targetUserId={}",
                    userId, targetUserId);
                return;
            }

            // 2. 更新计数缓存
            updateFollowCount(userId, targetUserId, false);

            // 3. 从关注集合缓存中移除
            redisTemplate.opsForSet().remove("user:following:" + userId,
                String.valueOf(targetUserId));

            // 4. 异步清理收件箱中对方的动态
            FollowEvent event = new FollowEvent();
            event.setUserId(userId);
            event.setTargetUserId(targetUserId);
            event.setEventType("UNFOLLOW");
            event.setMsgId(UUID.randomUUID().toString());
            event.setTimestamp(System.currentTimeMillis());

            mqTemplate.asyncSend("follow-event-topic", event, null);

            // 5. 同时采用"读取时过滤"策略作为兜底
            // 即使异步清理不彻底，读取时也会过滤已取关用户的内容
            log.info("取关处理完成, userId={}, targetUserId={}", userId, targetUserId);

        } catch (Exception e) {
            log.error("取关操作异常, userId={}, targetUserId={}", userId, targetUserId, e);
            throw new SystemException("取关失败，请稍后重试");
        }
    }

    /**
     * 同步回填收件箱（MQ发送失败时的降级方案）
     */
    private void backfillInboxSync(Long userId, Long targetUserId) {
        try {
            List<Outbox> outboxItems = outboxMapper.selectRecent(
                targetUserId, null, BACKFILL_FEED_COUNT);

            String inboxKey = "inbox:" + userId;
            for (Outbox outbox : outboxItems) {
                redisTemplate.opsForZSet().add(inboxKey,
                    String.valueOf(outbox.getFeedId()),
                    outbox.getCreateTime().getTime());
            }

            // 限制收件箱大小
            Long size = redisTemplate.opsForZSet().zCard(inboxKey);
            if (size != null && size > MAX_INBOX_SIZE) {
                redisTemplate.opsForZSet()
                    .removeRange(inboxKey, 0, (int)(size - MAX_INBOX_SIZE) - 1);
            }

            log.info("同步回填收件箱完成, userId={}, targetUserId={}, count={}",
                userId, targetUserId, outboxItems.size());
        } catch (Exception e) {
            log.error("同步回填收件箱失败, userId={}, targetUserId={}",
                userId, targetUserId, e);
        }
    }

    /**
     * 更新关注/粉丝计数缓存
     */
    private void updateFollowCount(Long userId, Long targetUserId, boolean isFollow) {
        int delta = isFollow ? 1 : -1;

        // 用户关注数
        redisTemplate.opsForValue().increment("user:following:count:" + userId, delta);
        // 被关注者粉丝数
        redisTemplate.opsForValue().increment("user:follower:count:" + targetUserId, delta);
    }
}
```

#### 6.3.3 关注/取关事件消费者

```java
/**
 * 关注/取关事件消费者
 *
 * 处理逻辑：
 * - FOLLOW：拉取被关注人最近N条动态，写入自己的收件箱
 * - UNFOLLOW：从收件箱中清理对方的动态
 *
 * 幂等控制：
 * - FOLLOW：通过关注关系表判断是否已关注（防重复回填）
 * - UNFOLLOW：ZREM操作天然幂等（删除不存在的member不会报错）
 */
@Component
@RocketMQMessageListener(
    topic = "follow-event-topic",
    consumerGroup = "follow-event-consumer-group",
    consumeMode = ConsumeMode.CONCURRENTLY,
    maxReconsumeTimes = 3
)
@Slf4j
public class FollowEventConsumer implements RocketMQListener<FollowEvent> {

    @Autowired
    private OutboxMapper outboxMapper;

    @Autowired
    private FeedMapper feedMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private FollowMapper followMapper;

    @Autowired
    private RocketMQTemplate mqTemplate;

    /** 关注后回填动态数量 */
    private static final int BACKFILL_FEED_COUNT = 100;

    /** 取关时单次清理最大数量 */
    private static final int CLEAN_BATCH_SIZE = 200;

    /** 收件箱最大容量 */
    private static final int MAX_INBOX_SIZE = 1000;

    /** 收件箱TTL */
    private static final long INBOX_TTL = 7 * 24 * 3600L;

    @Override
    public void onMessage(FollowEvent event) {
        try {
            if ("FOLLOW".equals(event.getEventType())) {
                handleFollow(event);
            } else if ("UNFOLLOW".equals(event.getEventType())) {
                handleUnfollow(event);
            }
        } catch (Exception e) {
            log.error("关注事件处理异常, event={}", JSON.toJSONString(event), e);
            throw new RuntimeException("关注事件处理失败", e);
        }
    }

    /**
     * 处理关注：回填被关注人最近动态到收件箱
     */
    private void handleFollow(FollowEvent event) {
        Long userId = event.getUserId();
        Long targetUserId = event.getTargetUserId();

        log.info("处理关注事件, userId={}, targetUserId={}, msgId={}",
            userId, targetUserId, event.getMsgId());

        // 1. 幂等检查：确认关注关系仍然存在（防止先关注再取关的竞态）
        Boolean isFollowing = followMapper.exists(userId, targetUserId);
        if (!isFollowing) {
            log.warn("关注关系已不存在, 跳过回填, userId={}, targetUserId={}",
                userId, targetUserId);
            return;
        }

        // 2. 幂等检查：防止重复回填
        String idempotentKey = "follow:backfill:" + userId + ":" + targetUserId;
        Boolean isNew = redisTemplate.opsForValue()
            .setIfAbsent(idempotentKey, "1", 24, TimeUnit.HOURS);
        if (Boolean.FALSE.equals(isNew)) {
            log.info("回填已处理过, 跳过, userId={}, targetUserId={}", userId, targetUserId);
            return;
        }

        // 3. 查询被关注人最近的发件箱
        List<Outbox> outboxItems = outboxMapper.selectRecent(
            targetUserId, null, BACKFILL_FEED_COUNT);

        if (CollectionUtils.isEmpty(outboxItems)) {
            log.info("被关注人无最近动态, userId={}, targetUserId={}", userId, targetUserId);
            return;
        }

        // 4. 批量写入收件箱（Pipeline）
        String inboxKey = "inbox:" + userId;
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Outbox outbox : outboxItems) {
                connection.zAdd(inboxKey.getBytes(),
                    outbox.getCreateTime().getTime(),
                    String.valueOf(outbox.getFeedId()).getBytes());
            }
            // 限制收件箱大小
            connection.zRemRangeByRank(inboxKey.getBytes(), 0,
                -(MAX_INBOX_SIZE + 1));
            // 刷新TTL
            connection.expire(inboxKey.getBytes(), INBOX_TTL);
            return null;
        });

        log.info("关注回填完成, userId={}, targetUserId={}, backfillCount={}",
            userId, targetUserId, outboxItems.size());
    }

    /**
     * 处理取关：从收件箱清理对方动态
     *
     * 策略：
     * 1. 查询对方最近发的动态ID（从发件箱获取）
     * 2. 检查这些动态在收件箱中是否存在
     * 3. 批量ZREM删除
     * 4. 如果动态过多，分批清理
     */
    private void handleUnfollow(FollowEvent event) {
        Long userId = event.getUserId();
        Long targetUserId = event.getTargetUserId();

        log.info("处理取关事件, userId={}, targetUserId={}, msgId={}",
            userId, targetUserId, event.getMsgId());

        // 幂等检查：确认已取关
        Boolean isFollowing = followMapper.exists(userId, targetUserId);
        if (isFollowing) {
            log.warn("关注关系仍存在, 跳过清理, userId={}, targetUserId={}",
                userId, targetUserId);
            return;
        }

        String inboxKey = "inbox:" + userId;

        // 1. 查询对方最近发的动态ID（从发件箱获取）
        List<Outbox> outboxItems = outboxMapper.selectRecent(
            targetUserId, null, CLEAN_BATCH_SIZE);

        if (CollectionUtils.isEmpty(outboxItems)) {
            log.info("对方无最近动态, 无需清理, userId={}, targetUserId={}",
                userId, targetUserId);
            return;
        }

        // 2. 查出这些动态ID在收件箱中实际存在的（有些可能已过期被清理）
        List<String> feedIdStrs = outboxItems.stream()
            .map(o -> String.valueOf(o.getFeedId()))
            .collect(Collectors.toList());

        // 批量检查收件箱中是否存在
        List<String> existingMembers = new ArrayList<>();
        for (String feedIdStr : feedIdStrs) {
            Double score = redisTemplate.opsForZSet().score(inboxKey, feedIdStr);
            if (score != null) {
                existingMembers.add(feedIdStr);
            }
        }

        if (existingMembers.isEmpty()) {
            log.info("收件箱中无对方动态, 无需清理, userId={}, targetUserId={}",
                userId, targetUserId);
            return;
        }

        // 3. 批量ZREM删除
        Long removedCount = redisTemplate.opsForZSet()
            .remove(inboxKey, existingMembers.toArray());

        log.info("取关清理完成, userId={}, targetUserId={}, removedCount={}",
            userId, targetUserId, removedCount);

        // 4. 如果对方动态数量超过单批清理量，发送延迟消息继续清理
        if (outboxItems.size() >= CLEAN_BATCH_SIZE) {
            FollowEvent continueEvent = new FollowEvent();
            continueEvent.setUserId(userId);
            continueEvent.setTargetUserId(targetUserId);
            continueEvent.setEventType("UNFOLLOW_CONTINUE");
            continueEvent.setMsgId(UUID.randomUUID().toString());
            continueEvent.setOffset(outboxItems.get(outboxItems.size() - 1).getCreateTime());

            // 延迟5秒继续清理下一批
            mqTemplate.asyncSend("follow-event-topic:delay", continueEvent,
                new SendCallback() {
                    @Override
                    public void onSuccess(SendResult result) {
                        log.info("延迟清理消息发送成功, userId={}, targetUserId={}",
                            userId, targetUserId);
                    }

                    @Override
                    public void onException(Throwable e) {
                        log.warn("延迟清理消息发送失败, 依赖读取时过滤兜底, userId={}, targetUserId={}",
                            userId, targetUserId);
                    }
                });
        }
    }
}
```

#### 6.3.4 数据一致性保障

```java
/**
 * 关注关系数据一致性补偿服务
 *
 * 问题场景：
 * 1. 关注关系写入成功，但回填收件箱失败（MQ丢失）
 * 2. 取关关系删除成功，但清理收件箱失败
 * 3. Redis与MySQL数据不一致
 *
 * 补偿策略：
 * 1. 定时任务扫描补偿表，重试失败操作
 * 2. 每日凌晨全量校验活跃用户的收件箱一致性
 */
@Service
@Slf4j
public class FollowConsistencyService {

    @Autowired
    private CompensationMapper compensationMapper;

    @Autowired
    private FollowService followService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private OutboxMapper outboxMapper;

    @Autowired
    private FollowFeignClient followClient;

    /**
     * 定时补偿任务：每5分钟扫描补偿表
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void compensate() {
        List<CompensationRecord> records = compensationMapper.selectPending(100);

        for (CompensationRecord record : records) {
            try {
                boolean success = retryCompensation(record);
                if (success) {
                    compensationMapper.updateStatus(record.getId(), 1); // 标记成功
                } else {
                    int newRetryCount = record.getRetryCount() + 1;
                    if (newRetryCount >= 5) {
                        // 超过最大重试次数，标记为需人工处理
                        compensationMapper.updateStatus(record.getId(), 2);
                        AlertManager.send("关注补偿超过最大重试次数，需人工处理",
                            "recordId=" + record.getId());
                    } else {
                        compensationMapper.incrementRetryCount(record.getId());
                    }
                }
            } catch (Exception e) {
                log.error("补偿重试异常, recordId={}", record.getId(), e);
                compensationMapper.incrementRetryCount(record.getId());
            }
        }
    }

    /**
     * 重试补偿操作
     */
    private boolean retryCompensation(CompensationRecord record) {
        switch (record.getBizType()) {
            case "FEED_PUSH":
                return retryFeedPush(record);
            case "FOLLOW_BACKFILL":
                return retryFollowBackfill(record);
            case "UNFOLLOW_CLEAN":
                return retryUnfollowClean(record);
            default:
                log.warn("未知补偿类型, bizType={}", record.getBizType());
                return true; // 跳过未知类型
        }
    }

    private boolean retryFeedPush(CompensationRecord record) {
        PushMessage msg = JSON.parseObject(record.getPayload(), PushMessage.class);
        try {
            // 重新获取粉丝并推送
            List<Long> followerIds = followClient.getFollowerIds(msg.getPublisherId());
            String inboxKey = "inbox:";
            for (Long followerId : followerIds) {
                redisTemplate.opsForZSet().add(inboxKey + followerId,
                    String.valueOf(msg.getFeedId()),
                    msg.getTimestamp());
            }
            return true;
        } catch (Exception e) {
            log.error("Feed推送补偿失败, recordId={}", record.getId(), e);
            return false;
        }
    }

    private boolean retryFollowBackfill(CompensationRecord record) {
        FollowEvent event = JSON.parseObject(record.getPayload(), FollowEvent.class);
        try {
            List<Outbox> outboxItems = outboxMapper.selectRecent(
                event.getTargetUserId(), null, 100);
            String inboxKey = "inbox:" + event.getUserId();
            for (Outbox outbox : outboxItems) {
                redisTemplate.opsForZSet().add(inboxKey,
                    String.valueOf(outbox.getFeedId()),
                    outbox.getCreateTime().getTime());
            }
            return true;
        } catch (Exception e) {
            log.error("关注回填补偿失败, recordId={}", record.getId(), e);
            return false;
        }
    }

    private boolean retryUnfollowClean(CompensationRecord record) {
        FollowEvent event = JSON.parseObject(record.getPayload(), FollowEvent.class);
        try {
            List<Outbox> outboxItems = outboxMapper.selectRecent(
                event.getTargetUserId(), null, 200);
            String inboxKey = "inbox:" + event.getUserId();
            String[] feedIds = outboxItems.stream()
                .map(o -> String.valueOf(o.getFeedId()))
                .toArray(String[]::new);
            redisTemplate.opsForZSet().remove(inboxKey, feedIds);
            return true;
        } catch (Exception e) {
            log.error("取关清理补偿失败, recordId={}", record.getId(), e);
            return false;
        }
    }
}
```

#### 6.3.5 三个案例的核心设计总结

| 维度 | 案例一：发布写扩散 | 案例二：读取全链路 | 案例三：关注/取关更新 |
|------|-------------------|-------------------|---------------------|
| 核心挑战 | 写放大、大V推送延迟 | 读延迟、聚合复杂度 | 数据一致性、竞态条件 |
| 幂等方案 | 请求指纹 + Redis SETNX | 游标分页天然幂等 | 关注关系表 + 幂等Key |
| 异步化 | MQ分批推送粉丝收件箱 | 已读标记异步MQ | 回填/清理均走MQ异步 |
| 降级策略 | MQ失败存补偿表 | 读取异常返回空列表 | MQ失败同步降级执行 |
| 失败补偿 | 重试3次 → 死信队列 → 补偿表 | 不适用（读操作无副作用） | 定时任务扫描补偿表重试 |
| 大V处理 | 拉模式，仅写发件箱 | 读时实时拉取大V发件箱合并 | 回填时从发件箱拉取 |
| 性能优化 | Redis Pipeline批量写 | L1/L2/L3多级缓存 | Pipeline批量写 + 分批清理 |
| 数据一致性 | 本地事务保证入库+发件箱 | 缓存与DB最终一致 | 关注表为准 + 读取时过滤兜底 |
