package io.kyligence.ragagent.core.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService Unit Tests")
class AuthServiceTest {

  @Mock
  private UserMapper userMapper;

  @Mock
  private WorkspaceMapper workspaceMapper;

  @Mock
  private WorkspaceMemberMapper workspaceMemberMapper;

  @Mock
  private JwtTokenProvider jwtTokenProvider;

  @Mock
  private PasswordEncoder passwordEncoder;

  @Captor
  private ArgumentCaptor<User> userCaptor;

  @Captor
  private ArgumentCaptor<Workspace> workspaceCaptor;

  @Captor
  private ArgumentCaptor<WorkspaceMember> memberCaptor;

  private AuthService authService;

  @BeforeEach
  void setUp() {
    authService = new AuthService(
        userMapper,
        workspaceMapper,
        workspaceMemberMapper,
        jwtTokenProvider,
        passwordEncoder);
  }

  @Nested
  @DisplayName("Register Tests")
  class RegisterTests {

    @Test
    @DisplayName("register creates user, workspace, and member with correct data")
    void register_withValidInput_createsUserWorkspaceAndMember() {
      String email = "alice@example.com";
      String password = "password123";
      String fullName = "Alice Smith";
      String hashedPassword = "hashed-password";

      when(userMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
      when(passwordEncoder.encode(password)).thenReturn(hashedPassword);
      when(jwtTokenProvider.generateAccessToken(anyString(), anyString(), any(Role.class)))
          .thenReturn("access-token");
      when(jwtTokenProvider.generateRefreshToken(anyString())).thenReturn("refresh-token");

      // Simulate insert operations setting IDs
      doAnswer(invocation -> {
        User user = invocation.getArgument(0);
        user.setId("user-123");
        return 1;
      }).when(userMapper).insert(any(User.class));

      doAnswer(invocation -> {
        Workspace ws = invocation.getArgument(0);
        ws.setId("ws-456");
        return 1;
      }).when(workspaceMapper).insert(any(Workspace.class));

      AuthService.AuthTokens tokens = authService.register(email, password, fullName);

      // Verify user creation
      verify(userMapper).insert(userCaptor.capture());
      User capturedUser = userCaptor.getValue();
      assertThat(capturedUser.getEmail()).isEqualTo(email);
      assertThat(capturedUser.getPasswordHash()).isEqualTo(hashedPassword);
      assertThat(capturedUser.getFullName()).isEqualTo(fullName);

      // Verify workspace creation
      verify(workspaceMapper).insert(workspaceCaptor.capture());
      Workspace capturedWorkspace = workspaceCaptor.getValue();
      assertThat(capturedWorkspace.getName()).isEqualTo(fullName + "'s Workspace");
      assertThat(capturedWorkspace.getOwnerId()).isEqualTo("user-123");

      // Verify member creation
      verify(workspaceMemberMapper).insert(memberCaptor.capture());
      WorkspaceMember capturedMember = memberCaptor.getValue();
      assertThat(capturedMember.getWorkspaceId()).isEqualTo("ws-456");
      assertThat(capturedMember.getUserId()).isEqualTo("user-123");
      assertThat(capturedMember.getRole()).isEqualTo(Role.OWNER);

      // Verify token generation
      verify(jwtTokenProvider).generateAccessToken("user-123", "ws-456", Role.OWNER);
      verify(jwtTokenProvider).generateRefreshToken("user-123");

      assertThat(tokens.accessToken()).isEqualTo("access-token");
      assertThat(tokens.refreshToken()).isEqualTo("refresh-token");
    }

    @Test
    @DisplayName("register throws UserAlreadyExistsException when email exists")
    void register_withExistingEmail_throwsException() {
      String email = "existing@example.com";
      when(userMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

      assertThatThrownBy(() -> authService.register(email, "password", "Name"))
          .isInstanceOf(AuthExceptions.UserAlreadyExistsException.class)
          .hasMessageContaining(email);

      verify(userMapper, never()).insert(any());
      verify(workspaceMapper, never()).insert(any());
      verify(workspaceMemberMapper, never()).insert(any());
    }

    @Test
    @DisplayName("register hashes password before storing")
    void register_hashesPasswordBeforeStorage() {
      String rawPassword = "plain-password";
      String hashedPassword = "hashed-version";

      when(userMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
      when(passwordEncoder.encode(rawPassword)).thenReturn(hashedPassword);
      when(jwtTokenProvider.generateAccessToken(anyString(), anyString(), any(Role.class)))
          .thenReturn("token");
      when(jwtTokenProvider.generateRefreshToken(anyString())).thenReturn("token");

      doAnswer(invocation -> {
        User user = invocation.getArgument(0);
        user.setId("user-1");
        return 1;
      }).when(userMapper).insert(any(User.class));

      doAnswer(invocation -> {
        Workspace ws = invocation.getArgument(0);
        ws.setId("ws-1");
        return 1;
      }).when(workspaceMapper).insert(any(Workspace.class));

      authService.register("test@example.com", rawPassword, "Test User");

      verify(passwordEncoder).encode(rawPassword);
      verify(userMapper).insert(argThat(user -> user.getPasswordHash().equals(hashedPassword)));
    }
  }

  @Nested
  @DisplayName("Login Tests")
  class LoginTests {

    @Test
    @DisplayName("login with valid credentials returns tokens")
    void login_withValidCredentials_returnsTokens() {
      String email = "bob@example.com";
      String password = "correct-password";
      User user = createUser("user-789", email, "hashed-password");
      WorkspaceMember member = createMember("ws-123", "user-789", Role.MEMBER);

      when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(user);
      when(passwordEncoder.matches(password, "hashed-password")).thenReturn(true);
      when(workspaceMemberMapper.selectOne(any(QueryWrapper.class))).thenReturn(member);
      when(jwtTokenProvider.generateAccessToken("user-789", "ws-123", Role.MEMBER))
          .thenReturn("access-token");
      when(jwtTokenProvider.generateRefreshToken("user-789")).thenReturn("refresh-token");

      AuthService.AuthTokens tokens = authService.login(email, password, null);

      assertThat(tokens.accessToken()).isEqualTo("access-token");
      assertThat(tokens.refreshToken()).isEqualTo("refresh-token");

      verify(jwtTokenProvider).generateAccessToken("user-789", "ws-123", Role.MEMBER);
      verify(jwtTokenProvider).generateRefreshToken("user-789");
    }

    @Test
    @DisplayName("login with specific workspace uses that workspace")
    void login_withSpecificWorkspace_usesSpecifiedWorkspace() {
      String email = "charlie@example.com";
      String password = "password";
      String workspaceId = "ws-specific";
      User user = createUser("user-111", email, "hashed-password");
      WorkspaceMember member = createMember(workspaceId, "user-111", Role.OWNER);

      when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(user);
      when(passwordEncoder.matches(password, "hashed-password")).thenReturn(true);
      when(workspaceMemberMapper.selectOne(any(QueryWrapper.class))).thenReturn(member);
      when(jwtTokenProvider.generateAccessToken("user-111", workspaceId, Role.OWNER))
          .thenReturn("access-token");
      when(jwtTokenProvider.generateRefreshToken("user-111")).thenReturn("refresh-token");

      AuthService.AuthTokens tokens = authService.login(email, password, workspaceId);

      assertThat(tokens).isNotNull();
      verify(jwtTokenProvider).generateAccessToken("user-111", workspaceId, Role.OWNER);
    }

    @Test
    @DisplayName("login throws InvalidCredentialsException for non-existent user")
    void login_withNonExistentUser_throwsInvalidCredentials() {
      when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

      assertThatThrownBy(() -> authService.login("no-user@example.com", "password", null))
          .isInstanceOf(AuthExceptions.InvalidCredentialsException.class);

      verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("login throws InvalidCredentialsException for wrong password")
    void login_withWrongPassword_throwsInvalidCredentials() {
      User user = createUser("user-1", "alice@example.com", "hashed-password");

      when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(user);
      when(passwordEncoder.matches("wrong-password", "hashed-password")).thenReturn(false);

      assertThatThrownBy(() -> authService.login("alice@example.com", "wrong-password", null))
          .isInstanceOf(AuthExceptions.InvalidCredentialsException.class);

      verify(workspaceMemberMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("login throws UnauthorizedException when user not in specified workspace")
    void login_whenUserNotInWorkspace_throwsUnauthorized() {
      String email = "dave@example.com";
      String password = "password";
      String workspaceId = "ws-not-member";
      User user = createUser("user-222", email, "hashed-password");

      when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(user);
      when(passwordEncoder.matches(password, "hashed-password")).thenReturn(true);
      when(workspaceMemberMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

      assertThatThrownBy(() -> authService.login(email, password, workspaceId))
          .isInstanceOf(AuthExceptions.UnauthorizedException.class)
          .hasMessageContaining("not a member of workspace");
    }

    @Test
    @DisplayName("login throws UnauthorizedException when user has no workspaces")
    void login_whenUserHasNoWorkspaces_throwsUnauthorized() {
      String email = "eve@example.com";
      String password = "password";
      User user = createUser("user-333", email, "hashed-password");

      when(userMapper.selectOne(any(QueryWrapper.class))).thenReturn(user);
      when(passwordEncoder.matches(password, "hashed-password")).thenReturn(true);
      when(workspaceMemberMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

      assertThatThrownBy(() -> authService.login(email, password, null))
          .isInstanceOf(AuthExceptions.UnauthorizedException.class)
          .hasMessageContaining("not a member of any workspace");
    }
  }

  @Nested
  @DisplayName("Refresh Tests")
  class RefreshTests {

    @Test
    @DisplayName("refresh with valid token returns new tokens")
    void refresh_withValidToken_returnsNewTokens() {
      String refreshToken = "valid-refresh-token";
      String userId = "user-555";
      User user = createUser(userId, "frank@example.com", "hash");
      WorkspaceMember member = createMember("ws-777", userId, Role.VIEWER);

      Claims claims = mock(Claims.class);
      when(jwtTokenProvider.validateRefreshToken(refreshToken)).thenReturn(claims);
      when(jwtTokenProvider.getUserId(claims)).thenReturn(userId);
      when(userMapper.selectById(userId)).thenReturn(user);
      when(workspaceMemberMapper.selectOne(any(QueryWrapper.class))).thenReturn(member);
      when(jwtTokenProvider.generateAccessToken(userId, "ws-777", Role.VIEWER))
          .thenReturn("new-access-token");
      when(jwtTokenProvider.generateRefreshToken(userId)).thenReturn("new-refresh-token");

      AuthService.AuthTokens tokens = authService.refresh(refreshToken);

      assertThat(tokens.accessToken()).isEqualTo("new-access-token");
      assertThat(tokens.refreshToken()).isEqualTo("new-refresh-token");

      verify(jwtTokenProvider).validateRefreshToken(refreshToken);
      verify(jwtTokenProvider).generateAccessToken(userId, "ws-777", Role.VIEWER);
      verify(jwtTokenProvider).generateRefreshToken(userId);
    }

    @Test
    @DisplayName("refresh throws UserNotFoundException when user not found")
    void refresh_whenUserNotFound_throwsUserNotFoundException() {
      String refreshToken = "valid-token";
      String userId = "non-existent-user";

      Claims claims = mock(Claims.class);
      when(jwtTokenProvider.validateRefreshToken(refreshToken)).thenReturn(claims);
      when(jwtTokenProvider.getUserId(claims)).thenReturn(userId);
      when(userMapper.selectById(userId)).thenReturn(null);

      assertThatThrownBy(() -> authService.refresh(refreshToken))
          .isInstanceOf(AuthExceptions.UserNotFoundException.class)
          .hasMessageContaining(userId);

      verify(workspaceMemberMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("refresh throws UnauthorizedException when user has no workspaces")
    void refresh_whenUserHasNoWorkspaces_throwsUnauthorized() {
      String refreshToken = "valid-token";
      String userId = "user-888";
      User user = createUser(userId, "george@example.com", "hash");

      Claims claims = mock(Claims.class);
      when(jwtTokenProvider.validateRefreshToken(refreshToken)).thenReturn(claims);
      when(jwtTokenProvider.getUserId(claims)).thenReturn(userId);
      when(userMapper.selectById(userId)).thenReturn(user);
      when(workspaceMemberMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

      assertThatThrownBy(() -> authService.refresh(refreshToken))
          .isInstanceOf(AuthExceptions.UnauthorizedException.class)
          .hasMessageContaining("not a member of any workspace");
    }

    @Test
    @DisplayName("refresh validates token before processing")
    void refresh_validatesTokenFirst() {
      String invalidToken = "invalid-token";

      when(jwtTokenProvider.validateRefreshToken(invalidToken))
          .thenThrow(new RuntimeException("Token validation failed"));

      assertThatThrownBy(() -> authService.refresh(invalidToken))
          .isInstanceOf(RuntimeException.class)
          .hasMessageContaining("Token validation failed");

      verify(userMapper, never()).selectById(anyString());
    }
  }

  // Helper methods
  private User createUser(String id, String email, String passwordHash) {
    User user = new User();
    user.setId(id);
    user.setEmail(email);
    user.setPasswordHash(passwordHash);
    user.setFullName("Test User");
    return user;
  }

  private WorkspaceMember createMember(String workspaceId, String userId, Role role) {
    WorkspaceMember member = new WorkspaceMember();
    member.setWorkspaceId(workspaceId);
    member.setUserId(userId);
    member.setRole(role);
    return member;
  }
}
