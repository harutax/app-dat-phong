package datphong.vn.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {
    private String secret;
    private long accessTokenExpiration = 900_000;
    private long refreshTokenExpiration = 604_800_000;
    private int maxActiveRefreshTokensPerUser = 5;
    private String issuer = "app-dat-phong";
}
