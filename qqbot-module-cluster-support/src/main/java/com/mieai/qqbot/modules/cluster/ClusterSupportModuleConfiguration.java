package com.mieai.qqbot.modules.cluster;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@AutoConfiguration
@ConditionalOnProperty(name = "qqbot.modules.available.cluster-support", havingValue = "true")
public class ClusterSupportModuleConfiguration {}
