package WebFlux.controller;

import WebFlux.entity.user;
import WebFlux.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
public class UserController {
    @Autowired
    private UserService userService;

    @GetMapping("/getUserById")
    public Mono<user> getUserById(Integer id) {
        return userService.getUserById(id);
    }
    @GetMapping("/selectAllUser")
    public Flux<user> selectAllUser() {
        return userService.selectAllUser();
    }
    @GetMapping("/addUser")
    public Mono<Void> addUser(@RequestBody user  user) {
        Mono<user> user1 = Mono.just(user);
        return userService.addUser(user1);
    }

}
