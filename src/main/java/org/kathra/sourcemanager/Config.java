/* 
 * Copyright 2019 The Kathra Authors.
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
 *
 *    IRT SystemX (https://www.kathra.org/)    
 *
 */

package org.kathra.sourcemanager;

import org.kathra.utils.ConfigManager;

public class Config extends ConfigManager {

    private String gitlabUrl;
    private String gitlabApiToken;
    private String folderNameContainingGitRepos;
    private boolean deleteFolderAfterGit;
    private boolean deleteZipFile;
    private String kathraRootGroup;
    private String userManagerUrl;

    private int maximalTryNumberToCreateDirectory;

    public Config() {
        gitlabUrl = getProperty("KATHRA_SOURCEMANAGER_GITLAB_URL", "https://git.dev-irtsysx.fr");
        folderNameContainingGitRepos = getProperty("KATHRA_SOURCEMANAGER_FOLDER_NAME_CONTAINING_GIT_REPOS",System.getProperty("java.io.tmpdir")+"/kathra-sourcemanager-git-repos");
        maximalTryNumberToCreateDirectory = Integer.parseInt(getProperty("KATHRA_SOURCEMANAGER_MAXIMAL_TRY_NUMBER_TO_CREATE_DIRECTORY", "3"));
        deleteFolderAfterGit = Boolean.valueOf(getProperty("KATHRA_SOURCEMANAGER_DELETE_FOLDER_AFTER_GIT", "true"));
        deleteZipFile = Boolean.valueOf(getProperty("KATHRA_SOURCEMANAGER_DELETE_ZIP_FILE", "true"));
        gitlabApiToken = getProperty("KATHRA_SOURCEMANAGER_GITLAB_API_TOKEN");
        kathraRootGroup = getProperty("KATHRA_ROOT_GROUP", "kathra-projects");
    }

    public String getGitlabUrl() {
        return gitlabUrl;
    }

    public String getGitlabApiToken() {
        return gitlabApiToken;
    }

    public String getFolderNameContainingGitRepos() {
        return folderNameContainingGitRepos;
    }

    public boolean isDeleteFolderAfterGit() {
        return deleteFolderAfterGit;
    }

    public boolean isDeleteZipFile() {
        return deleteZipFile;
    }

    public int getMaximalTryNumberToCreateDirectory() {
        return maximalTryNumberToCreateDirectory;
    }

    public String getKathraRootGroup() {
        return kathraRootGroup;
    }
}
