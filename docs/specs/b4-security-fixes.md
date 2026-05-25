# B4 ModelGateway Security Fixes

## Overview
Fix 2 CRITICAL and 4 HIGH security issues found in code review.

## CRITICAL Issues

### 1. Replace AES/ECB with AES/GCM
**File:** `platform-core/src/main/java/io/kyligence/ragagent/core/model/ModelProviderServiceImpl.java`

**Current code (lines 96-110):**
```java
private String encryptApiKey(String plaintext) {
    Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
    // ... uses ECB mode
}
```

**Fix:** Replace with AES/GCM mode that includes IV in the output:

```java
private String encryptApiKey(String plaintext) {
    try {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[12]; // GCM standard IV size
        SecureRandom.getInstanceStrong().nextBytes(iv);
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        
        SecretKeySpec keySpec = new SecretKeySpec(
            encryptionKey.getBytes(StandardCharsets.UTF_8), "AES");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec);
        
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        
        // Combine IV + encrypted data
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        
        return Base64.getEncoder().encodeToString(combined);
    } catch (Exception e) {
        throw new PlatformException(ErrorCode.INTERNAL_ERROR, 
            "Failed to encrypt API key", e);
    }
}

private String decryptApiKey(String ciphertext) {
    try {
        byte[] combined = Base64.getDecoder().decode(ciphertext);
        
        // Extract IV and encrypted data
        byte[] iv = new byte[12];
        byte[] encrypted = new byte[combined.length - 12];
        System.arraycopy(combined, 0, iv, 0, 12);
        System.arraycopy(combined, 12, encrypted, 0, encrypted.length);
        
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        SecretKeySpec keySpec = new SecretKeySpec(
            encryptionKey.getBytes(StandardCharsets.UTF_8), "AES");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);
        
        byte[] decrypted = cipher.doFinal(encrypted);
        return new String(decrypted, StandardCharsets.UTF_8);
    } catch (Exception e) {
        throw new PlatformException(ErrorCode.INTERNAL_ERROR, 
            "Failed to decrypt API key", e);
    }
}
```

**Test update:** Update `ModelProviderServiceImplTest.encryptDecryptRoundTrip()` to verify the new encryption still works.

---

### 2. Exclude encrypted API keys from API responses
**File:** `server/src/main/java/io/kyligence/ragagent/server/controller/ModelProviderController.java`

**Current code (lines 32-54):** Returns `ModelProvider` entity directly, exposing `apiKeyEncrypted` field.

**Fix:** Create DTO and use it in all controller methods:

```java
// Add this record to ModelProviderController
public record ProviderResponse(
    String id,
    String name,
    String type,
    String baseUrl,
    String modelsJson,
    Boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    static ProviderResponse from(ModelProvider p) {
        return new ProviderResponse(
            p.getId(), p.getName(), p.getType(), p.getBaseUrl(),
            p.getModelsJson(), p.getEnabled(), 
            p.getCreatedAt(), p.getUpdatedAt()
        );
    }
}

// Update all methods to return ProviderResponse
@PostMapping
public ApiResponse<ProviderResponse> create(@Valid @RequestBody CreateRequest req) {
    ModelProvider provider = service.create(new ModelProviderService.CreateProviderCommand(
        req.name(), req.type(), req.baseUrl(), req.apiKey(), req.modelsJson()));
    return ApiResponse.ok(ProviderResponse.from(provider));
}

@GetMapping("/{id}")
public ApiResponse<ProviderResponse> getById(@PathVariable String id) {
    return ApiResponse.ok(ProviderResponse.from(service.getById(id)));
}

@GetMapping
public ApiResponse<List<ProviderResponse>> list() {
    return ApiResponse.ok(service.list().stream()
        .map(ProviderResponse::from)
        .toList());
}

@PutMapping("/{id}")
public ApiResponse<ProviderResponse> update(
    @PathVariable String id, 
    @Valid @RequestBody UpdateRequest req) {
    ModelProvider updated = service.update(id, 
        new ModelProviderService.UpdateProviderCommand(
            req.name(), req.baseUrl(), req.apiKey(), req.modelsJson(), req.enabled()));
    return ApiResponse.ok(ProviderResponse.from(updated));
}
```

---

## HIGH Issues

