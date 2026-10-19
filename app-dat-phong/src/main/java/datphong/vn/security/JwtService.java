package datphong.vn.security;

import datphong.vn.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;

/**
 * Access JWT ngắn hạn. Chỉ chứa subject (userId), tv (token_version) và mốc cấp token.
 * KHÔNG đưa dữ liệu nhạy cảm; quyền thực tế đọc từ DB mỗi request nên role bị thu hồi có hiệu lực ngay,
 * và tv lệch (khóa tài khoản/đổi mật khẩu/đổi quyền) làm token cũ bị từ chối tức thì.
 */
@Service
public class JwtService {

    private final JwtProperties props;
    private final SecretKey key;
    private final Clock clock;

    public JwtService(JwtProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        byte[] bytes = Decoders.BASE64.decode(props.getSecret());
        if (bytes.length < 32) throw new IllegalStateException("JWT_SECRET phải là base64 của >= 32 bytes");
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    public String generateAccessToken(Long userId, int tokenVersion) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("tv", tokenVersion)
                .claim("typ", "access")
                .issuer(props.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(props.getAccessTokenExpiration())))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) throws JwtException {
        return Jwts.parser().verifyWith(key).requireIssuer(props.getIssuer()).build().parseSignedClaims(token).getPayload();
    }

    public long accessTtlSeconds() {
        return props.getAccessTokenExpiration() / 1000;
    }
}
