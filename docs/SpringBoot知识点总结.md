# Spring Boot 知识点总结（对照 campus-dialogue 项目）

> 本文把本项目实际用到的 Spring Boot 相关知识点系统梳理一遍，每条都尽量对应到本仓库的真实代码位置，
> 便于复习与答辩讲解。按「构建 → 启动 → 配置 → Web → 数据 → 容器 → 外部调用 → 异步 → 测试 → 运维」组织。

---

## 一、项目构建与依赖管理

### 1.1 `spring-boot-starter-parent`（父 POM）
`pom.xml` 继承 `spring-boot-starter-parent:3.4.5`：

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.4.5</version>
  <relativePath/>
</parent>
```

作用：
- **统一依赖版本**（dependencyManagement）：引入 starter 时无需写版本号，避免版本冲突。
- 提供默认插件配置、资源过滤、编码（UTF-8）等约定。
- `spring-boot-dependencies` 管理了 Spring Framework、Jackson、Hibernate、Reactor 等整套生态的版本。

### 1.2 Starter（场景启动器）
Starter 本质是「一组成熟依赖的聚合 + 对应的自动配置」。项目用到：

| Starter | 作用 |
|---|---|
| `spring-boot-starter-webflux` | 响应式 Web（Netty），提供 `@RestController`、`WebClient`、SSE |
| `spring-boot-starter-data-jpa` | Spring Data JPA + Hibernate + 事务 |
| `spring-boot-starter-test` | JUnit 5、Mockito、AssertJ 等测试套件 |
| `spring-boot-devtools` | 开发热重启、LiveReload |

> 注意：本项目**没有**引入 `spring-boot-starter-web`（Servlet 版），而是 `webflux`，因此整个 Web 层是**响应式**的，
> 返回 `Mono` / `Flux`，文件上传用 `FilePart` 而非 `MultipartFile`。

### 1.3 `spring-boot-maven-plugin`
```xml
<plugin>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-maven-plugin</artifactId>
</plugin>
```
`mvn package` 时执行 **repackage**，把普通 jar 重新打成**可执行 fat jar**（内含依赖与启动器），
可直接 `java -jar` 运行。它会把原始 jar 备份为 `*.jar.original`。

> 实操坑：若旧 jar 正被运行中的进程占用（Windows 文件锁），repackage 会失败；需先停进程再打包。

### 1.4 属性（properties）集中管理版本
```xml
<properties>
  <java.version>17</java.version>
  <flyway.version>11.3.0</flyway.version>
</properties>
```
`java.version` 会被父 POM 用于编译插件（`maven.compiler.source/target`）。本项目源码级别 17，运行于 JDK 25 亦可。

---

## 二、启动类与自动配置

### 2.1 `@SpringBootApplication`
`CampusDialogueApplication.java`：

```java
@SpringBootApplication
public class CampusDialogueApplication {
    public static void main(String[] args) {
        SpringApplication.run(CampusDialogueApplication.class, args);
    }
}
```

`@SpringBootApplication` 是**组合注解**，等价于：
- `@SpringBootConfiguration`（本质是 `@Configuration`）
- `@EnableAutoConfiguration`（**开启自动配置**）
- `@ComponentScan`（默认扫描启动类所在包及其子包）

### 2.2 自动配置原理（理解即可）
`@EnableAutoConfiguration` 通过 `spring.factories` / `AutoConfiguration.imports` 加载大量 `XxxAutoConfiguration`，
这些配置类上带 `@ConditionalOnClass`、`@ConditionalOnMissingBean`、`@ConditionalOnProperty` 等**条件注解**，
只有「类存在且用户没自己定义」时才生效。例如：
- 引入了 `spring-boot-starter-data-jpa` 且配置了 `spring.datasource` → 自动装配 `DataSource`、`EntityManagerFactory`、事务管理器。
- 引入了 `webflux` → 自动装配 Netty 服务器、`WebFluxAutoConfiguration`、编解码器（Jackson）。

> 这就是「约定优于配置」：你只写差异化的配置，其余用默认值。

### 2.3 `CommandLineRunner` 做启动初始化
```java
@Bean
public CommandLineRunner bootstrap(DocumentService documentService, ...) {
    return args -> {
        userService.seedIfEmpty();
        documentService.seedIfEmpty();
        if (vectorStore.size() == 0) { retrievalService.indexAll(); }
    };
}
```
`CommandLineRunner` 是 Spring Boot 提供的**启动后回调**接口，容器就绪后执行一次，适合做数据初始化。
与之类似的还有 `ApplicationRunner`（参数封装为 `ApplicationArguments`）。

### 2.4 启动前的 JVM 级设置
```java
Security.setProperty("networkaddress.cache.negative.ttl", "0");
SpringApplication.run(...);
```
在 `main` 里 `run` 之前关闭 JDK 的 DNS 负缓存——属于「Spring Boot 之外但启动期必做」的工程实践。

---

## 三、配置体系（application.yml）

### 3.1 YAML 层级与占位符
```yaml
server:
  port: 8080
