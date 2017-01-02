package com.ngs.stash.externalhooks.hook;/*
 * Copyright 2015 by Alcatel Lucent; All Rights Reserved
 */

import com.atlassian.bitbucket.mail.MailAttachment;
import com.atlassian.bitbucket.mail.MailMessage;
import com.atlassian.bitbucket.mail.MailService;
import com.atlassian.bitbucket.repository.Repository;
import com.atlassian.bitbucket.server.ApplicationPropertiesService;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.*;

/**
 * User: Maty Grosz
 * Date: 28/Apr/2015
 */
public class Utils {

    public static final String CBMS_REPOSITORY_NAME = "cloud";
    public static final String CB_QA_REPOSITORY_NAME = "cbqa";
    public static final String CB_NODE_REPOSITORY_NAME = "cloudnode";

    public static final CharSequence STG_PATTERN = "_stg_";
    public static final String REFS_PREFIX = "refs/heads/";
    public static final String GIT_INSTALL_PATH = "/usr/local/bin/git";

    private static final CharSequence CBQA_IGNORED_REFS_DIRECTORIES = "src/main/resources";

    private static final Logger log = LoggerFactory.getLogger(Utils.class);

    public static void sendEmailToUser(MailService mailService,
                                       Logger log,
                                       String contextId,
                                       String userEmailAddress,
                                       String emailSubject,
                                       String emailText) {

        StringBuffer sendEmailToUserLog = new StringBuffer("Context [" + contextId + "] com.ngs.stash.externalhooks.hook.Utils > sendEmailToUser: user email = " + userEmailAddress + "; emailSubject = " + emailSubject + "; emailText = " + emailText);

        try {
            if (mailService.isHostConfigured()) {
                Set<String> toEmail = Sets.newHashSet();
                toEmail.add(userEmailAddress);

                MailMessage mailMessage = new MailMessage(toEmail, null,
                        null, Sets.<String>newHashSet(), Sets.<MailAttachment>newHashSet(),
                        emailText, emailSubject, null);
                mailService.submit(mailMessage);

                sendEmailToUserLog.append("; email sent successfully");

            } else {
                sendEmailToUserLog.append("; email was not sent as host is not configured");
            }

            log.info(sendEmailToUserLog.toString());

        } catch (Throwable t) {

            sendEmailToUserLog.append(" email sending has failed due to " + t.getMessage());
            log.error(sendEmailToUserLog.toString(), t);
        }
    }

    public static String executeProcess(String whatToExecute,
                                        File executionDirectory,
                                        Logger log,
                                        String... executionParams) throws Exception {

        String result = null;
        List<String> command = new ArrayList<>();
        String commandAsString = null;
        try {

            command.add(whatToExecute);
            command.addAll(Arrays.asList(executionParams));

            StringBuilder commandStringBuilder = new StringBuilder();
            for (String commandPart : command) {
                commandStringBuilder.append(commandPart);
                commandStringBuilder.append(" ");
            }
            commandAsString = commandStringBuilder.toString().trim();

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            processBuilder.directory(executionDirectory);
            Process process = processBuilder.start();
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder stringBuilder = new StringBuilder();
            String line;

            while ((line = bufferedReader.readLine()) != null) {
                log.info(line);
                stringBuilder.append(line);
                stringBuilder.append(System.getProperty("line.separator"));
            }

            result = stringBuilder.toString();
            bufferedReader.close();
            process.waitFor();

            if (process.exitValue() != 0) {
                String errorMessage = "Failed to execute process command '" + commandAsString + "'. Exit value:" + process.exitValue();
                log.error(errorMessage);
                throw new RuntimeException(errorMessage);
            }
        } catch (Exception e) {
            log.error("Failed to execute process command '" + commandAsString + "'. Exception: " + e.getMessage());
            throw e;
        }

        return result;
    }

    public static void callHook(String repositoryName,
                                File hooksDir,
                                MailService mailService,
                                Logger log,
                                String hookFileName,
                                String oldRev,
                                String newRev,
                                String ref,
                                String userName,
                                String userEmailAddress) {

        File script = new File(hookFileName);

        if (new File(hooksDir, hookFileName).exists()) {
            try {
                executeProcess(script.toString(),
                        hooksDir,
                        log,
                        repositoryName,
                        oldRev,
                        newRev,
                        ref,
                        userName);
            } catch (Exception e) {
                sendEmailToUser(mailService, log, ref, userEmailAddress, "Stash > Call hook: Failed to call " + hookFileName, "Failed to call hook " + hookFileName + ": " + e.getMessage());
                log.error("Failed to call hook " + hookFileName + ": " + e.getMessage());
            }
        }
    }

    public static boolean isPushToStgBranch(String branchName) {
        return branchName.contains(STG_PATTERN);
    }

    public static File getRepositoryHookDirectory(ApplicationPropertiesService applicationPropertiesService, Repository repository) {
        return new File(applicationPropertiesService.getRepositoryDir(repository),"hooks");
    }



}
