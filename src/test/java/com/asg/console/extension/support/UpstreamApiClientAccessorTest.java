package com.asg.console.extension.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.alibaba.higress.sdk.service.kubernetes.KubernetesClientService;

import io.kubernetes.client.openapi.ApiClient;

class UpstreamApiClientAccessorTest {

    @Test
    void getApiClientReturnsPrivateFieldValue() throws Exception {
        KubernetesClientService service = Mockito.mock(KubernetesClientService.class);
        ApiClient expected = new ApiClient();
        Field field = KubernetesClientService.class.getDeclaredField("client");
        field.setAccessible(true);
        field.set(service, expected);

        assertThat(UpstreamApiClientAccessor.getApiClient(service)).isSameAs(expected);
    }
}
