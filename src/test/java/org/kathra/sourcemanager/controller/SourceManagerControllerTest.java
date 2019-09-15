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
package org.kathra.sourcemanager.controller;

import org.kathra.core.model.SourceRepository;
import org.kathra.sourcemanager.model.Folder;
import org.kathra.utils.Session;
import javassist.NotFoundException;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.gitlab.api.GitlabAPI;
import org.gitlab.api.models.GitlabBranch;
import org.gitlab.api.models.GitlabGroup;
import org.gitlab.api.models.GitlabProject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.zeroturnaround.zip.ZipUtil;

import javax.activation.FileDataSource;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SourceManagerControllerTest {

    public static final String KATHRA_PROJECTS = "kathra-projects";
    public static final String KATHRA_PROJECTS_DT = "kathra-projects/DT";
    private SourceManagerController underTest;
    static List<GitlabProject> gitlabProjects = new ArrayList();
    static List<GitlabGroup> gitlabGroups = new ArrayList();
    static List<GitlabBranch> gitlabBranches = new ArrayList();
    static Session session;
    static File workingFolder;
    static Git git;

    @Mock
    private GitlabService gitlabService;

    @Mock
    private GitService gitService;

    @Mock
    private GitlabAPI adminClient;

    @Mock
    private GitlabAPI userClient;

    @BeforeAll
    static void setUp() {
        workingFolder = new File(System.getProperty("java.io.tmpdir") + File.separator + "SESSION_1");
        GitlabGroup gitlabGroup = new GitlabGroup();
        gitlabGroup.setFullPath(KATHRA_PROJECTS);
        gitlabGroups.add(gitlabGroup);
        gitlabGroup = new GitlabGroup();
        gitlabGroup.setFullPath(KATHRA_PROJECTS_DT);
        gitlabGroups.add(gitlabGroup);

        GitlabBranch branch = new GitlabBranch();
        branch.setName("master");
        gitlabBranches.add(branch);
        branch = new GitlabBranch();
        branch.setName("dev");
        gitlabBranches.add(branch);

        GitlabProject p = new GitlabProject();
        p.setId(2);
        p.setName("testProject");
        p.setSshUrl("sshUrl");
        p.setHttpUrl("httpUrl");
        p.setWebUrl("webUrl");
        p.setNameWithNamespace(KATHRA_PROJECTS_DT + "/" + p.getName());
        p.setPathWithNamespace(KATHRA_PROJECTS_DT);
        gitlabProjects.add(p);

        session = new Session().callerName("testUser").id("testSessionId");
    }

    @AfterAll
    static void tearDown() throws IOException {
        FileUtils.deleteDirectory(workingFolder);
    }

    @BeforeEach
    void setUpEach() throws Exception {
        adminClient = Mockito.mock(GitlabAPI.class);
        userClient = Mockito.mock(GitlabAPI.class);
        gitService = Mockito.mock(GitService.class);
        gitlabService = Mockito.mock(GitlabService.class);
        gitlabService.session = session;
        Mockito.reset(gitService);
        Mockito.reset(gitlabService);
        Mockito.reset(adminClient);
        Mockito.reset(userClient);
        Mockito.when(gitlabService.getUserClient()).thenReturn(userClient);
        Mockito.when(gitlabService.getAdminClient()).thenReturn(adminClient);

        underTest = new SourceManagerController(gitlabService);
        underTest.gitService = gitService;

        Mockito.doAnswer(invocationOnMock -> {
            workingFolder.mkdirs();
            return workingFolder;
        }).when(gitService).createWorkingFolder();

        Mockito.when(gitService.createCommit(Mockito.any(Git.class), Mockito.any(String.class), Mockito.any(String.class))).thenCallRealMethod();
        Mockito.doCallRealMethod().when(gitService).createTag(Mockito.any(), Mockito.any(), Mockito.anyBoolean());
        Mockito.when(gitService.pushToGitRepoWithUserCredentials(Mockito.any(CredentialsProvider.class), Mockito.any(Git.class))).thenReturn(null);

        Mockito.doAnswer(invocationOnMock -> {
            ZipUtil.unpack(new File("src/test/resources/repo.zip"), new File(workingFolder + File.separator + gitlabProjects.get(0).getName()));
            return Git.open(new File(workingFolder + File.separator + gitlabProjects.get(0).getName() + File.separator + ".git"));
        }).when(gitService).cloneProject(eq(gitlabProjects.get(0).getName()), eq("dev"), eq(workingFolder), Mockito.any(CredentialsProvider.class), eq(gitlabProjects.get(0).getHttpUrl()),Mockito.anyBoolean());

        Mockito.when(gitlabService.getImpersonationTokenForUser())
                .thenReturn("testImpersonationTokenForUser");

        Mockito.when(gitlabService.getProjectFromPath(KATHRA_PROJECTS_DT + File.separator + gitlabProjects.get(0).getName()))
                .thenReturn(gitlabProjects.get(0));

        Mockito.when(gitlabService.getUserClient().getProject(gitlabProjects.get(0).getId()))
                .thenReturn(gitlabProjects.get(0));

        git = Git.init().setDirectory(new File(workingFolder + File.separator + gitlabProjects.get(0).getName())).call();
    }

    @Test
    public void given_nominal_args_when_getFolders_then_works() throws Exception {
        GitlabService gitlabService = underTest.getGitlabService();
        GitlabAPI userClient = gitlabService.getUserClient();
        Mockito.when(userClient.getGroups()).thenReturn(gitlabGroups);
        List<Folder> folders = underTest.getFolders();
        Assertions.assertEquals(2, folders.size(), "Number of returned folders");
        Assertions.assertEquals(KATHRA_PROJECTS, folders.get(0).getPath());
        Assertions.assertEquals(KATHRA_PROJECTS_DT, folders.get(1).getPath());
    }

    @Test
    public void given_nominal_args_when_getSourceRepositoriesInFolder_then_works() throws Exception {
        GitlabProject p = gitlabProjects.get(0);
        GitlabGroup gitlabGroup = new GitlabGroup();
        gitlabGroup.setFullPath(KATHRA_PROJECTS_DT);
        gitlabGroup.setId(1);
        Mockito.when(adminClient.getGroup(KATHRA_PROJECTS_DT)).thenReturn(gitlabGroup);
        Mockito.when(userClient.getGroupProjects(1)).thenReturn(gitlabProjects);
        List<SourceRepository> sourceRepositoriesInFolder = underTest.getSourceRepositoriesInFolder(KATHRA_PROJECTS_DT);

        Assertions.assertEquals(1, sourceRepositoriesInFolder.size(), "Number of source repositories in folder");
        SourceRepository returnedSourceRepository = sourceRepositoriesInFolder.get(0);
        Assertions.assertEquals(null, returnedSourceRepository.getId());
        Assertions.assertEquals(p.getId().toString(), returnedSourceRepository.getProviderId());
        Assertions.assertEquals(p.getName(), returnedSourceRepository.getName());
        Assertions.assertEquals(p.getSshUrl(), returnedSourceRepository.getSshUrl());
        Assertions.assertEquals(p.getHttpUrl(), returnedSourceRepository.getHttpUrl());
        Assertions.assertEquals(p.getWebUrl(), returnedSourceRepository.getWebUrl());
    }

    @Test
    public void given_nominal_args_when_getBranches_then_works() throws Exception {
        Mockito.when(userClient.getBranches(gitlabProjects.get(0)))
                .thenReturn(gitlabBranches);
        List<String> branches = underTest.getBranches(KATHRA_PROJECTS_DT + File.separator + gitlabProjects.get(0).getName());
        Assertions.assertEquals(2, branches.size(), "Number of returned branches");
        Assertions.assertEquals(gitlabBranches.get(0).getName(), branches.get(0));
        Assertions.assertEquals(gitlabBranches.get(1).getName(), branches.get(1));
    }

    @Test
    public void given_nominal_args_when_createCommit_then_works() throws Exception {
        String projectName = gitlabProjects.get(0).getName();
        FileDataSource fileSource = new FileDataSource(new File("src/test/resources/swagger.yml"));
        underTest.createCommit(KATHRA_PROJECTS_DT + File.separator + projectName, "dev", fileSource, null, null, null, false);
        RevCommit commit = git.log().call().iterator().next();
        Assertions.assertEquals(1, commit.getParentCount(), "Commit parent count");
        Assertions.assertEquals(session.getCallerName(), commit.getAuthorIdent().getName(), "Commit author");
    }

    @Test
    public void given_true_uncompress_args_when_createCommit_then_works() throws Exception {
        String projectName = gitlabProjects.get(0).getName();
        FileDataSource fileSource = new FileDataSource(new File("src/test/resources/contentToPush.zip"));
        underTest.createCommit(KATHRA_PROJECTS_DT + File.separator + projectName, "dev", fileSource, null, true, null, false);
        RevCommit commit = git.log().call().iterator().next();
        Assertions.assertEquals(1, commit.getParentCount(), "Commit parent count");
        Assertions.assertEquals(session.getCallerName(), commit.getAuthorIdent().getName(), "Commit author");
    }

    @Test
    public void given_nominal_args_and_tag_when_createCommit_then_works() throws Exception {
        String projectName = gitlabProjects.get(0).getName();
        FileDataSource fileSource = new FileDataSource(new File("src/test/resources/swagger.yml"));
        underTest.createCommit(KATHRA_PROJECTS_DT + File.separator + projectName, "dev", fileSource, null, null, "testTag",false);
        RevCommit commit = git.log().call().iterator().next();
        Assertions.assertEquals(1, git.tagList().call().size(), "Number of tags");
        Assertions.assertEquals(1, commit.getParentCount(), "Commit parent count");
        Assertions.assertEquals(session.getCallerName(), commit.getAuthorIdent().getName(), "Commit author");
    }

    @Test
    public void given_nominal_args_when_getFile_then_works() throws Exception {
        String projectName = gitlabProjects.get(0).getName();
        File workingFolderTest = new File("src/test/resources");
        Mockito.when(gitService.createWorkingFolder()).thenReturn(workingFolderTest);
        FileDataSource result = underTest.getFile(KATHRA_PROJECTS_DT + File.separator + projectName,"dev","testFolder/testFile");
        Assertions.assertNotNull(result);
        Assertions.assertEquals("testFile",result.getName());
    }

    @Test
    public void missing_args_when_getFile_then_throws_exception() throws Exception {
        String projectName = gitlabProjects.get(0).getName();
        assertThrows(IllegalArgumentException.class, () -> {
            underTest.getFile(null, "dev", "testFolder/testFile");
        });
        assertThrows(IllegalArgumentException.class, () -> {
            underTest.getFile("", "dev", "testFolder/testFile");
        });
        assertThrows(IllegalArgumentException.class, () -> {
            underTest.getFile(KATHRA_PROJECTS_DT + File.separator + projectName, null, "testFolder/testFile");
        });
        assertThrows(IllegalArgumentException.class, () -> {
            underTest.getFile(KATHRA_PROJECTS_DT + File.separator + projectName, "", "testFolder/testFile");
        });
        assertThrows(IllegalArgumentException.class, () -> {
            underTest.getFile(KATHRA_PROJECTS_DT + File.separator + projectName, "dev", null);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            underTest.getFile(KATHRA_PROJECTS_DT + File.separator + projectName, "dev", "");
        });

    }

    @Test
    public void missing_file_when_getFile_then_throws_exception() throws Exception {
        String projectName = gitlabProjects.get(0).getName();
        File workingFolderTest = new File("src/test/resources");
        Mockito.when(gitService.createWorkingFolder()).thenReturn(workingFolderTest);
        assertThrows(NotFoundException.class, () -> {
            underTest.getFile(KATHRA_PROJECTS_DT + File.separator + projectName, "dev", "missingFile");
        });

    }


}
