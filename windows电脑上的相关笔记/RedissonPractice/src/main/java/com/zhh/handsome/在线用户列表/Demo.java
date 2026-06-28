package com.zhh.handsome.在线用户列表;

public class Demo {



//    社交应用需实时展示「当前在线用户」列表，支持多台应用服务器部署（用户可能连接不同服务器），需保证列表数据一致。



    /*单台服务器用内存集合（如 HashSet）存储在线用户，无法同步到其他服务器，导致列表数据不一致。
    用户下线时需从所有服务器的集合中移除，传统方案难以实现。*/



    //这redis最大的特点就是是一个共享MAP，所有节点都可以访问，并且数据一致。


//    用 RSet 分布式集合，所有服务器共享同一个集合，用户登录时添加、下线时移除，天然支持分布式一致性。



    /*@Service
    public class OnlineUserService {
        @Autowired
        private RedissonClient redissonClient;

        // 获取分布式集合（存储在线用户ID）
        private RSet<Long> getOnlineUserSet() {
            return redissonClient.getSet("online:users", new LongCodec()); // 指定Long类型编码
        }

        // 用户登录：添加到在线列表
        public void userLogin(Long userId) {
            getOnlineUserSet().add(userId);
        }

        // 用户下线：从在线列表移除
        public void userLogout(Long userId) {
            getOnlineUserSet().remove(userId);
        }

        // 获取当前在线用户列表
        public List<Long> getOnlineUsers() {
            return new ArrayList<>(getOnlineUserSet());
        }

        // 统计在线人数
        public long getOnlineCount() {
            return getOnlineUserSet().size();
        }
    }*/






   /* RSet 支持 Redis 的 Set 特性（去重），避免同一用户重复登录时多次添加。
    可结合「过期监听」：用户长时间无操作时自动下线（通过 RSet 配合 RExpirable 设置过期时间）。*/










}