### 3. Use configured timeout instead of hardcoded value
**File:** `platform-core/src/main/java/io/kyligence/ragagent/core/model/OpenAiCompatClient.java`

**Current code (line 27):** `private static final Duration TIMEOUT = Duration.ofSeconds(120);`

**Fix:** Inject `ModelGatewayProperties` and use configured timeout:

```java
@Component
@RequiredArgsConstructor
public class OpenAiCompatClient implements ProviderClient {
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final ModelGatewayProperties properties;  // Add this field

    // Remove: private static final Duration TIMEOUT = Duration.ofSeconds(120);

    // Add helper method
    private Duration getTimeout() {
        return Duration.ofSeconds(properties.getTimeoutSeconds());
    }

    // Update all .timeout(TIMEOUT) calls to .timeout(getTimeout())
    // Lines to update: 44, 66, 88, 103
}
```

---

### 4. Add baseUrl validation to prevent SSRF
**File:** `platform-core/src/main/java/io/kyligence/ragagent/core/model/ModelProviderServiceImpl.java`

**Fix:** Add validation method and call it in create() and update():

```java
private void validateBaseUrl(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
        throw new PlatformException(ErrorCode.INVALID_REQUEST, "Base URL is required");
    }
    try {
        URI uri = new URI(baseUrl);
        String scheme = uri.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, 
                "Base URL must use http or https protocol");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "Invalid base URL");
        }
    } catch (URISyntaxException e) {
        throw new PlatformException(ErrorCode.INVALID_REQUEST, 
            "Invalid base URL format: " + e.getMessage());
    }
}

// Call in create() method (line 35, before encryption)
validateBaseUrl(cmd.baseUrl());

// Call in update() method (line 52, before encryption if baseUrl provided)
if (cmd.baseUrl() != null) {
    validateBaseUrl(cmd.baseUrl());
}
```

---

### 5. Return immutable lists from parsing methods
**File:** `platform-core/src/main/java/io/kyligence/ragagent/core/model/OpenAiCompatClient.java`

**Fix:** Wrap mutable lists with `List.copyOf()`:

```java
// Line 128 - parseChatResponse
return new ChatResponse(
    model, content, role, 
    toolCalls.isEmpty() ? null : List.copyOf(toolCalls),  // Make immutable
    u
);

// Line 156 - parseEmbeddingResponse
return new EmbeddingResponse(model, List.copyOf(embeddings), tokens);  // Make immutable

// Line 168 - parseRerankResponse
return new RerankResponse(req.model(), List.copyOf(results));  // Make immutable
```

---

### 6. Add @Size validation to controller inputs
**File:** `server/src/main/java/io/kyligence/ragagent/server/controller/ModelProviderController.java`

**Fix:** Add size constraints to request records:

```java
public record CreateRequest(
    @NotBlank @Size(max = 64) String name,
    @NotBlank @Size(max = 32) String type,
    @NotBlank @Size(max = 512) String baseUrl,
    @NotBlank @Size(max = 256) String apiKey,
    @Size(max = 10000) String modelsJson
) {}

public record UpdateRequest(
    @Size(max = 64) String name,
    @Size(max = 512) String baseUrl,
    @Size(max = 256) String apiKey,
    @Size(max = 10000) String modelsJson,
    Boolean enabled
) {}
```

---

## Implementation Requirements

1. **Follow TDD:** Write tests first for each fix, watch them fail, then implement
2. **Run existing tests:** Ensure all existing tests still pass after changes
3. **Add new tests:**
   - Test AES/GCM encryption/decryption round-trip
   - Test that API responses don't contain apiKeyEncrypted
   - Test baseUrl validation (valid/invalid URLs, SSRF attempts)
   - Test timeout configuration is used
4. **Verify compilation:** Run `mvn compile -pl platform-core,server`
5. **Run all tests:** Run `mvn test -pl platform-core,server`

---

## Acceptance Criteria

- [ ] AES/GCM encryption implemented with IV
- [ ] API responses exclude apiKeyEncrypted field
- [ ] Configured timeout used instead of hardcoded value
- [ ] BaseUrl validation prevents SSRF
- [ ] Immutable lists returned from parsing methods
- [ ] Size validation added to controller inputs
- [ ] All existing tests pass
- [ ] New tests added for security fixes
- [ ] Compilation successful
