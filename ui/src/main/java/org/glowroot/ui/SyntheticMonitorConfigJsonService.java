/*
 * Copyright 2014-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.ui;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import com.google.common.primitives.Ints;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.repo.ConfigRepository;
import org.glowroot.common.repo.ConfigRepository.DuplicateMBeanObjectNameException;
import org.glowroot.common.repo.util.Compilations;
import org.glowroot.common.repo.util.Compilations.CompilationException;
import org.glowroot.common.repo.util.Encryption;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Versions;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.SyntheticMonitorConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.SyntheticMonitorConfig.SyntheticMonitorKind;

import static com.google.common.base.Preconditions.checkNotNull;
import static io.netty.handler.codec.http.HttpResponseStatus.CONFLICT;

@JsonService
class SyntheticMonitorConfigJsonService {

    private static final Logger logger =
            LoggerFactory.getLogger(SyntheticMonitorConfigJsonService.class);

    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final Pattern encryptPattern = Pattern.compile("\"ENCRYPT:([^\"]*)\"");

    @VisibleForTesting
    static final Ordering<SyntheticMonitorConfig> orderingByDisplay =
            new Ordering<SyntheticMonitorConfig>() {
                @Override
                public int compare(SyntheticMonitorConfig left, SyntheticMonitorConfig right) {
                    if (left.getKindValue() == right.getKindValue()) {
                        return left.getDisplay().compareToIgnoreCase(right.getDisplay());
                    }
                    return Ints.compare(left.getKindValue(), right.getKindValue());
                }
            };

    private final ConfigRepository configRepository;

    SyntheticMonitorConfigJsonService(ConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    // central supports synthetic monitor configs on rollups
    @GET(path = "/backend/config/synthetic-monitors",
            permission = "agent:config:view:syntheticMonitor")
    String getSyntheticMonitor(@BindAgentRollupId String agentRollupId,
            @BindRequest SyntheticMonitorConfigRequest request) throws Exception {
        Optional<String> id = request.id();
        if (id.isPresent()) {
            SyntheticMonitorConfig config =
                    configRepository.getSyntheticMonitorConfig(agentRollupId, id.get());
            if (config == null) {
                throw new JsonServiceException(HttpResponseStatus.NOT_FOUND);
            }
            return mapper.writeValueAsString(SyntheticMonitorConfigDto.create(config));
        } else {
            List<SyntheticMonitorConfigDto> configDtos = Lists.newArrayList();
            List<SyntheticMonitorConfig> configs =
                    configRepository.getSyntheticMonitorConfigs(agentRollupId);
            configs = orderingByDisplay.immutableSortedCopy(configs);
            for (SyntheticMonitorConfig config : configs) {
                configDtos.add(SyntheticMonitorConfigDto.create(config));
            }
            return mapper.writeValueAsString(configDtos);
        }
    }

    // central supports synthetic monitor configs on rollups
    @POST(path = "/backend/config/synthetic-monitors/add",
            permission = "agent:config:edit:syntheticMonitor")
    String addSyntheticMonitor(@BindAgentRollupId String agentRollupId,
            @BindRequest SyntheticMonitorConfigDto configDto)
            throws Exception {
        SyntheticMonitorConfig config = configDto.convert(configRepository.getSecretKey());
        String errorResponse = validate(config);
        if (errorResponse != null) {
            return errorResponse;
        }
        String id;
        try {
            id = configRepository.insertSyntheticMonitorConfig(agentRollupId, config);
        } catch (DuplicateMBeanObjectNameException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            throw new JsonServiceException(CONFLICT, "mbeanObjectName");
        }
        config = config.toBuilder()
                .setId(id)
                .build();
        return mapper.writeValueAsString(SyntheticMonitorConfigDto.create(config));
    }

    // central supports synthetic monitor configs on rollups
    @POST(path = "/backend/config/synthetic-monitors/update",
            permission = "agent:config:edit:syntheticMonitor")
    String updateSyntheticMonitor(@BindAgentRollupId String agentRollupId,
            @BindRequest SyntheticMonitorConfigDto configDto) throws Exception {
        SyntheticMonitorConfig config = configDto.convert(configRepository.getSecretKey());
        String errorResponse = validate(config);
        if (errorResponse != null) {
            return errorResponse;
        }
        configRepository.updateSyntheticMonitorConfig(agentRollupId, config,
                configDto.version().get());
        return mapper.writeValueAsString(SyntheticMonitorConfigDto.create(config));
    }

    // central supports synthetic monitor configs on rollups
    @POST(path = "/backend/config/synthetic-monitors/remove",
            permission = "agent:config:edit:syntheticMonitor")
    void removeSyntheticMonitor(@BindAgentRollupId String agentRollupId,
            @BindRequest SyntheticMonitorConfigRequest request) throws Exception {
        configRepository.deleteSyntheticMonitorConfig(agentRollupId, request.id().get());
    }

    private @Nullable String validate(SyntheticMonitorConfig config) throws Exception {
        if (config.getKind() == SyntheticMonitorKind.JAVA) {
            // only used by central
            try {
                Class<?> javaSource = Compilations.compile(config.getJavaSource());
                try {
                    javaSource.getConstructor();
                } catch (NoSuchMethodException e) {
                    return buildCompilationErrorResponse(
                            ImmutableList.of("Class must have a public default constructor"));
                }
                // since synthetic monitors are only used in central, this class is present
                Class<?> webDriverClass = Class.forName("org.openqa.selenium.WebDriver");
                try {
                    javaSource.getMethod("test", new Class[] {webDriverClass});
                } catch (NoSuchMethodException e) {
                    return buildCompilationErrorResponse(ImmutableList.of("Class must have a"
                            + " \"public void test(WebDriver driver) { ... }\" method"));
                }
            } catch (CompilationException e) {
                return buildCompilationErrorResponse(e.getCompilationErrors());
            }
        }
        return null;
    }

    private String buildCompilationErrorResponse(List<String> compilationErrors)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeArrayFieldStart("javaSourceCompilationErrors");
        for (String compilationError : compilationErrors) {
            jg.writeString(compilationError);
        }
        jg.writeEndArray();
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @Value.Immutable
    interface SyntheticMonitorConfigRequest {
        Optional<String> id();
    }

    @Value.Immutable
    abstract static class SyntheticMonitorConfigDto {

        abstract String display();
        abstract SyntheticMonitorKind kind();
        abstract @Nullable String pingUrl();
        abstract @Nullable String javaSource();
        abstract Optional<String> id(); // absent for insert operations
        abstract Optional<String> version(); // absent for insert operations

        private SyntheticMonitorConfig convert(SecretKey secretKey)
                throws GeneralSecurityException {
            SyntheticMonitorConfig.Builder builder = SyntheticMonitorConfig.newBuilder()
                    .setDisplay(display())
                    .setKind(kind());
            String pingUrl = pingUrl();
            if (pingUrl != null) {
                builder.setPingUrl(pingUrl);
            }
            String javaSource = javaSource();
            if (javaSource != null) {
                Matcher matcher = encryptPattern.matcher(javaSource);
                StringBuffer sb = new StringBuffer();
                while (matcher.find()) {
                    String unencryptedPassword = checkNotNull(matcher.group(1));
                    matcher.appendReplacement(sb, "\"ENCRYPTED:"
                            + Encryption.encrypt(unencryptedPassword, secretKey) + "\"");
                }
                matcher.appendTail(sb);
                builder.setJavaSource(sb.toString());
            }
            Optional<String> id = id();
            if (id.isPresent()) {
                builder.setId(id.get());
            }
            return builder.build();
        }

        private static SyntheticMonitorConfigDto create(SyntheticMonitorConfig config) {
            ImmutableSyntheticMonitorConfigDto.Builder builder =
                    ImmutableSyntheticMonitorConfigDto.builder()
                            .display(config.getDisplay())
                            .kind(config.getKind());
            String pingUrl = config.getPingUrl();
            if (!pingUrl.isEmpty()) {
                builder.pingUrl(pingUrl);
            }
            String javaSource = config.getJavaSource();
            if (!javaSource.isEmpty()) {
                builder.javaSource(javaSource);
            }
            return builder.id(config.getId())
                    .version(Versions.getVersion(config))
                    .build();
        }
    }
}
