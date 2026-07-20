package com.mieai.qqbot.app.media;

import com.mieai.qqbot.client.FileMediaAssetStore;
import com.mieai.qqbot.client.MediaAssetStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaRuntimeProperties.class)
public class MediaRuntimeConfiguration {
    @Bean
    MediaAssetStore mediaAssetStore(MediaRuntimeProperties properties) {
        return new FileMediaAssetStore(properties.getStagingDirectory());
    }
}
