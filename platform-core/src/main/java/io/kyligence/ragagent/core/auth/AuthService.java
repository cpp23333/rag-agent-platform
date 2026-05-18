package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    /**
     * 用户注册
     *
     * @return 包含 accessToken 和 refreshToken 的响应
     */
    @Transactional
    public AuthTokens register(String email, String password, String fullName) {
        // 检查用户是否已存在
        QueryWrapper<User> query = new QueryWrapper<>();
        query.eq("email", email);
        if (userMapper.selectCount(query) > 0) {
            throw new AuthExceptions.UserAlreadyExistsException(email);
        }

        // 创建用户
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setFullName(fullName);
        userMapper.insert(user);

        // 创建默认 workspace（以用户名命名）
        Workspace workspace = new Workspace();
        workspace.setName(fullName + "'s Workspace");
        workspace.setOwnerId(user.getId());
        workspaceMapper.insert(workspace);

        // 将用户加入 workspace（OWNER 角色）
        WorkspaceMember member = new WorkspaceMember();
        member.setWorkspaceId(workspace.getId());
        member.setUserId(user.getId());
        member.setRole(Role.OWNER);
        workspaceMemberMapper.insert(member);

        log.info("User registered: email={}, userId={}, workspaceId={}",
                email, user.getId(), workspace.getId());

        // 生成 token
        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), workspace.getId(), Role.OWNER);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        return new AuthTokens(accessToken, refreshToken);
    }

    /**
     * 用户登录
     *
     * @param workspaceId 可选，如果为 null 则使用用户第一个 workspace
     */
    @Transactional(readOnly = true)
    public AuthTokens login(String email, String password, String workspaceId) {
        // 查找用户
        QueryWrapper<User> userQuery = new QueryWrapper<>();
        userQuery.eq("email", email);
        User user = userMapper.selectOne(userQuery);

        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new AuthExceptions.InvalidCredentialsException();
        }

        // 确定 workspace
        WorkspaceMember member;
        if (workspaceId != null) {
            // 使用指定的 workspace
            QueryWrapper<WorkspaceMember> memberQuery = new QueryWrapper<>();
            memberQuery.eq("user_id", user.getId())
                    .eq("workspace_id", workspaceId);
            member = workspaceMemberMapper.selectOne(memberQuery);

            if (member == null) {
                throw new AuthExceptions.UnauthorizedException(
                        "User is not a member of workspace: " + workspaceId);
            }
        } else {
            // 使用第一个 workspace
            QueryWrapper<WorkspaceMember> memberQuery = new QueryWrapper<>();
            memberQuery.eq("user_id", user.getId()).last("LIMIT 1");
            member = workspaceMemberMapper.selectOne(memberQuery);

            if (member == null) {
                throw new AuthExceptions.UnauthorizedException(
                        "User is not a member of any workspace");
            }
        }

        log.info("User logged in: email={}, userId={}, workspaceId={}",
                email, user.getId(), member.getWorkspaceId());

        // 生成 token
        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), member.getWorkspaceId(), member.getRole());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        return new AuthTokens(accessToken, refreshToken);
    }

    /**
     * 刷新 token
     */
    @Transactional(readOnly = true)
    public AuthTokens refresh(String refreshToken) {
        // 验证 refresh token
        Claims claims = jwtTokenProvider.validateRefreshToken(refreshToken);
        String userId = jwtTokenProvider.getUserId(claims);

        // 查找用户
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new AuthExceptions.UserNotFoundException(userId);
        }

        // 使用第一个 workspace（刷新时不改变 workspace）
        QueryWrapper<WorkspaceMember> memberQuery = new QueryWrapper<>();
        memberQuery.eq("user_id", userId).last("LIMIT 1");
        WorkspaceMember member = workspaceMemberMapper.selectOne(memberQuery);

        if (member == null) {
            throw new AuthExceptions.UnauthorizedException(
                    "User is not a member of any workspace");
        }

        log.info("Token refreshed: userId={}, workspaceId={}",
                userId, member.getWorkspaceId());

        // 生成新的 token
        String newAccessToken = jwtTokenProvider.generateAccessToken(
                userId, member.getWorkspaceId(), member.getRole());
        String newRefreshToken = jwtTokenProvider.generateRefreshToken(userId);

        return new AuthTokens(newAccessToken, newRefreshToken);
    }

    /**
     * 认证结果（包含 access token 和 refresh token）
     */
    public record AuthTokens(String accessToken, String refreshToken) {
    }
}
