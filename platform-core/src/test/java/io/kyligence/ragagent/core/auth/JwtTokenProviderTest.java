package io.kyligence.ragagent.core.auth;

import io.jsonwebtoken.Claims;
import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.*;

@DisplayName("JwtTokenProvider Unit Tests")
class JwtTokenProviderTest {

  private JwtTokenProvider tokenProvider;

  private static final String ACCESS_SECRET = "test-access-secret-that-is-at-least-32-bytes-long";
  private static final String REFRESH_SECRET = "test-refresh-secret-that-is-at-least-32-bytes-long";
  private static final long ACCESS_VALIDITY_MINUTES = 60;
  private static final long REFRESH_VALIDITY_DAYS = 30;

  @BeforeEach
  void setUp() {
    tokenProvider = new JwtTokenProvider(
        ACCESS_SECRET,
        REFRESH_SECRET,
        ACCESS_VALIDITY_MINUTES,
        REFRESH_VALIDITY_DAYS);
  }

  @Nested
  @DisplayName("Access Token Tests")
  class AccessTokenTests {

    @Test
    @DisplayName("generateAccessToken creates valid token with all claims")
    void generateAccessToken_withValidInputs_createsTokenWithClaims() {
      String userId = "user-123";
      String workspaceId = "ws-456";
      Role role = Role.OWNER;

      String token = tokenProvider.generateAccessToken(userId, workspaceId, role);

      assertThat(token).isNotNull().isNotEmpty();

      Claims claims = tokenProvider.validateAccessToken(token);
      assertThat(tokenProvider.getUserId(claims)).isEqualTo(userId);
      assertThat(tokenProvider.getWorkspaceId(claims)).isEqualTo(workspaceId);
      assertThat(tokenProvider.getRole(claims)).isEqualTo(role);
    }

    @Test
    @DisplayName("validateAccessToken succeeds for valid token")
    void validateAccessToken_withValidToken_returnsClaims() {
      String token = tokenProvider.generateAccessToken("user-1", "ws-1", Role.MEMBER);

      Claims claims = tokenProvider.validateAccessToken(token);

      assertThat(claims).isNotNull();
      assertThat(claims.getSubject()).isEqualTo("user-1");
      assertThat(claims.get("workspaceId", String.class)).isEqualTo("ws-1");
      assertThat(claims.get("role", String.class)).isEqualTo("MEMBER");
    }

    @Test
    @DisplayName("validateAccessToken throws TOKEN_INVALID for malformed token")
    void validateAccessToken_withMalformedToken_throwsTokenInvalid() {
      String malformedToken = "invalid.token.here";

      assertThatThrownBy(() -> tokenProvider.validateAccessToken(malformedToken))
          .isInstanceOf(PlatformException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_INVALID)
          .hasMessageContaining("Invalid access token");
    }

