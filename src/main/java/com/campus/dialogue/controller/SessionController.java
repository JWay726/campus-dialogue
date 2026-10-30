package com.campus.dialogue.controller;

import com.campus.dialogue.model.Session;
import com.campus.dialogue.service.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 当前登录用户新建一个归属自己的会话。 */
    @PostMapping
    public Session create(@AuthenticationPrincipal(expression = "username") String me) {
        return sessionService.createSession(me);
    }

    /** 仅返回当前登录用户自己的会话。 */
    @GetMapping
    public List<Session> list(@AuthenticationPrincipal(expression = "username") String me) {
        return sessionService.listSessions(me);
    }

    /** 查看会话详情；不属于当前用户时返回 404（看不到别人的对话）。 */
    @GetMapping("/{id}/messages")
    public ResponseEntity<Session> detail(@PathVariable Long id, @AuthenticationPrincipal(expression = "username") String me) {
        Session s = sessionService.get(id, me);
        return s == null ? ResponseEntity.status(HttpStatus.NOT_FOUND).build() : ResponseEntity.ok(s);
    }

    /** 删除会话（仅当归属当前用户）。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id,
                                                      @AuthenticationPrincipal(expression = "username") String me) {
        Session before = sessionService.get(id, me);
        if (before == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        sessionService.delete(id, me);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    /** 重命名会话（仅当归属当前用户）。 */
    @PatchMapping("/{id}")
    public ResponseEntity<Map<String, Object>> rename(@PathVariable Long id,
                                                      @RequestBody Map<String, String> body,
                                                      @AuthenticationPrincipal(expression = "username") String me) {
        if (sessionService.get(id, me) == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String title = body.getOrDefault("title", "");
        sessionService.rename(id, me, title);
        return ResponseEntity.ok(Map.of("renamed", id, "title", title));
    }

    /** 删除某条消息及其之后所有消息（用于“修改历史消息并重新提问”），仅当会话归属当前用户。 */
    @DeleteMapping("/{id}/messages")
    public ResponseEntity<Map<String, Object>> deleteMessagesFrom(@PathVariable Long id,
                                                                  @RequestParam long from,
                                                                  @AuthenticationPrincipal(expression = "username") String me) {
        if (sessionService.get(id, me) == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        sessionService.deleteMessagesFrom(id, me, from);
        return ResponseEntity.ok(Map.of("deletedFrom", from));
    }
}
