package com.ngs.stash.externalhooks.hook;

import com.atlassian.bitbucket.hook.repository.*;
import com.atlassian.bitbucket.mail.MailService;
import com.atlassian.bitbucket.pull.*;
import com.atlassian.bitbucket.repository.*;
import com.atlassian.bitbucket.user.*;
import com.atlassian.bitbucket.auth.*;
import com.atlassian.bitbucket.permission.*;
import com.atlassian.bitbucket.server.*;
import com.atlassian.bitbucket.util.Page;
import com.atlassian.bitbucket.util.PageRequestImpl;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static com.ngs.stash.externalhooks.hook.Utils.*;

public class ExternalAsyncPostReceiveHook implements AsyncPostReceiveRepositoryHook
{
    private static final PageRequestImpl ALL = new PageRequestImpl(0, 10000);

    private static final Logger log = LoggerFactory.getLogger(ExternalAsyncPostReceiveHook.class);
    private static final String STASH_REVIEWER_GROUP = "stash-reviewers";


    private AuthenticationContext authCtx;
    private PermissionService permissions;
    private RepositoryService repoService;
    private ApplicationPropertiesService properties;
    private UserService userService = null;
    private MailService mailService;
    private PermissionService userPermissionsService = null;
    private PullRequestService pullRequestService = null;




