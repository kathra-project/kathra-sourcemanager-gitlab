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

package org.kathra.sourcemanager.controller;

import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.kathra.core.model.Membership;
import org.kathra.core.model.SourceRepository;
import org.kathra.core.model.SourceRepositoryCommit;
import org.kathra.sourcemanager.Config;
import org.kathra.sourcemanager.model.Folder;
import org.kathra.sourcemanager.service.SourceManagerService;
import org.kathra.utils.ApiException;
import org.kathra.utils.ApiResponse;
import org.kathra.utils.KathraException;
import org.kathra.utils.ZipUtils;
import org.kathra.utils.sanitizing.SanitizeUtils;
import javassist.NotFoundException;
import org.apache.camel.cdi.ContextName;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.gitlab.api.GitlabAPIException;
import org.gitlab.api.models.*;
import org.json.JSONArray;
import org.zeroturnaround.zip.ZipUtil;

import javax.activation.FileDataSource;
import javax.inject.Named;
import javax.inject.Scope;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.IntStream;

/**
 * Authors: quentin.semanne, jeremy.guillemot
 */

@Named("SourceManagerController")
@ContextName("SourceManager")
public class SourceManagerController implements SourceManagerService {

    public static final String PRIVATE_TOKEN = "PRIVATE-TOKEN";
    private GitlabService gitlabService;
    protected GitService gitService;
    private Config config = new Config();

    private int maxAttempt = 5;
    private int attemptWaitMs = 250;

    Logger logger = Logger.getLogger(SourceManagerController.class.getName());

    public SourceManagerController() throws Exception {
        this.gitService = new GitService();
        this.gitlabService = new GitlabService(config.getGitlabUrl(), config.getGitlabApiToken(), getCurrentSession());
    }

    public SourceManagerController(GitlabService gitlabService) throws Exception {
        this.gitlabService = gitlabService;
        this.gitService = new GitService();
    }

    /**
     * Create a new branch in an existing Kathra SourceRepository
     *
     * @param sourceRepositoryPath SourceRepository's Path (required)
     * @param branch               (required)
     * @param branchRef            (optional)
     * @return SourceRepositoryBranch
     */
    public String createBranch(String sourceRepositoryPath, String branch, String branchRef) throws Exception {
        if (branchRef == null || branchRef.isEmpty()) branchRef = "master";
        GitlabProject projectFromPath = gitlabService.getProjectFromPath(sourceRepositoryPath);
        gitlabService.getUserClient().createBranch(projectFromPath.getId(), branch, branchRef);
        GitlabBranch gitlabBranch = gitlabService.getUserClient().getBranch(projectFromPath.getId(), branch);
        //branch.commit(getSourceRepositoryCommitFromGitlabBranchCommit(gitlabBranch.getCommit()));
        return gitlabBranch.getName();
    }

