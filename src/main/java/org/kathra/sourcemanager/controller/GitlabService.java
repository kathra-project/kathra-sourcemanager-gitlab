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

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.kathra.core.model.Membership;
import org.kathra.utils.Session;
import org.kathra.utils.KathraException;
import org.kathra.utils.sanitizing.SanitizeUtils;
import org.apache.log4j.Logger;
import org.gitlab.api.GitlabAPI;
import org.gitlab.api.GitlabAPIException;
import org.gitlab.api.TokenType;
import org.gitlab.api.models.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author Jérémy Guillemot <Jeremy.Guillemot@kathra.org>
 */
public class GitlabService {
    public static final String PRIVATE_TOKEN = "PRIVATE-TOKEN";
    String host;
    String apiToken;
    private GitlabAPI adminClient;
    private GitlabAPI userClient;
    Session session;
    String impersonationTokenForUser;
    Logger logger = Logger.getLogger(GitlabService.class.getName());

    public GitlabService(String host, String apiToken, Session session) throws Exception {
        this.host = host;
        this.apiToken = apiToken;
        this.session = session;
        adminClient = GitlabAPI.connect(host, apiToken);
        userClient = getGitlabClientForUser();
    }

    public GitlabService(String host, String apiToken, Session session, GitlabAPI adminClient, GitlabAPI userClient) throws Exception {
        this.host = host;
        this.apiToken = apiToken;
        this.session = session;
        this.adminClient = adminClient;
        this.userClient = userClient;
    }

    private GitlabAPI getGitlabClientForUser() throws UnirestException, IOException {
        this.impersonationTokenForUser = retrieveImpersonationTokenForUser();
        if (impersonationTokenForUser == null) throw new IOException("Unable to retrieve user gitlab token");
        return GitlabAPI.connect(host, impersonationTokenForUser, TokenType.PRIVATE_TOKEN);
    }

    public String retrieveImpersonationTokenForUser() throws UnirestException, IOException {
        GitlabUser user = adminClient.getUserViaSudo(session.getCallerName());

        HttpResponse<JsonNode> jsonNodeHttpResponse = Unirest.get(host + "/api/v4/users/{id}/impersonation_tokens")
                .routeParam("id", user.getId().toString())
                .header(PRIVATE_TOKEN, apiToken)
                .asJson();

        JSONArray array = jsonNodeHttpResponse.getBody().getArray();

        if (array.length() > 0) {
            JSONObject jsonObject;
            for (Object o : array) {
                jsonObject = (JSONObject) o;
                if (jsonObject.get("name").equals("KathraGitlabSourceManager") && jsonObject.get("revoked").equals(false)) {
                    return (String) jsonObject.get("token");
                }
            }
        }

        jsonNodeHttpResponse = Unirest.post(host + "/api/v4/users/{id}/impersonation_tokens")
                .header(PRIVATE_TOKEN, apiToken)
                .routeParam("id", user.getId().toString())
                .queryString("name", "KathraGitlabSourceManager")
                .queryString("scopes[]", Arrays.asList("api", "read_user"))
                .asJson();
        return (String) jsonNodeHttpResponse.getBody().getObject().get("token");
    }

    public GitlabAPI getAdminClient() {
        return adminClient;
    }

    public GitlabAPI getUserClient() {
        return userClient;
    }

    public String getImpersonationTokenForUser() {
        return impersonationTokenForUser;
    }

    public GitlabProject getProjectFromPath(String sourceRepositoryPath) throws IOException {
        sourceRepositoryPath = SanitizeUtils.sanitizePathParameter(sourceRepositoryPath);
        int i = sourceRepositoryPath.lastIndexOf('/');
        String sourceRespositoryName;
        String namespace;
        if (i != -1) {
            namespace = sourceRepositoryPath.substring(0, i);
            sourceRespositoryName = sourceRepositoryPath.substring(i + 1);
        } else {
            namespace = "";
            sourceRespositoryName = sourceRepositoryPath;
        }

        return adminClient.getProject(namespace, sourceRespositoryName);
    }

