package com.mieai.qqbot.onebot11;

import com.mieai.qqbot.runtime.configuration.BotNotFoundException;
import com.mieai.qqbot.onebot11.config.OneBot11RevisionConflictException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = OneBot11SettingsController.class)
public class OneBot11ExceptionHandler {
    @ExceptionHandler(OneBot11RevisionConflictException.class)
    ResponseEntity<Map<String, String>> revisionConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "REVISION_CONFLICT",
                        "message", "OneBot configuration revision changed"));
    }

    @ExceptionHandler(BotNotFoundException.class)
    ResponseEntity<Map<String, String>> botNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "BOT_NOT_FOUND", "message", "Bot was not found"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", "INVALID_ONEBOT_SETTINGS",
                "message", exception.getMessage() == null
                        ? "OneBot settings are invalid" : exception.getMessage()));
    }
}
