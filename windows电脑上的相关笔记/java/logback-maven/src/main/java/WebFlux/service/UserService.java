package WebFlux.service;

import WebFlux.entity.user;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserService {
    Mono<user> getUserById(Integer id);

    Flux<user> selectAllUser();

    Mono<Void> addUser(Mono<user> user);

}