campus:
  llm:
    api-key: ${LLM_API_KEY:sk-xxxx}
```
- 层级结构映射为属性 `campus.llm.api-key`。
- `${ENV:默认值}`：**环境变量优先，缺省用默认值**；支持嵌套 `${A:${B:默认}}`（本项目 fallback 就用了）。

### 3.2 Profile（多环境）
- 主配置 `application.yml`：`spring.profiles.active: dev`
- 分环境文件：`application-dev.yml`、`application-prod.yml`
- 也可以在**单个 yml 内用 `---` 分段 + `spring.config.activate.on-profile: dev`**（本项目主 yml 末尾就用了这种写法配置 dev 专属静态资源与 devtools）

| Profile | 数据源 | 建表 | Flyway |
|---|---|---|---|
| dev | MySQL | `ddl-auto: validate`（仅校验） | 开启 |
| prod | MySQL（可用 `DB_URL` 指向生产实例） | `ddl-auto: validate`（仅校验） | 开启 |

> 本项目已**移除 H2**，dev / prod 统一使用 MySQL、同一套 Flyway 脚本，避免「本地能跑、上线炸」的 schema 漂移。

启动切换：`--spring.profiles.active=prod` 或环境变量 `SPRING_PROFILES_ACTIVE=prod`。

### 3.3 配置覆盖优先级（从高到低，节选）
1. 命令行参数 `--server.port=8088`
2. `SPRING_APPLICATION_JSON` / 系统环境变量
3. `application-{profile}.yml`
4. `application.yml`
5. 代码里的默认值（`@ConfigurationProperties` 字段初始值）

### 3.4 `@ConfigurationProperties` 类型安全绑定
`AppProperties.java`：

```java
@Component
@ConfigurationProperties(prefix = "campus")
public class AppProperties {
    private Llm llm = new Llm();
    private Embedding embedding = new Embedding();
    // ... 嵌套静态类 + getter/setter
}
```

要点：
- `prefix = "campus"` 把 `campus.llm.base-url`、`campus.rag.top-k` 等**批量绑定**到对象。
- 支持**松散绑定**：`base-url` / `baseUrl` / `BASE_URL` 都能绑定到 `baseUrl`。
- 嵌套对象/集合通过内部类 + 初始化实例实现。
- **必须有 getter/setter**（JavaBean 规范），否则绑定不上。
- 相比 `@Value("${...}")` 逐个注入，`@ConfigurationProperties` 更适合**成组配置**，且支持校验（`@Validated`）。

> 本项目的 `AppProperties` 用 `@Component` 注册为 Bean；也可改用 `@EnableConfigurationProperties(AppProperties.class)`。

### 3.5 `server.*` 与 `spring.*` 常用项
```yaml
server:
  port: 8088
spring:
  codec:
    max-in-memory-size: 10MB     # WebFlux 上传/响应体内存缓冲上限
  web:
    resources:
      static-locations: file:src/main/resources/static/, classpath:/static/...
      cache:
        period: 0                # 关闭静态资源缓存（dev）
```

---

## 四、Web 层（Spring WebFlux 响应式）

### 4.1 与 Spring MVC 的区别
| | Spring MVC（Servlet） | Spring WebFlux（Reactive） |
|---|---|---|
| 服务器 | Tomcat（Servlet 容器） | Netty（事件循环，少量线程） |
| 返回类型 | 普通对象 / `ResponseEntity` | `Mono<T>` / `Flux<T>` |
| 阻塞 | 一线程一请求 | 非阻塞，异步回调 |
| 文件上传 | `MultipartFile` | `FilePart` |

本项目引入的是 `webflux`，所以控制器大量返回 `Mono` / `Flux`。

### 4.2 常用注解
```java
@RestController                 // = @Controller + @ResponseBody，返回体直接序列化为 JSON
@RequestMapping("/api/docs")    // 类级路由前缀
public class DocController {