    /**
     * Create new commit in branch
     *
     * @param sourceRepositoryPath SourceRepository's Path (required)
     * @param branch               SourceRepository's branch (required)
     * @param file                 File to commit (required)
     * @param filepath             The location in which the file has to be commited (optional)
     * @param uncompress           Boolean to indicate if provided file should be uncompressed before being commited (optional, default to false)
     * @return SourceRepositoryCommit
     */
    public SourceRepositoryCommit createCommit(String sourceRepositoryPath, String branch, FileDataSource file, String filepath, Boolean uncompress, String tag, Boolean replaceRepositoryContent) throws Exception {

        String folderPath = "";
        String fileName = null;

        if (uncompress == null) uncompress = false;
        if (filepath != null) {
            int index = filepath.lastIndexOf("/");
            if (index==-1)
                fileName=filepath;
            else if(index== filepath.length()-1){
                folderPath = filepath;
            }
            else {
                folderPath = filepath.substring(0, index);
                fileName = filepath.substring(index+1);
            }
        }

        File permFile = tmpFileToPermanentFile(file.getFile(),fileName);
        File workingFolder = null;
        try {
            String username = gitlabService.session.getCallerName();

            GitlabProject project = getGitlabProject(sourceRepositoryPath);

            String projectName = project.getName();

            workingFolder = gitService.createWorkingFolder();

            final CredentialsProvider cp = getCredentialProviders();

            Git git = gitService.cloneProject(projectName, branch, workingFolder, cp, project.getHttpUrl(), false);

            File projectFolder = new File(workingFolder, projectName);
            if (replaceRepositoryContent != null && replaceRepositoryContent) {
                File[] files = projectFolder.listFiles();
                for (File fileToDelete : files){
                    if (!fileToDelete.getName().equals(".git"))
                        FileUtils.forceDelete(fileToDelete);
                }
            }
            if (uncompress != null && uncompress) {
                ZipUtil.unpack(permFile, projectFolder);
            } else {
                File destPath;
                if (!folderPath.isEmpty()) {
                    destPath = new File(projectFolder, folderPath);
                } else
                    destPath = projectFolder;
                FileUtils.copyFileToDirectory(permFile, destPath, true);
            }

            Status status = git.status().call();
            SourceRepositoryCommit commit;
            if (status.hasUncommittedChanges() || !status.getUntracked().isEmpty()) {
                commit = gitService.createCommit(git, username, "Update autogenerated components");
                gitService.pushToGitRepoWithUserCredentials(cp, git);
                // if tag exists, update and force
                if (StringUtils.isNotEmpty(tag)) {
                    gitService.createTag(git, tag, true);
                    gitService.pushTagOnlyToGitRepoWithUserCredentials(cp, git, true);
                }
            } else {
                throw new KathraException("No changes detected, aborting request.").errorCode(KathraException.ErrorCode.NOT_MODIFIED);
            }
            return commit;
        } finally {
            try {
                if (workingFolder == null && config.isDeleteFolderAfterGit()) {
                    FileUtils.forceDelete(workingFolder);
                    FileUtils.deleteDirectory(workingFolder);
                }
            } catch(Exception e) {

            }
            try {
                FileUtils.deleteQuietly(permFile);
            } catch(Exception e) {

            }
        }
    }

    /**
     * Create a new folder in the Source Repository Provider
     *
     * @param folder Folder object (required)
     * @return Folder
     */
    public Folder createFolder(Folder folder) throws Exception {

        Path path = Paths.get(SanitizeUtils.sanitizePathParameter(folder.getPath()));
        Path parentPath = path.getParent();

        String groupName = path.getFileName().toString();
        GitlabGroup gitlabGroup;
        try {
            GitlabGroup parent = null;
            if (parentPath != null) {
                try {
                    parent = gitlabService.getAdminClient().getGroup(parentPath.toString());
                } catch (FileNotFoundException e) {
                    parent = createFolderHierarchyIfNotExists(parentPath);
                }
            }
            GitlabGroup existingGroup = null;
            try {
                existingGroup = gitlabService.getUserClient().getGroup(path.toString());
            } catch (Exception e) {

            }
            gitlabGroup = (existingGroup != null) ? existingGroup : gitlabService.getUserClient().createGroup(groupName, groupName, null, null, null, parent.getId());

        } catch (GitlabAPIException e) {
            throw new ApiException(409, "A group with the same name already exists at the requested path");
        }

        folder = convertGitlabGroupToFolder(gitlabGroup);
        return folder;
    }

    @Override
    public String deleteSourceRepository(String sourceRepositoryPath) throws Exception {
        try {
            GitlabProject project = gitlabService.getProjectFromPath(sourceRepositoryPath);
            if (project == null) {
                throw new KathraException("No project found.").errorCode(KathraException.ErrorCode.NOT_FOUND);
            }
            gitlabService.getUserClient().deleteProject(project.getId());
        } catch(KathraException e) {
            throw e;
        } catch(Exception e) {
            e.printStackTrace();
            throw new KathraException("Internal error.").errorCode(KathraException.ErrorCode.INTERNAL_SERVER_ERROR);
        }
        return "OK";
    }

