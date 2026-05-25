package io.kyligence.ragagent.core.model;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.kyligence.ragagent.shared.exception.ErrorCode;
import io.kyligence.ragagent.shared.exception.PlatformException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelProviderServiceImpl implements ModelProviderService {

    private final ModelProviderMapper mapper;

    @Value("${ragagent.model-gateway.encryption-key:default-key-change-in-prod!}")
    private String encryptionKey;

    @Override
    @Transactional
    public ModelProvider create(Long workspaceId, CreateProviderCommand cmd) {
        validateBaseUrl(cmd.baseUrl());
        ModelProvider p = new ModelProvider();
        p.setWorkspaceId(workspaceId);
        p.setName(cmd.name());
        p.setType(cmd.type());
        p.setBaseUrl(cmd.baseUrl());
        p.setApiKeyEncrypted(encryptApiKey(cmd.apiKey()));
        p.setModelsJson(cmd.modelsJson());
        p.setEnabled(true);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        mapper.insert(p);
        return p;
    }

    @Override
    @Transactional
    public ModelProvider update(String id, UpdateProviderCommand cmd) {
        ModelProvider p = mapper.selectById(id);
        if (p == null)
            throw new PlatformException(
                    ErrorCode.MODEL_PROVIDER_NOT_FOUND, "Provider not found: " + id);
        if (cmd.baseUrl() != null) {
            validateBaseUrl(cmd.baseUrl());
            p.setBaseUrl(cmd.baseUrl());
        }
        if (cmd.apiKey() != null) p.setApiKeyEncrypted(encryptApiKey(cmd.apiKey()));
        if (cmd.modelsJson() != null) p.setModelsJson(cmd.modelsJson());
        if (cmd.enabled() != null) p.setEnabled(cmd.enabled());
        p.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(p);
        return p;
    }

    @Override
    @Transactional(readOnly = true)
    public ModelProvider getByName(String name) {
        return mapper.selectOne(
                Wrappers.<ModelProvider>lambdaQuery()
                        .eq(ModelProvider::getName, name)
                        .eq(ModelProvider::getEnabled, true));
    }

    @Override
    @Transactional(readOnly = true)
    public ModelProvider getById(String id) {
        return mapper.selectById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModelProvider> list(Long workspaceId) {
        return mapper.selectList(
                Wrappers.<ModelProvider>lambdaQuery()
                        .eq(ModelProvider::getWorkspaceId, workspaceId)
                        .orderByAsc(ModelProvider::getName));
    }

    @Override
    @Transactional
    public void delete(String id) {
        mapper.deleteById(id);
    }

    @Override
    public String decryptApiKey(String encrypted) {
        try {
            byte[] combined = Base64.getDecoder().decode(encrypted);

            // Extract IV and encrypted data
            byte[] iv = new byte[12];
            byte[] encryptedData = new byte[combined.length - 12];
            System.arraycopy(combined, 0, iv, 0, 12);
            System.arraycopy(combined, 12, encryptedData, 0, encryptedData.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            byte[] keyBytes = padKey(encryptionKey);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec);

            byte[] decrypted = cipher.doFinal(encryptedData);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new PlatformException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "Failed to decrypt API key", e);
        }
    }

    private String encryptApiKey(String plaintext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[12]; // GCM standard IV size
            SecureRandom.getInstanceStrong().nextBytes(iv);
            GCMParameterSpec spec = new GCMParameterSpec(128, iv);

            byte[] keyBytes = padKey(encryptionKey);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec);

            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Combine IV + encrypted data
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new PlatformException(ErrorCode.INTERNAL_SERVER_ERROR,
                "Failed to encrypt API key", e);
        }
    }

    private byte[] padKey(String key) {
        byte[] raw = key.getBytes(StandardCharsets.UTF_8);
        byte[] padded = new byte[32];
        System.arraycopy(raw, 0, padded, 0, Math.min(raw.length, 32));
        return padded;
    }

    private void validateBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new PlatformException(ErrorCode.INVALID_REQUEST, "Base URL is required");
        }
        try {
            URI uri = new URI(baseUrl);
            String scheme = uri.getScheme();
            if (!"https".equals(scheme) && !"http".equals(scheme)) {
                throw new PlatformException(
                        ErrorCode.INVALID_REQUEST, "Base URL must use http or https protocol");
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new PlatformException(ErrorCode.INVALID_REQUEST, "Invalid base URL");
            }
        } catch (URISyntaxException e) {
            throw new PlatformException(
                    ErrorCode.INVALID_REQUEST, "Invalid base URL format: " + e.getMessage());
        }
    }
}