    @GetMapping("/status")      // GET
    public Map<String, Object> status() { ... }

    @PostMapping("/index")      // POST
    public Mono<Map<String, Object>> index(@RequestBody Map<String, String> body) { ... }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> upload(@RequestPart("files") Flux<FilePart> files) { ... }
}
```
- `@PathVariable`：路径变量（`/sessions/{id}`）。
- `@RequestParam`：查询参数（`?from=123`）。
- `@RequestBody`：JSON 请求体（由 Jackson 反序列化）。
- `@RequestPart`：multipart 的某个 part（WebFlux 上传用 `FilePart`）。

### 4.3 响应式类型 `Mono` / `Flux`
- `Mono<T>`：0 或 1 个元素的异步序列（适合单个结果）。
- `Flux<T>`：0~N 个元素的异步序列（适合列表、流）。
- 常见操作符（本项目用到）：
  - `Mono.fromCallable(...)`：把阻塞调用包成 Mono。
  - `.flatMap(...)`：异步映射。
  - `.collectList()`：`Flux` → `Mono<List>`。
  - `.subscribeOn(Schedulers.boundedElastic())`：把**阻塞逻辑**调度到弹性线程池，避免阻塞 Netty 事件循环。

```java
@PostMapping("/reindex")
public Mono<Map<String, Object>> reindex() {
    return Mono.fromCallable(() -> {
        int n = retrievalService.indexAll();
        return Map.of("reindexed", n, "chunks", vectorStore.size());
    }).subscribeOn(Schedulers.boundedElastic());
}
```

### 4.4 SSE 流式输出
`ChatController` 返回 `Flux<ServerSentEvent<String>>`：

```java
@PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public ResponseEntity<Flux<ServerSentEvent<String>>> chat(@RequestBody ChatRequest req) { ... }
```

- `MediaType.TEXT_EVENT_STREAM_VALUE` = `text/event-stream`，浏览器可逐条接收。
- `ServerSentEvent<String>` 可带 `event` 名称（`sources` / `token` / `done` / `error`）和 `data`。
- 前端用 `fetch` 读 `ReadableStream`，手动解析 `event:` / `data:` 行，实现打字机效果。

### 4.5 WebFlux 文件上传（`FilePart` + `DataBufferUtils`）
```java
@PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
public Mono<Map<String, Object>> upload(@RequestPart("files") Flux<FilePart> files) {
    return files
        .flatMap(part -> DataBufferUtils.join(part.content())   // 把分块内容合并成一个 DataBuffer
            .map(buf -> {
                String content = buf.toString(StandardCharsets.UTF_8);
                DataBufferUtils.release(buf);                   // 必须释放，防止内存泄漏
                return documentService.upload(part.filename(), content);
            }))
        .collectList()
        .map(saved -> Map.of("uploaded", saved.size(), "chunks", vectorStore.size()));
}
```
要点：
- WebFlux 没有 `MultipartFile`，用 `FilePart`；`part.content()` 是 `Flux<DataBuffer>`。
- `DataBufferUtils.join` 合并分块；用完 `release` 释放池化内存。
- 大文件受 `spring.codec.max-in-memory-size` 限制（本项目设为 10MB）。
- Reactor 3.7.5 已移除 `Flux.aggregate()`，不要用旧写法。

### 4.6 Spring Security（WebFlux 响应式安全）——原生登录体系

本项目使用 **Spring Security 6.4**，采用框架**自带的全套登录体系**（表单登录 + 服务端 Session），
**没有自研过滤器、没有自签 token**。

#### 4.6.1 三个标准扩展点

```java
// ① 用户来源：查库 → UserDetails
@Service
public class CampusUserDetailsService implements ReactiveUserDetailsService {
    @Override
    public Mono<UserDetails> findByUsername(String username) {
        return Mono.fromCallable(() -> userRepository.findByUsername(username))
                .subscribeOn(Schedulers.boundedElastic())
                .switchIfEmpty(Mono.error(new UsernameNotFoundException(username)))
                .map(u -> User.withUsername(u.getUsername())
                              .password(u.getPasswordHash())
                              .roles(u.getRole())        // 自动补 ROLE_ 前缀
                              .build());
    }
}

// ② 密码校验：实现 PasswordEncoder（此处复用项目既有的 SHA-256 + 随机盐，兼容存量账号）
public class CampusPasswordEncoder implements PasswordEncoder {
    public String encode(CharSequence raw) { return Passwords.hash(raw.toString()); }
    public boolean matches(CharSequence raw, String encoded) { return Passwords.matches(raw.toString(), encoded); }
}

