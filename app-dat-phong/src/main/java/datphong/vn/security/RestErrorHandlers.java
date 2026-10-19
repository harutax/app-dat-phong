package datphong.vn.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import datphong.vn.exception.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 401 = chưa xác thực; 403 = đã xác thực nhưng thiếu quyền - cùng định dạng lỗi JSON thống nhất. */
@Component
@RequiredArgsConstructor
public class RestErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper mapper;

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex) throws IOException {
        write(res, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Chưa xác thực hoặc phiên không còn hiệu lực");
    }

    @Override
    public void handle(HttpServletRequest req, HttpServletResponse res, AccessDeniedException ex) throws IOException {
        write(res, HttpStatus.FORBIDDEN, "FORBIDDEN_SCOPE", "Bạn không có quyền thực hiện thao tác này");
    }

    private void write(HttpServletResponse res, HttpStatus st, String code, String msg) throws IOException {
        res.setStatus(st.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
        mapper.writeValue(res.getWriter(), GlobalExceptionHandler.body(st, code, msg, null).getBody());
    }
}
