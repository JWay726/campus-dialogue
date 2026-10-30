package com.campus.dialogue.controller;

import com.campus.dialogue.entity.UserEntity;
import com.campus.dialogue.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

/** 管理员用户管理：列表 / 新建（可指定角色）/ 删除。由 Spring Security 的 {@code /api/admin/**} → ROLE_ADMIN 规则保护。 */
@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    private final UserService userService;

    public UserAdminController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public Mono<List<UserEntity>> list() {
        return Mono.fromCallable(userService::list).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<ResponseEntity<UserEntity>> create(@RequestBody Map<String, String> body) {
        return Mono.fromCallable(() -> {
            try {
                UserEntity u = userService.createUser(
                        body.get("username"), body.get("password"), body.getOrDefault("role", "USER"));
                return ResponseEntity.ok(u);
            } catch (UserService.UserException e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).<UserEntity>body(null);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<Map<String, Object>> delete(@PathVariable Long id) {
        return Mono.fromCallable(() -> {
            userService.deleteUser(id);
            return Map.<String, Object>of("deleted", id);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
