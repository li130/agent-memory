package com.wuuees.li.memory.config;

import java.io.IOException;
import java.nio.file.Path;

import com.wuuees.li.memory.classifier.MemoryClassifier;
import com.wuuees.li.memory.index.MemoryIndex;
import com.wuuees.li.memory.injector.MemoryInjector;
import com.wuuees.li.memory.kairos.KairosLogger;
import com.wuuees.li.memory.kairos.NightlyDistiller;
import com.wuuees.li.memory.recall.MemoryRecall;
import com.wuuees.li.memory.recall.SideQueryClient;
import com.wuuees.li.memory.service.MemorySystem;
import com.wuuees.li.memory.storage.MemoryStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(MemoryProperties.class)
public class MemoryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MemoryStorage memoryStorage(MemoryProperties properties) {
        return new MemoryStorage(Path.of(properties.getBasePath()));
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryIndex memoryIndex(MemoryStorage storage) {
        return new MemoryIndex(storage);
    }

    @Bean
    @ConditionalOnMissingBean
    public SideQueryClient sideQueryClient(MemoryProperties properties) {
        return new SideQueryClient(properties.getLlm());
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryClassifier memoryClassifier(SideQueryClient sideQueryClient) {
        return new MemoryClassifier(sideQueryClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryRecall memoryRecall(SideQueryClient sideQueryClient) {
        return new MemoryRecall(sideQueryClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryInjector memoryInjector(MemoryIndex index) {
        return new MemoryInjector(index);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemorySystem memorySystem(MemoryStorage storage, MemoryIndex index,
                                      MemoryClassifier classifier, MemoryRecall recall,
                                      MemoryInjector injector, SideQueryClient sideQueryClient,
                                      MemoryProperties properties)
            throws IOException {
        String projectRoot = MemoryStorage.detectProjectRoot();
        Path memoryDir = storage.resolveMemoryDir(projectRoot, properties.getProjectName());
        storage.ensureMemoryDir(memoryDir);
        return new MemorySystem(storage, index, classifier, recall, injector,
                sideQueryClient, memoryDir);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent-memory.kairos", name = "enabled", havingValue = "true")
    public KairosLogger kairosLogger(MemorySystem memorySystem, MemoryProperties properties) {
        return new KairosLogger(memorySystem.getMemoryDir(), properties.getKairos().isEnabled());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent-memory.kairos", name = "enabled", havingValue = "true")
    public NightlyDistiller nightlyDistiller(KairosLogger kairosLogger,
                                              MemorySystem memorySystem,
                                              SideQueryClient sideQueryClient) {
        return new NightlyDistiller(kairosLogger, memorySystem, sideQueryClient);
    }
}