    public ExternalAsyncPostReceiveHook(
        AuthenticationContext authenticationContext,
        PermissionService permissions,
        RepositoryService repoService,
        ApplicationPropertiesService properties,
        UserService userService,
        MailService mailService,
        PermissionService userPermissionsService,
        PullRequestService pullRequestService
    ) {
        this.authCtx = authenticationContext;
        this.permissions = permissions;
        this.repoService = repoService;
        this.properties = properties;
        this.userService = userService;
        this.mailService = mailService;
        this.userPermissionsService = userPermissionsService;
        this.pullRequestService = pullRequestService;

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
                Page<String> page = this.userService.findGroupsByUser(currentUser.getName(), new PageRequestImpl(0, 100));
                Iterator<String> tempIter = page.getValues().iterator();
                Set<String> groupNames = new HashSet<String>();
                while (tempIter.hasNext()) {
                    String groupName = tempIter.next();
                    if (groupName.startsWith(STASH_REVIEWER_GROUP)) {
                        groupNames.add(groupName);
                    }
                }

                if (groupNames.size() == 0) {
                    log.error("Context [" + postReceiveContextId + "]: CreatePullRequestRepositoryHook > postReceive > groups were not found for committer " + currentUser.getName() + ". A pull request cannot be created.");
                    sendEmailToUser(mailService, log, postReceiveContextId, currentUser.getEmailAddress(), "Stash: Create Pull Request Failure", "Pushed to branchName = " + branchName + "; refId = " + currentRefChangeId + ". No group of reviewers was found for committer " + currentUser.getName() + ". A pull request cannot be created.");
                    return;
                }

                Set<String> reviewersList = getReviewersList(postReceiveContextId, context.getRepository(), groupNames, currentUser);

                if (reviewersList.size() == 0) {
                    log.error("Context [" + postReceiveContextId + "]: CreatePullRequestRepositoryHook > postReceive > reviewers were not found for committer " + currentUser.getName() +". A pull request cannot be created.");
                    sendEmailToUser(mailService, log, postReceiveContextId, currentUser.getEmailAddress(), "Stash: Create Pull Request Failure", "Pushed to branchName = " + branchName + "; refId = " + currentRefChangeId + ". No reviewers were found for committer " + currentUser.getName() + ". A pull request cannot be created.");
                    return;
                }

                if (refChange.getType() == RefChangeType.ADD) {
                    log.info("Context [" + postReceiveContextId + "]: The ref is added " + currentRefChangeId + ". New pull request will be created");

                    if (!createPullReq(postReceiveContextId, refChange, context, toBranch, currentUser, reviewersList)) {
                        return;
                    }

                } else if (refChange.getType() == RefChangeType.UPDATE) {
                    log.info("Context [" + postReceiveContextId + "]: The ref is Update " + refChange.getRefId()+ ". If existing pull request is closed, create a new one.");
                    boolean pullRequestClosed = true;
//                    for (final PullRequest pr : iterateOutgoingPullRequests(context, refChange)) {
//                        if (pr.getState() == PullRequestState.OPEN){
//                            pullRequestClosed = false;
//                            break;
//                        }
//                    }

                    if (pullRequestClosed){
                        if (!createPullReq(postReceiveContextId, refChange, context, toBranch, currentUser, reviewersList)) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private Set<String> getReviewersList(String postReceiveContextId, Repository currentRepository, Set<String> groupNames, ApplicationUser stashUser) {

        StringBuilder sb = new StringBuilder("Context [" + postReceiveContextId + "]: CreatePullRequestRepositoryHook > getReviewersList: Code committer is [" + stashUser.getName() + "]. Groups of reviewers: {");
        String currentUserName = stashUser.getName();
        Set<String> users = new HashSet<String>();

        try {

            for (String groupName : groupNames){
                sb.append("{Group = ").append(groupName).append(". Users = ");
                Page<?> page = userService.findUsersByGroup(groupName,new PageRequestImpl(0,100));
                for(Object user : page.getValues()){
                    String userName = ((ApplicationUser)user).getName();
                    if (!userName.equals(currentUserName) && userPermissionsService.hasRepositoryPermission((ApplicationUser)user, currentRepository, Permission.REPO_READ)) {
                        users.add(userName);
                        sb.append(userName).append(", ");
                    }
                }
                sb.append("}");
            }


            sb.append("}");
            log.info(sb.toString());

        } catch (Throwable t) {
            sb.append(" FAILED. Error message is: ").append(t.getMessage());
            sendEmailToUser(mailService, log, postReceiveContextId, stashUser.getEmailAddress(), "Stash: Get List of Reviewers Failure", sb.toString());
            log.error(sb.toString(), t);
            users.clear();
        }

        return users;
    }

    private boolean createPullReq(String postReceiveContextId, RefChange refChange, RepositoryHookContext context, String toBranch, ApplicationUser stashUser, Set<String> reviewersList) {

        boolean createPullReqSuccessfully = true;

        StringBuilder pullRequestLog = new StringBuilder("Context [" + postReceiveContextId + "] repository " + context.getRepository() + ": CreatePullRequestRepositoryHook > createPullReq: Pull Request creation by Stash hook: ");

        try {

            String pullRequestTitle = "Pull Request created by Stash hook";

            PullRequest pr = pullRequestService.create(
                    pullRequestTitle,
                    "Pull Request created by Stash hook: Commit done by user " + stashUser.getName() + " ; Commit from branch " + refChange.getRefId().substring(REFS_PREFIX.length()) + " ; Commit is about to be pushed to branch " + toBranch + " after pull request is approved",
                    reviewersList,
                    context.getRepository(),
                    refChange.getRefId().substring(REFS_PREFIX.length()),
                    context.getRepository(),
                    toBranch);

            pullRequestLog.append("Create PullRequest number: ").append(pr.getId())
                    .append(" ; Commit done by user ")
                    .append(stashUser.getName())
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
                    .append(stashUser.getName())
                    .append(" ; Commit from branch ")
                    .append(refChange.getRefId().substring(REFS_PREFIX.length()));

            sendEmailToUser(mailService, log, postReceiveContextId, stashUser.getEmailAddress(), "Stash: Create Pull Request Failure", pullRequestLog.toString());

            log.error(pullRequestLog.toString(), t);
        }

        return createPullReqSuccessfully;
    }



//    private Iterable<PullRequest> iterateOutgoingPullRequests(final RepositoryHookContext context, final RefChange refChange) {
//        return new PagedIterable<PullRequest>(new PageProvider<PullRequest>() {
//            public Page<PullRequest> get(PageRequest pageRequest) {
//                //noinspection ConstantConditions
//                return pullRequestService.findInDirection(PullRequestDirection.OUTGOING, context.getRepository().getId(), refChange.getRefId(), null, null, pageRequest);
//                PullRequestSearchRequest request = PullRequestSearchRequest.Builder.fromRefId().fromRefIds().build();
//                pullRequestService.search(request, pageRequest);
//            }
//        }, ALL);
//    }
}
