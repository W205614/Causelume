package io.causelume;

import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

class Problem extends RuntimeException {
    final int status;
    Problem(int status,String message) { super(message); this.status=status; }
    static String text(String value,int max,String field) {
        if (value == null || value.isBlank() || value.length()>max)
            throw new Problem(400, field+" 为必填项，最多 "+max+" 个字符");
        return value.strip();
    }
    static String choice(String value,Set<String> options,String field) {
        if (value == null || !options.contains(value)) throw new Problem(400,field+" 的取值无效");
        return value;
    }
}

@RestControllerAdvice
class Errors {
    @ExceptionHandler(Problem.class)
    ResponseEntity<?> problem(Problem p) { return ResponseEntity.status(p.status).body(Map.of("message",p.getMessage())); }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<?> duplicate() { return ResponseEntity.status(409).body(Map.of("message","记录已存在，请刷新后查看")); }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> malformed() { return ResponseEntity.badRequest().body(Map.of("message","请求格式或字段类型无效")); }
}
