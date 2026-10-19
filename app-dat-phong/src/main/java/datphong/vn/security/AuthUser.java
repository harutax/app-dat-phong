package datphong.vn.security;

import datphong.vn.constant.UserStatus;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Principal đã xác thực. Quyền = ROLE_xxx + permission code (kiểm tra bằng hasAuthority). */
@Getter
public class AuthUser implements UserDetails {

    private final Long id;
    private final String email;
    private final String fullName;
    private final UserStatus status;
    private final Set<String> roles;
    private final Set<String> permissions;
    private final List<GrantedAuthority> authorities;

    public AuthUser(Long id, String email, String fullName, UserStatus status, Set<String> roles, Set<String> permissions) {
        this.id = id;
        this.email = email;
        this.fullName = fullName;
        this.status = status;
        this.roles = roles;
        this.permissions = permissions;
        this.authorities = new java.util.ArrayList<>();
        roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean hasPermission(String perm) {
        return permissions.contains(perm);
    }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String getPassword() { return null; }
    @Override public String getUsername() { return String.valueOf(id); }
    @Override public boolean isEnabled() { return status == UserStatus.ACTIVE; }
}
