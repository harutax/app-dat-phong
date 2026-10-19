package datphong.vn.service;

import datphong.vn.exception.BusinessException;
import datphong.vn.exception.ErrorCode;
import datphong.vn.util.Texts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Chính sách mật khẩu động (UC-30). Độ dài đo theo Unicode code points; áp dụng ở đăng ký / đổi mật khẩu từ effective_at. */
@Service
@RequiredArgsConstructor
public class PasswordPolicy {

    private final ConfigService config;

    public void validate(String password) {
        int min = config.getInt("auth.password.minLength");
        int max = config.getInt("auth.password.maxLength");
        int len = Texts.length(password);
        if (len < min || len > max) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Mật khẩu phải dài từ " + min + " đến " + max + " ký tự");
        }
        boolean upper = password.chars().anyMatch(Character::isUpperCase);
        boolean lower = password.chars().anyMatch(Character::isLowerCase);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        if (!(upper && lower && digit)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Mật khẩu cần có chữ hoa, chữ thường và chữ số");
        }
    }
}