    /**
     * Create a new Source Repository in Kathra Repository Provider
     *
     * @param sourceRepository SourceRepository object to be created (required)
     * @param deployKeys       A list of deployKey Ids to enable in the created source repository (optional)
     * @return SourceRepository
     */
    public SourceRepository createSourceRepository(SourceRepository sourceRepository, List<String> deployKeys) throws Exception {

        Path path = Paths.get(SanitizeUtils.sanitizePathParameter(sourceRepository.getPath()));
        Path parentPath = path.getParent();
        String sourceRepoName = path.getFileName().toString();

        GitlabGroup group;
        Map<String, Integer> keysMap;

        if (deployKeys != null && !deployKeys.isEmpty()) {
            keysMap = checkDeployKeysExists(deployKeys);
        } else {
            keysMap = new HashMap();
        }

        try {
            group = gitlabService.getUserClient().getGroup(parentPath.toString());
        } catch (FileNotFoundException e) {
            group = createFolderHierarchyIfNotExists(parentPath);
        }

        if (group == null)
            throw new KathraException("Unable to create gitlab project for provided path: " + parentPath)
                    .errorCode(KathraException.ErrorCode.SERVICE_UNAVAILABLE);

        try {
            GitlabProject gitlabProject = gitlabService.getUserClient().createProjectForGroup(sourceRepoName, group);
            for (Integer keyId : keysMap.values()) {
                enableDeployKeyForProject(gitlabProject, keyId);
            }

            if (gitlabProject == null)
                throw new KathraException("Cannot create the project " + sourceRepository.getName() + " in the group path: (" + parentPath + ", " + group.getName() + ")").errorCode(KathraException.ErrorCode.SERVICE_UNAVAILABLE);

            sourceRepository = map(sourceRepository, gitlabProject);

            createDefaultsBranches(gitlabProject);

            return sourceRepository;
        } catch (Exception e) {
            if (e.getMessage().contains("has already been taken")) {
                Optional<GitlabProject> existingProject = gitlabService.getUserClient().getGroupProjects(group).stream().filter(gitlabProject -> gitlabProject.getName().equals(sourceRepoName)).findFirst();
                if (existingProject.isPresent()) {
                    createDefaultsBranches(existingProject.get());
                    return map(sourceRepository, existingProject.get());
                }
                throw new ApiException(409, "A source repository with the same name already exists at the requested path");
            }
            throw new KathraException("Cannot create the project " + sourceRepository.getName() + " in the group: (" + group.getName() + ") caused by " + e.getMessage(), e).errorCode(KathraException.ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private SourceRepository map(SourceRepository sourceRepository, GitlabProject gitlabProject) {
        sourceRepository
                .httpUrl(gitlabProject.getHttpUrl())
                .sshUrl(gitlabProject.getSshUrl())
                .webUrl(gitlabProject.getWebUrl())
                .provider("gitlab")
                .providerId(String.valueOf(gitlabProject.getId()));
        return sourceRepository;
    }

    private void createDefaultsBranches(GitlabProject gitlabProject) throws Exception {
        int attempt = 0;
        Exception lastException;
        do {
            try {
                gitlabService.getUserClient().createBranch(gitlabProject, "dev", "master");
                lastException = null;
                break;
            } catch (Exception e) {
                long wait = (long) (attemptWaitMs * Math.pow(2, attempt));
                // checking branch creation in spite of the error
                try {
                    GitlabBranch devBranch = gitlabService.getUserClient().getBranch(gitlabProject, "dev");
                    if(devBranch !=null && devBranch.getCommit()!=null && devBranch.getCommit().getId()!=null) {
                        logger.info("Gitlab has thrown an exception, however the branches has been created ("+e.getMessage()+")");
                        lastException = null;
                        break;
                    }
                } catch (Exception e1){
                    logger.warn("Gitlab has thrown an exception, branches has not been created : "+e1.getMessage());
                }

                logger.warn("Unable to create branches for repository " + gitlabProject.getPath() + ", wait  " + wait + " ms and retry (" + attempt + "/" + maxAttempt + ")");
                lastException = e;
                Thread.sleep(wait);
                attempt++;

            }
        } while(attempt < maxAttempt);
        if (lastException != null) {
            lastException.printStackTrace();
            throw lastException;
        }

    }

    private Map checkDeployKeysExists(List<String> deployKeys) throws Exception {
        Map<String, Integer> keysMap = new HashMap();

        JSONArray array = Unirest.get(config.getGitlabUrl() + "/api/v4/deploy_keys")
                .queryString("per_page", "1000000")
                .header(PRIVATE_TOKEN, config.getGitlabApiToken())
                .asJson().getBody().getArray();

        IntStream.range(0, array.length())
                .mapToObj(array::getJSONObject)
                .forEach(obj -> keysMap.put((String) obj.get("title"), (Integer) obj.get("id")));

        Map<String, Integer> keysMapToReturn = new HashMap();

        for (String title : deployKeys) {
            if (!keysMap.containsKey(title)) {
                throw new KathraException("Unable to find deploy key " + title).errorCode(KathraException.ErrorCode.NOT_FOUND);
            }
            keysMapToReturn.put(title,keysMap.get(title));
        }

        return keysMapToReturn;
    }

    private void enableDeployKeyForProject(GitlabProject p, Integer keyId) throws UnirestException {
        Unirest.post(config.getGitlabUrl() + "/api/v4/projects/{projectId}/deploy_keys/{keyId}/enable")
                .header(PRIVATE_TOKEN, config.getGitlabApiToken())
                .routeParam("projectId", p.getId().toString())
                .routeParam("keyId", keyId.toString())
                .asJson();
    }

    /**
     * Retrieve accessible branches in an existing Kathra SourceRepository
     *
     * @param sourceRepositoryPath SourceRepository's Path (required)
     * @return List<SourceRepositoryBranch>
     */
    public List<String> getBranches(String sourceRepositoryPath) throws Exception {

        List<String> branches = new ArrayList();
        for (GitlabBranch gitlabBranch : gitlabService.getUserClient().getBranches(gitlabService.getProjectFromPath(sourceRepositoryPath))) {
            branches.add(gitlabBranch.getName());
        }
        for (GitlabTag tag : gitlabService.getUserClient().getTags(gitlabService.getProjectFromPath(sourceRepositoryPath))) {
            branches.add(tag.getName());
        }
        return branches;
    }

    /**
     * Retrieve accessible commits in an existing Kathra SourceRepositoryBranch
     *
     * @param sourceRepositoryPath SourceRepository's Path (required)
     * @param branch               SourceRepository's branch (required)
     * @return List<SourceRepositoryCommit>
     */
    public List<SourceRepositoryCommit> getCommits(String sourceRepositoryPath, String branch) throws Exception {
        List<SourceRepositoryCommit> commits = new ArrayList();
        for (GitlabCommit gitlabCommit : gitlabService.getUserClient().getAllCommits(gitlabService.getProjectFromPath(sourceRepositoryPath).getId(), branch)) {
            commits.add(getSourceRepositoryCommitFromGitlabCommit(gitlabCommit));
        }
        return commits;
    }

    @Override
    public FileDataSource getFile(String sourceRepositoryPath, String branch, String filepath) throws Exception {
        if (StringUtils.isEmpty(sourceRepositoryPath) || StringUtils.isEmpty(branch) || StringUtils.isEmpty(filepath))
            throw new IllegalArgumentException("sourceRepositoryPath, branch and filepath must be specified");

        GitlabProject project = getGitlabProject(sourceRepositoryPath);
        String projectName = project.getName();

        File workingFolder = gitService.createWorkingFolder();

        final CredentialsProvider cp = getCredentialProviders();

        gitService.cloneProject(projectName, branch, workingFolder, cp, project.getHttpUrl(),true);

        File file = new File(workingFolder, File.separator + projectName + File.separator + filepath);
        if (file == null || !file.exists() || file.isDirectory())
            throw new NotFoundException("File " + filepath + " not found in repository " + sourceRepositoryPath + " ,branch=" + branch);

        return new FileDataSource(file);
    }

    @Override
    public Folder getFolder(String folderPath) throws Exception {
        return null;
    }

    /**
     * Retrieve accessible folders for user using provided identity
     *
     * @return List<Folder>
     */
    public List<Folder> getFolders() throws Exception {
        List<Folder> folders = new ArrayList();
        List<GitlabGroup> gitlabGroups = gitlabService.getUserClient().getGroups();
        for (GitlabGroup g : gitlabGroups) {
            folders.add(new Folder().path(g.getFullPath()));
        }

        return folders;
    }

    /**
     * Retrieve accessible Source Repositories in the specified folder
     *
     * @param folderPath Folder's ID in which artifacts will be created (required)
     * @return List<SourceRepository>
     */
    public List<SourceRepository> getSourceRepositoriesInFolder(String folderPath) throws Exception {
        List<GitlabProject> groupProjects;
        try {
            groupProjects = gitlabService.getUserClient().getGroupProjects(gitlabService.getAdminClient().getGroup(folderPath).getId());
        } catch (Error e) {
            throw new ApiException(404, "This group doesn't exists");
        }

        List<SourceRepository> repos = new ArrayList();

        groupProjects.forEach(p -> {
            SourceRepository sourceRepository = new SourceRepository();
            sourceRepository.providerId(p.getId().toString()).name(p.getName());
            sourceRepository
                    .provider("gitlab")
                    .httpUrl(p.getHttpUrl())
                    .webUrl(p.getWebUrl())
                    .sshUrl(p.getSshUrl());
            repos.add(sourceRepository);
        });
        return repos;
    }

    /**
     * Add multiple memberships in specified projects
     *
     * @param memberships (required)
     * @return List<Membership>
     */
    public ApiResponse addMemberships(List<Membership> memberships) throws Exception {
        gitlabService.addMemberships(memberships);
        return new ApiResponse(200, null, "Successfully added members");
    }

    /**
     * Create new deployKey in specified project
     *
     * @param keyName              (required)
     * @param sshPublicKey         (required)
     * @param sourceRepositoryPath SourceRepository's Path (required)
     * @return ApiResponse
     */
    public ApiResponse createDeployKey(String keyName, String sshPublicKey, String sourceRepositoryPath) throws Exception {
        try {
            gitlabService.createDeployKey(keyName, sshPublicKey, sourceRepositoryPath);
        } catch (GitlabAPIException e) {
            throw new ApiException(e.getResponseCode(), e.getMessage());
        }
        return new ApiResponse(200, null, "Successfully added deploy key " + keyName);
    }

    /**
     * Delete multiple memberships in specified projects
     *
     * @param memberships (required)
     * @return List<Membership>
     */
    public ApiResponse deleteMemberships(List<Membership> memberships) throws Exception {
        gitlabService.deleteMemberships(memberships);
        return new ApiResponse(200, null, "Successfully removed members");
    }


    /**
     * Retrieve memberships in specified project
     *
     * @param sourceRepositoryPath SourceRepository's Path (required)
     * @param memberType           Type of memberships to retrieve, return all types if not specified (optional)
     * @return List<Membership>
     */
    public List<Membership> getMemberships(String sourceRepositoryPath, String memberType) throws Exception {
        return gitlabService.getMemberships(sourceRepositoryPath, memberType);
    }

    private SourceRepositoryCommit getSourceRepositoryCommitFromGitlabCommit(GitlabCommit gitlabCommit) {
        SourceRepositoryCommit commit = new SourceRepositoryCommit()
                .authorEmail(gitlabCommit.getAuthorEmail())
                .authorName(gitlabCommit.getAuthorName())
                .createdAt(gitlabCommit.getCreatedAt().toString())
                .message(gitlabCommit.getMessage())
                .shortId(gitlabCommit.getShortId())
                .title(gitlabCommit.getTitle());

        commit.id(gitlabCommit.getId());
        return commit;
    }


    private String getGroupPathFromWebUrl(String webUrl) {
        return webUrl.split("groups/")[1];
    }

    private Folder convertGitlabGroupToFolder(GitlabGroup g) {
        Folder folder = new Folder().path(getGroupPathFromWebUrl(g.getWebUrl()));
        return folder;
    }

    public GitlabService getGitlabService() {
        return gitlabService;
    }

    private GitlabGroup createFolderHierarchyIfNotExists(Path path) throws Exception {

        GitlabGroup currentFolder = checkExistingFolder(path);

        if (currentFolder!=null){
            // The requested folder already exists, nothing to do
            if (currentFolder.getFullPath().equals(path.toString()))
                return currentFolder;

            path = Paths.get(currentFolder.getFullPath()).relativize(path);
        }
        Iterator<Path> iterator = path.iterator();
        while (iterator.hasNext()) {
            // Create every folder recursively under the previous one
            currentFolder = createFolderIfNotExists(iterator.next().toString(), currentFolder);
        }
        return currentFolder;
    }

    private GitlabGroup checkExistingFolder (Path path) {
        Path pathToTest = path;
        GitlabGroup ret = null;

        while(pathToTest!=null && ret == null) {

            try {
                ret = gitlabService.getUserClient().getGroup(pathToTest.toString());
            } catch(IOException e) {
                pathToTest = pathToTest.getParent();
            }
        }

        return ret;

    }

    private synchronized GitlabGroup createFolderIfNotExists(String folderName, GitlabGroup parentGroup) throws Exception {
        GitlabGroup groupFolder = null;
        try {
            if (parentGroup != null) {
                CreateGroupRequest groupRequest = new CreateGroupRequest(folderName).setParentId(parentGroup.getId());
                groupFolder = gitlabService.getUserClient().createGroup(groupRequest, null);
            } else {
                groupFolder = gitlabService.getUserClient().createGroup(folderName);
            }
        } catch (GitlabAPIException e) {
            if (e.getResponseCode() == 400) {
                if (parentGroup != null) {
                    groupFolder = gitlabService.getUserClient().getGroup(parentGroup.getFullPath() + "/" + folderName);
                } else {
                    groupFolder = gitlabService.getUserClient().getGroup(folderName);
                }
                return groupFolder;
            } else if (e.getResponseCode() == 403) {
                if (parentGroup != null) {
                    throw new KathraException("Forbidden to create folder "+parentGroup.getFullPath() + "/" +folderName+", please verify you have the correct permissions in your source repository provider",e.getCause(), KathraException.ErrorCode.FORBIDDEN);
                } else {
                    throw new KathraException("Forbidden to create folder "+folderName+", please verify you have the correct permissions in your source repository provider",e.getCause(), KathraException.ErrorCode.FORBIDDEN);
                }
            }
        }

        return groupFolder;
    }

    private CredentialsProvider getCredentialProviders() {
        String username = gitlabService.session.getCallerName();
        String impersonationTokenForUser = gitlabService.getImpersonationTokenForUser();
        return new UsernamePasswordCredentialsProvider(username, impersonationTokenForUser);
    }

    private GitlabProject getGitlabProject(String sourceRepositoryPath) throws Exception {

        GitlabProject projectFromPath = gitlabService.getProjectFromPath(sourceRepositoryPath);

        // TODO: Better permissions check
        GitlabProject project = gitlabService.getUserClient().getProject(projectFromPath.getId());

        // temporary work around to avoid unwanted pushes to non-kathra repos
        if (!project.getPathWithNamespace().startsWith(config.getKathraRootGroup())) {
            throw new KathraException("Unauthorized to read from a non-kathra source repository").errorCode(KathraException.ErrorCode.UNAUTHORIZED);
        }
        return project;
    }

    private File tmpFileToPermanentFile(File file, String fileName) throws IOException {
        if (StringUtils.isEmpty(fileName) || fileName.equals(".")) {
            String[] split = file.getName().split("_");
            fileName = split[split.length - 1];
        }
        File tmpFile = new File(file.getParentFile().getPath()+File.separator+"SourceManager-Gitlab"+file.getName()+File.separator+fileName);
        FileUtils.copyFile(file, tmpFile);
        return tmpFile;
    }
}
