package WebFlux.service.impl;

import WebFlux.entity.user;
import WebFlux.service.UserService;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@Repository
public class UserServiceImpl implements UserService {


    //模拟操作数据库
    //创建一个map集合存储数据
    private static final Map<Integer,user> userMap = new HashMap<>();
    public UserServiceImpl()
    {
        userMap.put(1,user.builder().name("张三").age(18).sex("男").build());
        userMap.put(2,user.builder().name("李四").age(18).sex("男").build());
        userMap.put(3,user.builder().name("王五").age(18).sex("女").build());
    }
    @Override
    public Mono<user> getUserById(Integer id) {
        if(userMap.containsKey(id))
        {
            return Mono.justOrEmpty(userMap.get(id));
        }
        return null;
    }

    @Override
    public Flux<user> selectAllUser() {
//      方式一  return Flux.fromIterable(userMap.values());
        //方式二
        Flux<user> flux=Flux.create(fluxSink -> {
            for(user user:userMap.values()){
                fluxSink.next(user);
            }
            fluxSink.complete();
        });
       return flux;
    }

    @Override
    public Mono<Void> addUser(Mono<user> user) {
        return user.doOnNext(u -> userMap.put(userMap.size()+1,u))
                .thenEmpty(Mono.empty());
    }
}
