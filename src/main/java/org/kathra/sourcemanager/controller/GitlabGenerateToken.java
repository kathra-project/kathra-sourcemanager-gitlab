package org.kathra.sourcemanager.controller;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.kathra.core.model.User;
import org.kathra.resourcemanager.client.GroupsClient;
import org.kathra.resourcemanager.client.UsersClient;
import org.kathra.sourcemanager.Config;
import org.kathra.sourcemanager.KeycloackSession;
import org.kathra.utils.KathraSessionManager;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;

public class GitlabGenerateToken implements Processor {

    private final static Logger logger = Logger.getLogger(GitlabGenerateToken.class.getName());

    private final GroupsClient groupsClient;
    private final UsersClient usersClient;
    private final static String SCRIPT="generateToken.sh";
    private final String gitlabHost;
    private final String keycloakHost;

    private static GitlabGenerateToken instance;

    public static GitlabGenerateToken getInstance() {
        if (instance == null) {
            Config config = new Config();
            KathraSessionManager sessionManager = new KeycloackSession(new User().name(config.getLoginKeycloak()).password(config.getPasswordKeycloak()));
            GroupsClient groupsClient = new GroupsClient(config.getResourceManager(), sessionManager);
            UsersClient usersClient = new UsersClient(config.getResourceManager(), sessionManager);
            instance = new GitlabGenerateToken(groupsClient, usersClient, config.getGitlabUrl().replaceAll("https://", "").replaceAll("http://", ""), config.getKeycloakHost());
        }
        return instance;
    }

    public GitlabGenerateToken(GroupsClient groupsClient, UsersClient usersClient, String gitlabHost, String keycloakHost) {
        this.groupsClient = groupsClient;
        this.usersClient = usersClient;
        this.keycloakHost = keycloakHost;
        this.gitlabHost = gitlabHost;
    }

    public void process(Exchange exchange) throws Exception {
        copyScriptIntoFs("/tmp/"+SCRIPT);
        logger.info("execute");
        groupsClient.getGroups()
                    .parallelStream()
                    .map(g -> g.getTechnicalUser())
                    .filter(Objects::nonNull)
                    .forEach(u -> execute(u));
    }

    public void execute(User user) {
        logger.info("execute for user "+ user.getId());
        try {
            User userWidthDetails = usersClient.getUser(user.getId());

            if (StringUtils.isEmpty(userWidthDetails.getPassword())) {
                throw new IllegalStateException("Not password defined for user:" + userWidthDetails.getName());
            }

            String token = userWidthDetails.getMetadata() != null ? (String) userWidthDetails.getMetadata().get("GITLAB_TOKEN") : null;
            if (StringUtils.isEmpty(token)) {
                logger.info("GITLAB_TOKEN undefined for user:"+ userWidthDetails.getName());
                token = generateToken(gitlabHost, keycloakHost, userWidthDetails.getName(), userWidthDetails.getPassword());
                usersClient.updateUserAttributes(user.getId(), new User().putMetadataItem("GITLAB_TOKEN", token));
                logger.info("GITLAB_TOKEN updated for user:"+ userWidthDetails.getName());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void copyScriptIntoFs(String path) throws IOException {
        File file = new File(path);
        if (file.isFile())
            return;
        ClassLoader classLoader = getClass().getClassLoader();
        URL resource = classLoader.getResource(SCRIPT);
        FileUtils.copyURLToFile(resource, file);
        Set<PosixFilePermission> perms = new HashSet<>();
        perms.add(PosixFilePermission.GROUP_EXECUTE);
        perms.add(PosixFilePermission.OWNER_EXECUTE);
        perms.add(PosixFilePermission.OTHERS_EXECUTE);
        perms.add(PosixFilePermission.GROUP_EXECUTE);
        perms.add(PosixFilePermission.OWNER_READ);
        perms.add(PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(file.toPath(), perms);
    }

    private String generateToken(String gitlabHost, String keycloakHost, String username, String password) throws IOException {
        File tokenFile = File.createTempFile("/tmp/generate.gitlab",".txt");
        String[] cmd = new String[]{"/bin/bash", "/tmp/"+SCRIPT, gitlabHost, keycloakHost, username, password, tokenFile.getAbsolutePath()};
        logger.info("generateToken:"+String.join(",", cmd));
        Runtime rt = Runtime.getRuntime();
        Process proc = rt.exec(cmd);


        try (InputStream stdIn = proc.getInputStream();
            InputStreamReader isr = new InputStreamReader(stdIn);
            BufferedReader br = new BufferedReader(isr)) {

            int exitVal = 0;
            try {
                exitVal = proc.waitFor();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            String line;
            while ((line = br.readLine()) != null)
                logger.info(line);

            logger.info("generateToken:" + String.join(",", cmd) + " exit value:" + exitVal);
            if (exitVal == 0) {
                return FileUtils.readFileToString(tokenFile, "UTF-8");
            }
        }
        throw new IllegalStateException("Not token generated");
    }


}
