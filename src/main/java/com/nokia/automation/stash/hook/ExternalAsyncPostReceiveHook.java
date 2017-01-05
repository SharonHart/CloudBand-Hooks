package com.nokia.automation.stash.hook;/*
 * Copyright 2016 by Nokia; All Rights Reserved
 */

import com.atlassian.bitbucket.hook.repository.*;
import com.atlassian.bitbucket.mail.MailService;
import com.atlassian.bitbucket.pull.*;
import com.atlassian.bitbucket.repository.*;
import com.atlassian.bitbucket.user.*;
import com.atlassian.bitbucket.auth.*;
import com.atlassian.bitbucket.permission.*;
import com.atlassian.bitbucket.util.Operation;
import com.atlassian.bitbucket.util.Page;
import com.atlassian.bitbucket.util.PageRequestImpl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.nokia.automation.stash.hook.Utils.*;

public class ExternalAsyncPostReceiveHook implements AsyncPostReceiveRepositoryHook {

    private static final Logger log = LoggerFactory.getLogger(ExternalAsyncPostReceiveHook.class);

    private AuthenticationContext authCtx;
    private UserService userService = null;
    private MailService mailService;
    private SecurityService securityService = null;
    private PullRequestService pullRequestService = null;
    private PermissionAdminService adminService = null;

    public ExternalAsyncPostReceiveHook(
            AuthenticationContext authenticationContext,
            UserService userService,
            MailService mailService,
            SecurityService securityService,
            PullRequestService pullRequestService,
            PermissionAdminService adminService
    ) {
        this.authCtx = authenticationContext;
        this.userService = userService;
        this.mailService = mailService;
        this.securityService = securityService;
        this.pullRequestService = pullRequestService;
        this.adminService = adminService;

    }

    public class SecurityImpersonate implements Operation<Page<PermittedGroup>, RuntimeException>{

        Repository repository;

        public SecurityImpersonate(Repository _repository) {
            this.repository = _repository;
        }

        @Override
        public Page<PermittedGroup> perform() throws RuntimeException {
            return adminService.findGroupsWithRepositoryPermission(this.repository, BITBUCKET_REVIEWER_GROUP, new PageRequestImpl(0, 1000));
        }
    }


