package com.nokia.automation.stash.hook;/*
 * Copyright 2016 by Nokia; All Rights Reserved
 */

import com.atlassian.bitbucket.mail.MailAttachment;
import com.atlassian.bitbucket.mail.MailMessage;
import com.atlassian.bitbucket.mail.MailService;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import java.util.*;

public class Utils {

    public static final CharSequence STG_PATTERN = "_stg_";
    public static final String REFS_PREFIX = "refs/heads/";
    public static final String BITBUCKET_REVIEWER_GROUP = "stash-reviewers";
    public static final String BITBUCKET_ADMIN = "jenkins";


    public static void sendEmailToUser(MailService mailService,
                                       Logger log,
                                       String contextId,
                                       String userEmailAddress,
                                       String emailSubject,
                                       String emailText) {

        StringBuffer sendEmailToUserLog = new StringBuffer("Context [" + contextId + "] Utils > sendEmailToUser: user email = " + userEmailAddress + "; emailSubject = " + emailSubject + "; emailText = " + emailText);

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


    public static boolean isPushToStgBranch(String branchName) {
        return branchName.contains(STG_PATTERN);
    }




}
