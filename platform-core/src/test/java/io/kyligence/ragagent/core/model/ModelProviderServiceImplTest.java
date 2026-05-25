package io.kyligence.ragagent.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.kyligence.ragagent.shared.exception.PlatformException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ModelProviderServiceImplTest {

    @Mock private ModelProviderMapper mapper;
    private ModelProviderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ModelProviderServiceImpl(mapper);
        ReflectionTestUtils.setField(service, "encryptionKey", "test-encryption-key-32-chars!!");
    }

    @Test
    void createInsertsProvider() {
        when(mapper.insert(any())).thenReturn(1);
        ModelProvider result =
                service.create(
                        1L,
                        new ModelProviderService.CreateProviderCommand(
                                "openai",
                                "openai",
                                "https://api.openai.com/v1",
                                "sk-test",
                                "{\"chat\":[\"gpt-4o\"]}"));
        assertThat(result.getName()).isEqualTo("openai");
        assertThat(result.getApiKeyEncrypted()).isNotEqualTo("sk-test");
        verify(mapper).insert(any());
    }

    @Test
    void encryptDecryptRoundTrip() {
        String original = "sk-my-secret-key-12345";
        ReflectionTestUtils.setField(service, "encryptionKey", "test-encryption-key-32-chars!!");
        String encrypted =
                (String) ReflectionTestUtils.invokeMethod(service, "encryptApiKey", original);
        String decrypted = service.decryptApiKey(encrypted);
        assertThat(decrypted).isEqualTo(original);
    }

    @Test
    void encryptionUsesGcmMode() {
        String original = "sk-test-key";
        ReflectionTestUtils.setField(service, "encryptionKey", "test-encryption-key-32-chars!!");
        String encrypted1 =
                (String) ReflectionTestUtils.invokeMethod(service, "encryptApiKey", original);
        String encrypted2 =
                (String) ReflectionTestUtils.invokeMethod(service, "encryptApiKey", original);
        // GCM with random IV should produce different ciphertexts
        assertThat(encrypted1).isNotEqualTo(encrypted2);
        // But both should decrypt to the same plaintext
        assertThat(service.decryptApiKey(encrypted1)).isEqualTo(original);
        assertThat(service.decryptApiKey(encrypted2)).isEqualTo(original);
    }

    @Test
    void updateThrowsWhenNotFound() {
        when(mapper.selectById("missing")).thenReturn(null);
        assertThatThrownBy(
                        () ->
                                service.update(
                                        "missing",
                                        new ModelProviderService.UpdateProviderCommand(
                                                null, null, null, null)))
                .isInstanceOf(PlatformException.class);
    }

    @Test
    void createValidatesBaseUrl() {
        assertThatThrownBy(
                        () ->
                                service.create(
                                        1L,
                                        new ModelProviderService.CreateProviderCommand(
                                                "test", "openai", "ftp://invalid.com", "key",
                                                null)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("http");
    }

    @Test
    void updateValidatesBaseUrl() {
        ModelProvider existing = new ModelProvider();
        existing.setId("test-id");
        when(mapper.selectById("test-id")).thenReturn(existing);

        assertThatThrownBy(
                        () ->
                                service.update(
                                        "test-id",
                                        new ModelProviderService.UpdateProviderCommand(
                                                "javascript:alert(1)", null, null, null)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("http");
    }
}