    @Override
    public void postReceive(RepositoryHookContext context, Collection<RefChange> refChanges) {

        String postReceiveContextId = UUID.randomUUID().toString();

        ApplicationUser currentUser = authCtx.getCurrentUser();
        if (!refChanges.isEmpty()) {
            for (RefChange refChange : refChanges) {
                String currentRefChangeId = refChange.getRefId();
                String branchName = currentRefChangeId.substring(REFS_PREFIX.length());

                if (!isPushToStgBranch(branchName)) {
                    log.info("Context [" + postReceiveContextId + "]: CreatePullRequestRepositoryHook > postReceive > pushed to branchName = " + branchName + ". No pull request is needed");
                    break;
                }

                String toBranch = branchName.substring(0, branchName.indexOf(STG_PATTERN.toString()));
                ApplicationUser adminApplicationUser = userService.getUserByName(BITBUCKET_ADMIN);
                EscalatedSecurityContext securityContext = securityService.impersonating(adminApplicationUser, "getting repo groups");
                final Page<PermittedGroup> groupsPage = securityContext.call(new SecurityImpersonate(context.getRepository()));

                Iterator<PermittedGroup> tempIter = groupsPage.getValues().iterator();
                Set<String> groupNames = new HashSet<String>();
                while (tempIter.hasNext()) {
                    String groupName = tempIter.next().getGroup();
                    groupNames.add(groupName);
                }


                if (groupNames.size() == 0) {
                    log.error("Context [" + postReceiveContextId + "]: CreatePullRequestRepositoryHook > postReceive > groups were not found for committer " + currentUser.getName() + ". A pull request cannot be created.");
                    sendEmailToUser(mailService, log, postReceiveContextId, currentUser.getEmailAddress(), "Bitbucket: Create Pull Request Failure", "Pushed to branchName = " + branchName + "; refId = " + currentRefChangeId + ". No group of reviewers was found for committer " + currentUser.getName() + ". A pull request cannot be created.");
                    return;
                }

                Set<String> reviewersList = getReviewersList(postReceiveContextId, context.getRepository(), groupNames, currentUser);

                if (reviewersList.size() == 0) {
                    log.error("Context [" + postReceiveContextId + "]: CreatePullRequestRepositoryHook > postReceive > reviewers were not found for committer " + currentUser.getName() + ". A pull request cannot be created.");
                    sendEmailToUser(mailService, log, postReceiveContextId, currentUser.getEmailAddress(), "Bitbucket: Create Pull Request Failure", "Pushed to branchName = " + branchName + "; refId = " + currentRefChangeId + ". No reviewers were found for committer " + currentUser.getName() + ". A pull request cannot be created.");
                    return;
                }

                if (refChange.getType() == RefChangeType.ADD) {
                    log.info("Context [" + postReceiveContextId + "]: The ref is added " + currentRefChangeId + ". New pull request will be created");

                    if (!createPullReq(postReceiveContextId, refChange, context, toBranch, currentUser, reviewersList)) {
                        return;
                    }

                } else if (refChange.getType() == RefChangeType.UPDATE) {
                    log.info("Context [" + postReceiveContextId + "]: The ref is Update " + refChange.getRefId() + ". If existing pull request is closed, create a new one.");
                    boolean pullRequestClosed = true;
                    if (iterateOutgoingPullRequests(context, refChange).iterator().hasNext()){
                        pullRequestClosed = false;
                    }

                    if (pullRequestClosed) {
                        if (!createPullReq(postReceiveContextId, refChange, context, toBranch, currentUser, reviewersList)) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private Set<String> getReviewersList(String postReceiveContextId, Repository currentRepository, Set<String> groupNames, ApplicationUser currentUser) {

        StringBuilder sb = new StringBuilder("Context [" + postReceiveContextId + "]: CreatePullRequestRepositoryHook > getReviewersList: Code committer is [" + currentUser.getName() + "]. Groups of reviewers: {");
        String currentUserName = currentUser.getName();
        Set<String> users = new HashSet<String>();

        try {

            for (String group : groupNames) {
                sb.append("{Group = ").append(group).append(". Users = ");
                Page<ApplicationUser> usersPage = userService.findUsersByGroup(group, new PageRequestImpl(0, 1000));
                boolean userInGroup = false;
                for (ApplicationUser user : usersPage.getValues()) {
                    if (user.getName().equals(currentUserName)) {
                        userInGroup = true;
                    }
                }
                if(userInGroup) {
                    for (ApplicationUser user : usersPage.getValues()) {
                        String userName = user.getName();
                        if (!userName.equals(currentUserName)) {
                            users.add(userName);
                            sb.append(userName).append(", ");
                        }
                    }
                    sb.append("}");
                }
            }

            sb.append("}");
            log.info(sb.toString());

        } catch (Throwable t) {
            sb.append(" FAILED. Error message is: ").append(t.getMessage());
            sendEmailToUser(mailService, log, postReceiveContextId, currentUser.getEmailAddress(), "Bitbucket: Get List of Reviewers Failure", sb.toString());
            log.error(sb.toString(), t);
            users.clear();
        }

        return users;
    }

    private boolean createPullReq(String postReceiveContextId, RefChange refChange, RepositoryHookContext context, String toBranch, ApplicationUser currentUser, Set<String> reviewersList) {

        boolean createPullReqSuccessfully = true;

        StringBuilder pullRequestLog = new StringBuilder("Context [" + postReceiveContextId + "] repository " + context.getRepository() + ": CreatePullRequestRepositoryHook > createPullReq: Pull Request creation by Bitbucket hook");

        try {

            String pullRequestTitle = "[AUTO PR] From " + refChange.getRefId().substring(REFS_PREFIX.length());

            PullRequest pr = pullRequestService.create(
                    pullRequestTitle,
                    "Pull Request created by Bitbucket hook: Commit done by user " + currentUser.getName() + ".\n Commit from branch " + refChange.getRefId().substring(REFS_PREFIX.length()) + ".\n Commit is about to be pushed to branch " + toBranch + " after pull request is approved",
                    reviewersList,
                    context.getRepository(),
                    refChange.getRefId().substring(REFS_PREFIX.length()),
                    context.getRepository(),
                    toBranch);

            pullRequestLog.append("Create PullRequest number: ").append(pr.getId())
                    .append(" ; Commit done by user ")
                    .append(currentUser.getName())
                    .append(" ; Commit from branch ")
                    .append(refChange.getRefId().substring(REFS_PREFIX.length()))
                    .append(" ; Commit is about to be pushed to branch ")
                    .append(toBranch)
                    .append(" after pull request is approved");
            log.info(pullRequestLog.toString());

        } catch (Throwable t) {

            createPullReqSuccessfully = false;

            pullRequestLog.append(" FAILED. Error message is: ").append(t.getMessage())
                    .append(" ; Commit done by user ")
                    .append(currentUser.getName())
                    .append(" ; Commit from branch ")
                    .append(refChange.getRefId().substring(REFS_PREFIX.length()));

            sendEmailToUser(mailService, log, postReceiveContextId, currentUser.getEmailAddress(), "Bitbucket: Create Pull Request Failure", pullRequestLog.toString());
            log.error("Added reviwers: " + reviewersList.toString());
            log.error(pullRequestLog.toString(), t);
        }

        return createPullReqSuccessfully;
    }


    private Iterable<PullRequest> iterateOutgoingPullRequests(final RepositoryHookContext context, final RefChange refChange) {
        Page<PullRequest> prPage= pullRequestService.search(new PullRequestSearchRequest.Builder()
                .state(PullRequestState.OPEN)
                .fromRepositoryId(context.getRepository().getId())
                .fromRefId(refChange.getRefId())
                .build(), new PageRequestImpl(0, 100));

        return prPage.getValues();

    }
}