    @Test
    @DisplayName("validateAccessToken throws TOKEN_INVALID for token signed with wrong secret")
    void validateAccessToken_withWrongSecret_throwsTokenInvalid() {
      JwtTokenProvider wrongProvider = new JwtTokenProvider(
          "different-secret-that-is-at-least-32-bytes-long",
          REFRESH_SECRET,
          ACCESS_VALIDITY_MINUTES,
          REFRESH_VALIDITY_DAYS);
      String token = wrongProvider.generateAccessToken("user-1", "ws-1", Role.MEMBER);

      assertThatThrownBy(() -> tokenProvider.validateAccessToken(token))
          .isInstanceOf(PlatformException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("validateAccessToken throws TOKEN_EXPIRED for expired token")
    void validateAccessToken_withExpiredToken_throwsTokenExpired() {
      JwtTokenProvider shortLivedProvider = new JwtTokenProvider(
          ACCESS_SECRET,
          REFRESH_SECRET,
          0,  // 0 minutes validity
          REFRESH_VALIDITY_DAYS);
      String token = shortLivedProvider.generateAccessToken("user-1", "ws-1", Role.MEMBER);

      // Wait a bit to ensure expiry
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      assertThatThrownBy(() -> tokenProvider.validateAccessToken(token))
          .isInstanceOf(PlatformException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_EXPIRED)
          .hasMessageContaining("Access token expired");
    }

    @Test
    @DisplayName("access token contains correct expiry time")
    void generateAccessToken_setsCorrectExpiry() {
      Instant before = Instant.now();
      String token = tokenProvider.generateAccessToken("user-1", "ws-1", Role.OWNER);
      Instant after = Instant.now();

      Claims claims = tokenProvider.validateAccessToken(token);
      Instant expiry = claims.getExpiration().toInstant();

      // Expiry should be approximately ACCESS_VALIDITY_MINUTES from now
      Instant expectedExpiryMin = before.plus(ACCESS_VALIDITY_MINUTES, ChronoUnit.MINUTES);
      Instant expectedExpiryMax = after.plus(ACCESS_VALIDITY_MINUTES, ChronoUnit.MINUTES);

      assertThat(expiry).isBetween(expectedExpiryMin, expectedExpiryMax);
    }
  }

  @Nested
  @DisplayName("Refresh Token Tests")
  class RefreshTokenTests {

    @Test
    @DisplayName("generateRefreshToken creates valid token")
    void generateRefreshToken_withValidUserId_createsToken() {
      String userId = "user-789";

      String token = tokenProvider.generateRefreshToken(userId);

      assertThat(token).isNotNull().isNotEmpty();

      Claims claims = tokenProvider.validateRefreshToken(token);
      assertThat(tokenProvider.getUserId(claims)).isEqualTo(userId);
    }

    @Test
    @DisplayName("validateRefreshToken succeeds for valid token")
    void validateRefreshToken_withValidToken_returnsClaims() {
      String token = tokenProvider.generateRefreshToken("user-1");

      Claims claims = tokenProvider.validateRefreshToken(token);

      assertThat(claims).isNotNull();
      assertThat(claims.getSubject()).isEqualTo("user-1");
    }

    @Test
    @DisplayName("validateRefreshToken throws TOKEN_INVALID for malformed token")
    void validateRefreshToken_withMalformedToken_throwsTokenInvalid() {
      String malformedToken = "totally.invalid.token";

      assertThatThrownBy(() -> tokenProvider.validateRefreshToken(malformedToken))
          .isInstanceOf(PlatformException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_INVALID)
          .hasMessageContaining("Invalid refresh token");
    }

    @Test
    @DisplayName("validateRefreshToken throws TOKEN_EXPIRED for expired token")
    void validateRefreshToken_withExpiredToken_throwsTokenExpired() {
      JwtTokenProvider shortLivedProvider = new JwtTokenProvider(
          ACCESS_SECRET,
          REFRESH_SECRET,
          ACCESS_VALIDITY_MINUTES,
          0);  // 0 days validity
      String token = shortLivedProvider.generateRefreshToken("user-1");

      // Wait a bit to ensure expiry
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      assertThatThrownBy(() -> tokenProvider.validateRefreshToken(token))
          .isInstanceOf(PlatformException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_EXPIRED)
          .hasMessageContaining("Refresh token expired");
    }

    @Test
    @DisplayName("refresh token does not contain workspace or role claims")
    void generateRefreshToken_doesNotIncludeWorkspaceOrRole() {
      String token = tokenProvider.generateRefreshToken("user-1");

      Claims claims = tokenProvider.validateRefreshToken(token);

      assertThat(claims.get("workspaceId")).isNull();
      assertThat(claims.get("role")).isNull();
    }

    @Test
    @DisplayName("refresh token contains correct expiry time")
    void generateRefreshToken_setsCorrectExpiry() {
      Instant before = Instant.now();
      String token = tokenProvider.generateRefreshToken("user-1");
      Instant after = Instant.now();

      Claims claims = tokenProvider.validateRefreshToken(token);
      Instant expiry = claims.getExpiration().toInstant();

      // Expiry should be approximately REFRESH_VALIDITY_DAYS from now
      Instant expectedExpiryMin = before.plus(REFRESH_VALIDITY_DAYS, ChronoUnit.DAYS);
      Instant expectedExpiryMax = after.plus(REFRESH_VALIDITY_DAYS, ChronoUnit.DAYS);

      assertThat(expiry).isBetween(expectedExpiryMin, expectedExpiryMax);
    }
  }

  @Nested
  @DisplayName("Claims Extraction Tests")
  class ClaimsExtractionTests {

    @Test
    @DisplayName("getUserId extracts subject from claims")
    void getUserId_fromValidClaims_returnsSubject() {
      String token = tokenProvider.generateAccessToken("user-123", "ws-456", Role.OWNER);
      Claims claims = tokenProvider.validateAccessToken(token);

      String userId = tokenProvider.getUserId(claims);

      assertThat(userId).isEqualTo("user-123");
    }

    @Test
    @DisplayName("getWorkspaceId extracts workspaceId claim")
    void getWorkspaceId_fromValidClaims_returnsWorkspaceId() {
      String token = tokenProvider.generateAccessToken("user-123", "ws-789", Role.MEMBER);
      Claims claims = tokenProvider.validateAccessToken(token);

      String workspaceId = tokenProvider.getWorkspaceId(claims);

      assertThat(workspaceId).isEqualTo("ws-789");
    }

    @Test
    @DisplayName("getRole extracts and parses role claim")
    void getRole_fromValidClaims_returnsRole() {
      String token = tokenProvider.generateAccessToken("user-123", "ws-456", Role.VIEWER);
      Claims claims = tokenProvider.validateAccessToken(token);

      Role role = tokenProvider.getRole(claims);

      assertThat(role).isEqualTo(Role.VIEWER);
    }

    @Test
    @DisplayName("all role types can be extracted correctly")
    void getRole_forAllRoleTypes_extractsCorrectly() {
      for (Role role : Role.values()) {
        String token = tokenProvider.generateAccessToken("user-1", "ws-1", role);
        Claims claims = tokenProvider.validateAccessToken(token);

        Role extractedRole = tokenProvider.getRole(claims);

        assertThat(extractedRole).isEqualTo(role);
      }
    }
  }

  @Nested
  @DisplayName("Token Independence Tests")
  class TokenIndependenceTests {

    @Test
    @DisplayName("access token cannot be validated as refresh token")
    void accessToken_cannotBeValidatedAsRefreshToken() {
      String accessToken = tokenProvider.generateAccessToken("user-1", "ws-1", Role.OWNER);

      assertThatThrownBy(() -> tokenProvider.validateRefreshToken(accessToken))
          .isInstanceOf(PlatformException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_INVALID);
    }

    @Test
    @DisplayName("refresh token cannot be validated as access token")
    void refreshToken_cannotBeValidatedAsAccessToken() {
      String refreshToken = tokenProvider.generateRefreshToken("user-1");

      assertThatThrownBy(() -> tokenProvider.validateAccessToken(refreshToken))
          .isInstanceOf(PlatformException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TOKEN_INVALID);
    }
  }
}