// ③ 认证管理器：响应式版 DaoAuthenticationProvider
@Bean
public ReactiveAuthenticationManager authenticationManager(ReactiveUserDetailsService uds,
                                                           PasswordEncoder encoder) {
    var manager = new UserDetailsRepositoryReactiveAuthenticationManager(uds);
    manager.setPasswordEncoder(encoder);
    return manager;
}
```

#### 4.6.2 安全链配置

```java
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public ServerSecurityContextRepository securityContextRepository() {
        return new WebSessionServerSecurityContextRepository();   // 有状态：登录态存 WebSession
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                         ReactiveAuthenticationManager manager,
                                                         ServerSecurityContextRepository repo) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)       // 演示项目关闭，生产应开启
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                // ① 表单登录：处理 URL 仍是前端调的 /api/auth/login
                .formLogin(form -> form
                        .loginPage("/login.html")
                        // WebFlux 版没有 loginProcessingUrl()，用 matcher 指定「哪个请求算表单提交」
                        .requiresAuthenticationMatcher(
                                ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, "/api/auth/login"))
                        .authenticationSuccessHandler(jsonLoginSuccess())   // 返回 {username, role}
                        .authenticationFailureHandler(jsonLoginFailure()))  // 401 + JSON
                // ② 登出：框架负责清 SecurityContext + 让 Session 失效
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((ex, auth) -> writeJson(..., "{\"ok\":true}")))
                .authenticationManager(manager)
                .securityContextRepository(repo)
                .authorizeExchange(ex -> ex
                        .pathMatchers("/login.html", "/login.js", "/styles.css").permitAll()
                        .pathMatchers("/api/auth/login", "/api/auth/register").permitAll()
                        .pathMatchers("/api/auth/me").authenticated()
                        .pathMatchers("/api/admin/**", "/admin.html",
                                      "/api/docs/reindex", "/api/docs/upload").hasRole("ADMIN")
                        .pathMatchers("/api/chat/**", "/api/sessions/**", "/api/me/**",
                                      "/api/docs/status", "/", "/index.html").authenticated()
                        .anyExchange().permitAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((exchange, e) -> /* 401 或跳登录页 */)
                        .accessDeniedHandler((exchange, e) -> /* 403 或跳首页 */))
                .build();
    }
}
```

#### 4.6.3 认证全流程

```
浏览器 POST /api/auth/login (x-www-form-urlencoded: username & password)
  → FormLoginWebFilter 命中 requiresAuthenticationMatcher
  → ServerFormLoginAuthenticationConverter 取出 username/password
  → UserDetailsRepositoryReactiveAuthenticationManager.authenticate()
        ├── CampusUserDetailsService.findByUsername()   查 users 表
        └── CampusPasswordEncoder.matches()             校验密码
  → 成功：authenticationSuccessHandler 返回 JSON；SecurityContext 存入 WebSession → Set-Cookie: SESSION=...
  → 失败：authenticationFailureHandler 返回 401 JSON（不区分「用户不存在/密码错误」，防账号枚举）
