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

import org.kathra.core.model.SourceRepositoryCommit;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.UUID;

import static java.util.Collections.singleton;

/**
 * @author Jérémy Guillemot <Jeremy.Guillemot@kathra.org>
 */
public class GitService {
    Logger logger = Logger.getLogger(GitService.class.getName());

    public static final String GIT_REMOTE = "origin";
    String currentWorkingDirectory = System.getProperty("java.io.tmpdir");

    public Git cloneProject(String projectName, String branch, File workingFolder, CredentialsProvider cp, String projectUrl, Boolean includeTags) throws GitAPIException, InterruptedException {

        LsRemoteCommand lsRemoteCommand = Git.lsRemoteRepository()
                .setTags(true)
                .setHeads(true)
                .setCredentialsProvider(cp)
                .setRemote(projectUrl);
        Collection<Ref> remoteRefs = callWithRetries(lsRemoteCommand);
        boolean branchExists = false;
        boolean tagExists = false;

        for (Ref remoteRef : remoteRefs) {
            if (remoteRef.getName().equals("refs/heads/" + branch)) {
                branchExists = true;
                break;
            } else if (includeTags && remoteRef.getName().equals("refs/tags/" + branch)) {
                tagExists = true;
                break;
            }
        }
        Git git;
        CloneCommand cloneCommand;
        if (branchExists) {
            cloneCommand = Git.cloneRepository()
                    .setBranchesToClone(singleton("refs/heads/" + branch))
                    .setBranch("refs/heads/" + branch)
                    .setURI(projectUrl)
                    .setDirectory(getGitFolderPath(workingFolder, projectName))
                    .setCredentialsProvider(cp);
            git = callWithRetries(cloneCommand);
        } else if (tagExists) {
            cloneCommand = Git.cloneRepository()
                    .setURI(projectUrl)
                    .setDirectory(getGitFolderPath(workingFolder, projectName))
                    .setCredentialsProvider(cp);
            git = callWithRetries(cloneCommand);
            git.checkout().setName(branch).call();
        } else {
            cloneCommand = Git.cloneRepository()
                    .setURI(projectUrl)
                    .setDirectory(getGitFolderPath(workingFolder, projectName))
                    .setCredentialsProvider(cp);
            git = callWithRetries(cloneCommand);
            git.branchRename().setNewName(branch).call();
        }
        return git;
    }

    public File createWorkingFolder() throws IOException {
        String sessionID = "KathraSourceManager_WorkingFolder_" + UUID.randomUUID().toString();
        int i = 0;
        File workingFolder = new File(currentWorkingDirectory + File.separator + sessionID);
        while (i < 3) {
            ++i;
            if (!workingFolder.exists())
                break;
            workingFolder = new File(currentWorkingDirectory + File.separator + "KathraSourceManager_WorkingFolder_" + UUID.randomUUID().toString());
            if ((i >= 3) && (workingFolder.exists())) {
                FileUtils.deleteDirectory(workingFolder);
            }
        }
        workingFolder.mkdirs();
        return workingFolder;
    }

    private File getGitFolderPath(File workingFolder, String projectName) {
        return new File(workingFolder.getAbsolutePath() + File.separator + projectName);
    }

    public Iterable<PushResult> pushToGitRepoWithUserCredentials(CredentialsProvider cp, Git git) throws GitAPIException, InterruptedException {
        PushCommand pushCommand = git.push();
        pushCommand.setPushTags();
        pushCommand.setPushAll();
        pushCommand.setRemote(GIT_REMOTE);
        pushCommand.setCredentialsProvider(cp);

        return callWithRetries(pushCommand);
    }

    public Iterable<PushResult> pushTagOnlyToGitRepoWithUserCredentials(CredentialsProvider cp, Git git, boolean force) throws GitAPIException, InterruptedException {
        PushCommand pushCommand = git.push();
        pushCommand.setPushTags();
        pushCommand.setRemote(GIT_REMOTE);
        pushCommand.setCredentialsProvider(cp);
        pushCommand.setForce(force);
        return callWithRetries(pushCommand);
    }

    public SourceRepositoryCommit createCommit(Git git, String username, String message) throws GitAPIException {
        git.add().addFilepattern(".").call();
        git.add().setUpdate(true).addFilepattern(".").call();
        RevCommit call = git.commit()
                .setAuthor(username, username + "@kathra.org")
                .setMessage(message)
                .setCommitter(username, username + "@kathra.org")
                .setAllowEmpty(false)
                .call();
        SourceRepositoryCommit sourceRepositoryCommit = new SourceRepositoryCommit();
        sourceRepositoryCommit.authorEmail(call.getAuthorIdent().getEmailAddress());
        sourceRepositoryCommit.authorName(call.getAuthorIdent().getName());
        sourceRepositoryCommit.committerEmail(call.getCommitterIdent().getEmailAddress());
        sourceRepositoryCommit.committerName(call.getCommitterIdent().getName());
        sourceRepositoryCommit.createdAt(String.valueOf(call.getCommitTime()));
        sourceRepositoryCommit.message(call.getFullMessage());
        sourceRepositoryCommit.id(call.getName());
        sourceRepositoryCommit.shortId(call.getId().abbreviate(8).name());
        sourceRepositoryCommit.title(call.getShortMessage());
        return sourceRepositoryCommit;
    }

    public void createTag(Git git, String tag, boolean force) throws GitAPIException {
        git.tag().setForceUpdate(force).setName(tag).call();
    }



    private <T> T callWithRetries(TransportCommand<? extends GitCommand,T> command) throws GitAPIException, InterruptedException {
        return callWithRetries(command, 5, 1000);
    }

    private <T> T callWithRetries(TransportCommand<? extends GitCommand,T> command, int nbMaxAttempts, int millisBetweenAttemps) throws GitAPIException, InterruptedException {
        int attempt=1;
        GitAPIException exception = null;

        while (attempt<=nbMaxAttempts){
            try {
                return command.call();
            } catch (GitAPIException e) {
                logger.warn("Git command "+command.getClass().getName()+"failed ("+attempt+"/"+nbMaxAttempts+")");
                Thread.sleep(millisBetweenAttemps);
                exception = e;
                attempt++;
            }
        }
        throw exception;
    }
}