    public void addMemberships(List<Membership> memberships) throws Exception {
        for (Membership m : memberships) {
            GitlabUser user;
            try {
                user = adminClient.getUserViaSudo(m.getMemberName());
            } catch (FileNotFoundException e) {
                logger.error("Unable to find member " + m.getMemberName());
                break;
            }

            try {
                GitlabProject project;
                project = getProjectFromPath(m.getPath());
                adminClient.addProjectMember(project, user, membershipRoleToGitlabAccessLevel(m.getRole(),false));
            } catch (FileNotFoundException e) {
                GitlabGroup group = adminClient.getGroup(SanitizeUtils.sanitizePathParameter(m.getPath()));
                try {
                    adminClient.addGroupMember(group, user, membershipRoleToGitlabAccessLevel(m.getRole(),true));
                } catch (GitlabAPIException e2) {
                    if (e2.getResponseCode() == 409) break;
                    else throw e;
                }
            } catch (GitlabAPIException e) {
                if (e.getResponseCode() == 409) break;
                else throw e;
            }
        }
    }

    public void createDeployKey(String keyName, String sshPublicKey, String sourceRepositoryPath) throws Exception {
        adminClient.createDeployKey(getProjectFromPath(sourceRepositoryPath).getId(), keyName, sshPublicKey);
    }

    /**
     * Delete multiple memberships in specified projects
     *
     * @param memberships (required)
     * @return List<Membership>
     */
    public void deleteMemberships(List<Membership> memberships) throws Exception {
        for (Membership m : memberships) {
            GitlabUser user = adminClient.getUserViaSudo(m.getMemberName());
            try {
                GitlabProject project;
                project = getProjectFromPath(m.getPath());
                adminClient.deleteProjectMember(project, user);
            } catch (FileNotFoundException e) {
                GitlabGroup group = adminClient.getGroup(SanitizeUtils.sanitizePathParameter(m.getPath()));
                adminClient.deleteGroupMember(group, user);
            }
        }
    }

    /**
     * Retrieve memberships in specified project
     *
     * @param sourceRepositoryPath SourceRepository's Path (required)
     * @param memberType           Type of memberships to retrieve, return all types if not specified (optional)
     * @return List<Membership>
     */
    public List<Membership> getMemberships(String sourceRepositoryPath, String memberType) throws Exception {
        List<Membership> memberships = new ArrayList();
        GitlabProject project;
        try {
            project = getProjectFromPath(sourceRepositoryPath);
            List<GitlabProjectMember> projectMembers = adminClient.getProjectMembers(project);
            for (GitlabProjectMember m : projectMembers) {
                memberships.add(new Membership()
                        .memberName(m.getUsername())
                        .memberType(Membership.MemberTypeEnum.USER)
                        .role(gitlabAccessLevelToMembershipRole(m.getAccessLevel())));
            }
        } catch (FileNotFoundException e) {
            GitlabGroup group;
            try {
                group = adminClient.getGroup(SanitizeUtils.sanitizePathParameter(sourceRepositoryPath));
            } catch (FileNotFoundException e2) {
                throw new KathraException("Unable to find project or group " + sourceRepositoryPath, e2.getCause(), KathraException.ErrorCode.NOT_FOUND);
            }

            List<GitlabGroupMember> groupMembers = adminClient.getGroupMembers(group);

            for (GitlabGroupMember m : groupMembers) {
                memberships.add(new Membership()
                        .memberName(m.getUsername())
                        .memberType(Membership.MemberTypeEnum.USER)
                        .role(gitlabAccessLevelToMembershipRole(m.getAccessLevel())));
            }
        }
        return memberships;
    }

    private Membership.RoleEnum gitlabAccessLevelToMembershipRole(GitlabAccessLevel gitlabAccessLevel) {
        if (gitlabAccessLevel.accessValue <= 20) {
            return Membership.RoleEnum.GUEST;
        } else if (gitlabAccessLevel.accessValue == 30) {
            return Membership.RoleEnum.CONTRIBUTOR;
        } else {
            return Membership.RoleEnum.MANAGER;
        }
    }

    private GitlabAccessLevel membershipRoleToGitlabAccessLevel(Membership.RoleEnum membershipRole, boolean isGroup) {


        if (membershipRole.equals(Membership.RoleEnum.GUEST)) {
            return GitlabAccessLevel.Reporter;
        } else if (membershipRole.equals(Membership.RoleEnum.CONTRIBUTOR)) {
            return GitlabAccessLevel.Developer;
        } else if (membershipRole.equals(Membership.RoleEnum.MANAGER)) {
            if (isGroup) return GitlabAccessLevel.Owner;
            else return GitlabAccessLevel.Master;
        }
        return GitlabAccessLevel.Guest;
    }
}