后续请求：浏览器自动带 SESSION Cookie → 框架从 Session 恢复 SecurityContext → 授权规则判定
登出：POST /api/auth/logout → LogoutWebFilter 清上下文 + 让 Session 失效
```

#### 4.6.4 要点与坑

- `@EnableWebFluxSecurity` 开启**响应式**安全（不是 Servlet 的 `@EnableWebSecurity`）。
- `authorizeExchange` 是**路径级授权**的集中声明：`permitAll` / `authenticated` / `hasRole`；
  规则**按顺序匹配**，所以 `/api/auth/me` 的 `authenticated()` 必须写在 `/api/auth/**` 的 `permitAll()` 之前。
- `hasRole("ADMIN")` 匹配 authority `ROLE_ADMIN`，所以加载用户时用 `roles(role)` 让它自动加前缀。
- **WebFlux 的 `FormLoginSpec` 没有 `loginProcessingUrl()`**（那是 Servlet 版才有的方法），
  要用 `requiresAuthenticationMatcher(ServerWebExchangeMatchers.pathMatchers(POST, "/api/auth/login"))`。
- 表单登录只认 `application/x-www-form-urlencoded`，前端 fetch 要传 `new URLSearchParams({...})`，
  不能传 JSON 字符串。
- **principal 必须是 `UserDetails`**：`@AuthenticationPrincipal(expression = "username")` 会对 principal 求 SpEL，
  如果手动把 principal 存成 String（例如微信登录里自己构造 Authentication），表达式会报错。
- `WebSessionServerSecurityContextRepository` 是**有状态**的（对比：无状态 token 方案用 `NoOpServerSecurityContextRepository`）；
  会话空闲超时用 `server.reactive.session.timeout` 配置。
- 未登录/越权响应：API 返回 `401/403` JSON，页面 `302` 跳登录页/首页。
- 控制器取当前用户：`@AuthenticationPrincipal(expression = "username") String me`；需要角色时用 `@AuthenticationPrincipal UserDetails`。
- `AccessDeniedHandler` 里只 `setStatusCode(302) + setComplete()` 时状态码可能不提交（实测返回 200），
  要显式 `writeWith` 一个空 `DataBuffer` 强制提交。

### 4.7 `ResponseEntity` 与状态码
```java
response.setStatusCode(HttpStatus.UNAUTHORIZED);
response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
```
`ResponseEntity` 可显式控制**状态码 + 响应头 + 响应体**；WebFlux 下也可直接操作 `ServerHttpResponse`。

### 4.8 静态资源
- 默认从 `classpath:/static/` 提供。
- dev 环境把 `static-locations` 指向 `file:src/main/resources/static/`，实现**前端改完刷新即生效**。
- `cache.period: 0` 关闭缓存，避免拿到旧文件。

---

## 五、数据访问（Spring Data JPA）

### 5.1 实体映射
```java
@Entity
@Table(name = "sessions")
public class SessionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String username;      // 会话归属用户

    private String title;
    private Instant createdAt;
    private Instant updatedAt;
    // getter/setter
}
```
- `@Entity` + `@Table`：映射到表。
- `@Id` + `@GeneratedValue`：主键与自增策略。
- `@Column`：列约束（非空、长度、唯一、索引等）。

### 5.2 Repository 接口（只需声明，不用实现）
```java
public interface SessionRepository extends JpaRepository<SessionEntity, Long> {

    List<SessionEntity> findByUsernameOrderByUpdatedAtDesc(String username);

    Optional<SessionEntity> findByIdAndUsername(Long id, String username);
}
```
- 继承 `JpaRepository<T, ID>` 即获得 `save / findById / findAll / deleteById / count` 等 CRUD。
- **派生查询方法**：按方法名自动生成 SQL——
  - `findByUsername` → `where username = ?`
  - `OrderByUpdatedAtDesc` → `order by updated_at desc`
  - `findByIdAndUsername` → `where id = ? and username = ?`（本项目用它做**归属校验**，防越权）
- 复杂查询可用 `@Query`（JPQL 或原生 SQL）。

### 5.3 `@Transactional` 事务
```java
@Transactional
public void delete(long id, String username) {
    sessionRepository.findByIdAndUsername(id, username).ifPresent(s -> {
        messageRepository.deleteBySessionId(id);
        sessionRepository.deleteById(id);
    });
}
```
- 加在 **public 方法**上，由 Spring AOP 代理开启/提交事务。
- 删除主表 + 子表这类**多条写操作**必须加事务，保证原子性。
- 默认只对 `RuntimeException` 回滚；受检异常需 `@Transactional(rollbackFor = Exception.class)`。
- 注意：同类内部方法自调用不会走代理，事务不生效。

### 5.4 DDL 策略 `ddl-auto`
| 值 | 含义 | 本项目 |
|---|---|---|
| `none` | 不做任何操作 | — |
| `update` | 自动增量建表/加列（不删列，易产生 schema 漂移） | — |
| `validate` | 只校验实体与表结构是否一致，不一致**启动即失败** | **dev 与 prod 均使用** |
| `create` / `create-drop` | 每次重建（丢数据） | — |

> 为什么两个环境都用 `validate`：表结构统一由 Flyway 脚本管理，Hibernate 只做「实体 ↔ 表」一致性校验。
> 好处是**开发时就能发现**实体与迁移脚本不匹配的问题，而不是等上线才暴露；也彻底避免 `update` 在本地偷偷加列、
> 结果生产脚本里没有对应 DDL 的经典事故。

### 5.5 MySQL 数据源（dev / prod 统一，唯一数据源）
```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:mysql://localhost:3306/campus_dialogue?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true}
    username: ${DB_USERNAME:campus_app}
    password: ${DB_PASSWORD:change_me}
    driver-class-name: com.mysql.cj.jdbc.Driver
```
- 连接串放在主 `application.yml`，**dev / prod 共用**；换实例只需改环境变量 `DB_URL`，代码和配置零改动。
- 连接串参数含义（面试常问）：
  | 参数 | 作用 |
  |---|---|
  | `characterEncoding=UTF-8` + `useUnicode=true` | 保证中文不乱码（配合库 `utf8mb4`） |
  | `connectionTimeZone=Asia/Shanghai` | 明确会话时区，避免 `TIMESTAMP`/`DATETIME` 差 8 小时 |
  | `useSSL=false` | 本地/内网免证书握手；公网部署应改为 `true` 并配证书 |
  | `allowPublicKeyRetrieval=true` | MySQL 8+ 默认 `caching_sha2_password`，非 SSL 下需允许取公钥 |
- 驱动 `com.mysql.cj.jdbc.Driver` 由 `mysql-connector-j` 提供（`runtime` 作用域，编译期不依赖具体驱动）。

### 5.6 HikariCP 连接池（Spring Boot 默认）
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      connection-timeout: 5000
```
- Spring Boot 默认池化实现就是 **HikariCP**，无需额外依赖；`HikariPool-1 - Added connection ...` 是它的启动日志。
- `maximum-pool-size`：池上限。本项目是 WebFlux + JPA 混合，**JPA 是阻塞的**，池子不宜过大，10 个够用。
- `connection-timeout`：拿不到连接的等待上限（默认 30s）。设成 5s，数据库不可用时**快速失败**，
  而不是让请求一直吊着（配合 LLM 超时一起构成「整条链路都有超时」）。
- 面试延伸：池子不是越大越好，连接数超过数据库 `max_connections` 或核数收益递减；关键是「有界 + 有超时」。

### 5.7 Flyway 数据库迁移（dev / prod 都启用）
```
src/main/resources/db/migration/
├── V1__init_schema.sql          # sessions / messages / vectors / documents
├── V2__users.sql                # users 账号表
├── V3__user_profile.sql         # 学号 / 微信绑定字段
└── V4__sessions_username.sql    # 会话归属列（按用户隔离）
```
```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true     # 对已有非空库建立基线
    baseline-version: 0           # 让 V1 仍作为待执行迁移落地
    locations: classpath:db/migration
```
- 命名规则 `V{版本}__{描述}.sql`，按版本号顺序执行，已执行记录在 `flyway_schema_history` 表。
- 启动日志示例（真实运行输出）：
  ```
  Migrating schema `campus_dialogue` to version "2 - users"
  Migrating schema `campus_dialogue` to version "3 - user profile"
  Migrating schema `campus_dialogue` to version "4 - sessions username"
  Successfully applied 3 migrations to schema `campus_dialogue`, now at version v4
  ```
- **新增字段的正确姿势**：写一个 `V5__xxx.sql`，提交后所有环境（本地 / 测试 / 生产）启动时自动对齐，
  而不是靠 `ddl-auto: update` 各自为政。
- `baseline-on-migrate` 用于接管「历史上由 Hibernate 建过表的既有库」：没有 `flyway_schema_history` 时
  先打一个基线版本，再继续执行后续脚本。

---

## 六、IoC 容器与依赖注入

### 6.1 常用组件注解
| 注解 | 语义 |
|---|---|
| `@Component` | 通用 Bean |
| `@Service` | 业务层（语义化，本质同 `@Component`） |
| `@Repository` | 持久层（额外做异常转换） |
| `@Controller` / `@RestController` | 控制层 |
| `@Configuration` + `@Bean` | 配置类 / 手动声明 Bean |

### 6.2 构造器注入（推荐）
```java
@RestController
public class DocController {
    private final RetrievalService retrievalService;

    public DocController(RetrievalService retrievalService, ...) {
        this.retrievalService = retrievalService;
    }
}
```
- 单构造器时**无需 `@Autowired`**，Spring 自动注入。
- 构造器注入保证**不可变**（`final`）与**依赖不为空**，优于字段注入。

### 6.3 `@Bean` 声明第三方对象
```java
@Configuration
public class WebClientConfig {
    @Bean
    public WebClient embeddingWebClient(AppProperties props) {
        return WebClient.builder().baseUrl(props.getEmbedding().getBaseUrl()).build();
    }
}
```
方法名即 Bean 名；方法参数由容器自动注入（如 `AppProperties`）。

### 6.4 Bean 作用域与生命周期
- 默认**单例**（`singleton`）；本项目所有 Service/Controller 都是单例，故持有的是无状态成员。
- 生命周期：实例化 → 依赖注入 → `@PostConstruct` → 使用 → `@PreDestroy`。
- `@Order` 控制**同类型多 Bean 的排序**（本项目用它排 `WebFilter`）。

---

## 七、外部服务调用（WebClient）

### 7.1 声明 `WebClient`
```java
@Bean
public WebClient embeddingWebClient(AppProperties props) {
    HttpClient http = HttpClient.create()
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
        .responseTimeout(Duration.ofSeconds(60));
    return WebClient.builder()
        .baseUrl(props.getEmbedding().getBaseUrl())
        .clientConnector(new ReactorClientHttpConnector(http))
        .build();
}
```
- `WebClient` 是 WebFlux 的**响应式 HTTP 客户端**（替代 `RestTemplate`）。
- `.baseUrl(...)` 统一前缀；`.clientConnector(...)` 接 Reactor Netty 的 `HttpClient`。
- **超时必配**：`CONNECT_TIMEOUT_MILLIS`（建连）、`responseTimeout`（等响应头）。
  注意 `responseTimeout` 只约束「多久没开始回」，**不打断流式输出的总时长**。

### 7.2 流式调用 LLM
`LlmClient` 自行构建 WebClient，向 OpenAI 兼容的 `/chat/completions` 发 `stream: true` 请求，
把响应体 `Flux<String>` 按行解析出 `delta.content` 增量。并实现：
- **主备双通道**：主通道 402/429/DNS/超时且尚未吐字 → 切备用通道重放同一 prompt。
- **网络类异常重试 1 次**。
- 用 `onStatus` 抛出带响应体的异常，避免错误原因被吞。

### 7.3 为什么用 WebClient 而非 RestTemplate
在 WebFlux 中，`RestTemplate` 是**阻塞**的，会占住 Netty 事件循环线程，应改用非阻塞的 `WebClient`。

---

## 八、异步与并发

### 8.1 Reactor 操作符（复习）
- `Mono.fromCallable`：包装同步阻塞逻辑。
- `flatMap`：一对多/异步转换。
- `collectList`：流 → 列表。
- `subscribeOn(Schedulers.boundedElastic())`：阻塞任务切到弹性线程池。
- `doOnNext` / `doOnError` / `onErrorResume`：副作用与降级。

### 8.2 业务层限流
`ChatService` 用 `Semaphore(maxConcurrent)` 限制同时进行的 LLM 流式调用数，超出排队，
避免瞬时并发把模型服务打到 429。属于**应用层自我保护**，非 Spring 框架能力。

### 8.3 向量化并发
`RetrievalService` 用固定并发度（`campus.embedding.concurrency`）批量调用 embedding 接口，
缩短大知识库重建时间。

---

## 九、测试（spring-boot-starter-test）

### 9.1 依赖与工具
`spring-boot-starter-test` 聚合了 **JUnit 5（Jupiter）**、**Mockito**、AssertJ、Spring Test 等。

```java
@ExtendWith(MockitoExtension.class)
class SessionServiceTest {
    @Mock SessionRepository sessionRepository;
    @Mock MessageRepository messageRepository;
    @InjectMocks SessionService sessionService;

    @Test
    void listOnlyOwnSessions() {
        when(sessionRepository.findByUsernameOrderByUpdatedAtDesc("alice"))
            .thenReturn(List.of(entity(1L, "alice")));
        assertEquals(1, sessionService.list("alice").size());
        verify(sessionRepository).findByUsernameOrderByUpdatedAtDesc("alice");
    }
}
```
- `@Mock` 创建模拟对象；`@InjectMocks` 把 mock 注入被测对象。
- `when(...).thenReturn(...)` 打桩；`verify(...)` 校验交互。

### 9.2 JDK 17+ 的 Mockito 坑
Mockito 的 inline mock maker 需要改写字节码，JDK 17+ 模块系统默认禁止，需在 surefire 加：
```xml
<argLine>--add-opens java.base/java.lang=ALL-UNNAMED ...</argLine>
```
否则会报 `Unable to make ... accessible`。

### 9.3 本项目测试覆盖
- `VectorStoreTest`：向量存储与相似度检索
- `RetrievalServiceTest`：检索阈值、docId 去重（用 **fake 子类**替代对具体类的 mock）
- `MemoryServiceTest`：字符预算裁剪
- `CampusPasswordEncoderTest`：密码哈希生成与校验（含历史哈希兼容）
- `CampusUserDetailsServiceTest`：查库转 `UserDetails`、`ROLE_` 前缀、用户不存在抛异常
- `SessionServiceTest`：**会话按用户隔离**（6 例）

> JDK 25 下 Mockito 无法 mock 具体类，本项目改用「手写 fake 子类」的方式规避。

---

## 十、开发与运维

### 10.1 DevTools 热更新
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-devtools</artifactId>
  <optional>true</optional>
</dependency>
```
```yaml
spring:
  devtools:
    restart:
      enabled: true
      additional-paths: src/main/resources/static
    livereload:
      enabled: true
```
- **自动重启**：classpath 变化时快速重启（比冷启动快）。
- **LiveReload**：浏览器自动刷新（配合插件）。
- dev 还把静态资源指向源码目录，前端改动刷新即可，无需重启。
- `optional=true`：不会传递给依赖本项目的其他模块，也不打进生产包。

### 10.2 日志
```yaml
logging:
  level:
    com.campus.dialogue: info
```
Spring Boot 默认用 Logback；可按包设置级别，生产可配 `logging.file.name` 输出到文件。

### 10.3 打包与运行
```bash
mvn -DskipTests package                 # 生成可执行 fat jar
java -jar target/campus-dialogue-1.0.0.jar --server.port=8088
java -jar target/campus-dialogue-1.0.0.jar --spring.profiles.active=prod
```
- 命令行参数优先级最高，可覆盖 yml。
- `Dockerfile` 以 `eclipse-temurin-25` 为基础镜像，`ENTRYPOINT` 直接运行 jar。

### 10.4 常用 starter 之外的能力（本项目未用，可了解）
- `spring-boot-starter-actuator`：健康检查、指标、`/actuator/health`（上生产建议加）。
- `spring-boot-starter-security`：标准的安全框架。**本项目已使用**（`@EnableWebFluxSecurity` + `SecurityWebFilterChain`），
  采用原生表单登录 + Session + `authorizeExchange` 授权，扩展点只有 `ReactiveUserDetailsService` 与 `PasswordEncoder`。
- `spring-boot-starter-validation`：`@Valid` + `@NotNull` 等参数校验。

---

## 十一、知识点速查表

| 类别 | 关键词 | 本项目落点 |
|---|---|---|
| 构建 | starter-parent / starter / maven-plugin | `pom.xml` |
| 启动 | `@SpringBootApplication` / `CommandLineRunner` | `CampusDialogueApplication` |
| 配置 | yml / Profile / `@ConfigurationProperties` | `application*.yml`、`AppProperties` |
| Web | `@RestController` / `Mono` / `Flux` | `*Controller` |
| 流式 | SSE / `ServerSentEvent` | `ChatController` |
| 上传 | `FilePart` / `DataBufferUtils` | `DocController#upload` |
| 安全 | `@EnableWebFluxSecurity` / `SecurityWebFilterChain` / `authorizeExchange` / `formLogin` / `logout` / `ReactiveUserDetailsService` / `PasswordEncoder` / `@AuthenticationPrincipal` | `SecurityConfig`、`security/`、各 Controller |
| 数据 | `@Entity` / `JpaRepository` / 派生查询 | `entity/*`、`repository/*` |
| 事务 | `@Transactional` | `SessionService` |
| DDL | `ddl-auto` validate（dev/prod 一致） | `application.yml` |
| 迁移 | Flyway `V{n}__*.sql` | `db/migration` |
| 数据库 | MySQL（dev/prod 统一） | `application.yml` |
| 连接池 | HikariCP `maximum-pool-size` / `connection-timeout` | `application.yml` |
| 容器 | `@Component` / `@Bean` / 构造器注入 / 单例 | 全局 |
| HTTP | `WebClient` / Reactor Netty 超时 | `WebClientConfig`、`LlmClient` |
| 异步 | `Mono.fromCallable` / `boundedElastic` / `Semaphore` | Service 层 |
| 测试 | JUnit5 / Mockito / surefire `--add-opens` | `src/test`、`pom.xml` |
| 开发 | devtools 热重启 / LiveReload / 静态资源直读 | dev Profile |
| 部署 | fat jar / `java -jar` / Docker | `Dockerfile` |
