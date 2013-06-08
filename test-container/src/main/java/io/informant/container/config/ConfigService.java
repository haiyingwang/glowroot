/**
 * Copyright 2013 the original author or authors.
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
package io.informant.container.config;

import java.util.List;

import checkers.nullness.quals.Nullable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public interface ConfigService {

    void setStoreThresholdMillis(int storeThresholdMillis) throws Exception;

    GeneralConfig getGeneralConfig() throws Exception;

    String updateGeneralConfig(GeneralConfig config) throws Exception;

    CoarseProfilingConfig getCoarseProfilingConfig() throws Exception;

    String updateCoarseProfilingConfig(CoarseProfilingConfig config) throws Exception;

    FineProfilingConfig getFineProfilingConfig() throws Exception;

    String updateFineProfilingConfig(FineProfilingConfig config) throws Exception;

    UserConfig getUserConfig() throws Exception;

    String updateUserConfig(UserConfig config) throws Exception;

    StorageConfig getStorageConfig() throws Exception;

    String updateStorageConfig(StorageConfig config) throws Exception;

    @Nullable
    PluginConfig getPluginConfig(String pluginId) throws Exception;

    String updatePluginConfig(String pluginId, PluginConfig config) throws Exception;

    List<PointcutConfig> getPointcutConfigs() throws Exception;

    String addPointcutConfig(PointcutConfig pointcutConfig) throws Exception;

    String updatePointcutConfig(String version, PointcutConfig pointcutConfig) throws Exception;

    void removePointcutConfig(String version) throws Exception;

    void retransformClasses() throws Exception;

    void compactData() throws Exception;
}