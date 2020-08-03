/*
 * Copyright (c) 2020. The Kathra Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *    IRT SystemX (https://www.kathra.org/)
 *
 */

package org.kathra.sourcemanager;

import org.kathra.utils.ConfigManager;

public class Config extends ConfigManager {

    private final String password;
    private final String username;
    private String gitlabUrl;
    private String gitlabApiToken;
    private boolean deleteFolderAfterGit;
    private String kathraRootGroup;

    private String keycloakHost;
    private String resourceManager;

    public Config() {
        gitlabUrl = getProperty("KATHRA_SOURCEMANAGER_GITLAB_URL", "https://git.dev-irtsysx.fr");
        deleteFolderAfterGit = Boolean.valueOf(getProperty("KATHRA_SOURCEMANAGER_DELETE_FOLDER_AFTER_GIT", "true"));
        gitlabApiToken = getProperty("KATHRA_SOURCEMANAGER_GITLAB_API_TOKEN");
        kathraRootGroup = getProperty("KATHRA_ROOT_GROUP", "kathra-projects");

        username = getProperty("USERNAME","");
        password = getProperty("PASSWORD", "");
        resourceManager = getProperty("RESOURCE_MANAGER_URL", "");
        keycloakHost = getProperty("KEYCLOAK_AUTH_URL", "") .replace("http://", "")
                .replace("https://", "")
                .replace("/aut.*", "");
    }

    public String getGitlabUrl() {
        return gitlabUrl;
    }

    public String getGitlabApiToken() {
        return gitlabApiToken;
    }

    public boolean isDeleteFolderAfterGit() {
        return deleteFolderAfterGit;
    }

    public String getKathraRootGroup() {
        return kathraRootGroup;
    }

    public String getLoginKeycloak() {
        return username;
    }

    public String getPasswordKeycloak() {
        return password;
    }

    public String getResourceManager() {
        return resourceManager;
    }

    public String getKeycloakHost() {
        return keycloakHost;
    }

    public String getDelaySchedule() {
        return "30s";
    }
}
