package com.hnp.filemanagement.identity.domain;

import com.hnp.filemanagement.audit.domain.ActionEnum;
import com.hnp.filemanagement.audit.domain.ActionHistoryService;
import com.hnp.filemanagement.audit.domain.EntityEnum;
import com.hnp.filemanagement.identity.persistence.OnlineAccount;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.ActiveUserSessions;
import com.hnp.filemanagement.identity.security.ActiveUserSessions.SignedInSession;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Who is signed in (2.16.0): each person with a session on this instance, their sessions - when and
 * where each signed in, what browser, its last request - and a way to end one session, or all of a
 * person's. An ended session's next request lands on the login page; the account is untouched (to keep
 * someone out, disable the account, which ends its sessions too).
 *
 * <p>What is listed is this instance's memory ({@link ActiveUserSessions}): the browser's sessions only -
 * a request with an API key or the v1 password keeps none - and a session lasts until it is signed out of
 * or idle past the server's session timeout, so a browser closed without signing out stays listed until
 * then; its last request says how long ago it was used.
 *
 * <p>Ending a session of an account that holds ADMIN asks ADMIN ({@link UserService#requireAdministratorFor},
 * issue 91) - or whoever held this page's permissions could keep the administrators out. Nobody ends the
 * session they are using here: that is signing out. Each end is in {@code action_history} and the log,
 * neither with the session's id.
 */
@Service
@Transactional(readOnly = true)
public class OnlineUserService {

    private static final Logger logger = LoggerFactory.getLogger(OnlineUserService.class);

    /** A session whose last request is this recent is in use now. */
    public static final Duration ACTIVE = Duration.ofMinutes(5);

    /** One session as the page shows it; {@code current} the one this page was asked from. */
    public record OnlineSession(String handle, Instant signedInAt, Instant lastRequest, String address, String browser,
                                String userAgent, boolean current, boolean active) {
    }

    /**
     * One person signed in, their sessions the latest used first. {@code known}: the account is still
     * there (one deleted while signed in is listed by the name it signed in with).
     */
    public record OnlineUser(int userId, String username, String fullName, boolean known, boolean administrator,
                             boolean enabled, Instant lastRequest, List<OnlineSession> sessions) {

        public boolean active() {
            return sessions.stream().anyMatch(OnlineSession::active);
        }

        public boolean holdsCurrent() {
            return sessions.stream().anyMatch(OnlineSession::current);
        }
    }

    /** Everyone signed in, the latest used first, with the totals above the list. */
    public record OnlinePage(List<OnlineUser> users, int sessions, long activeUsers, long activeMinutes) {
    }

    private final ActiveUserSessions activeUserSessions;
    private final UserRepository userRepository;
    private final UserService userService;
    private final ActionHistoryService actionHistoryService;
    private final Clock clock;

    public OnlineUserService(ActiveUserSessions activeUserSessions, UserRepository userRepository, UserService userService,
                             ActionHistoryService actionHistoryService, Clock clock) {
        this.activeUserSessions = activeUserSessions;
        this.userRepository = userRepository;
        this.userService = userService;
        this.actionHistoryService = actionHistoryService;
        this.clock = clock;
    }

    /** Everyone signed in now, with their accounts in one statement (none when nobody is). */
    public OnlinePage page(String currentSessionId) {
        List<SignedInSession> sessions = activeUserSessions.signedIn();
        String current = ActiveUserSessions.handleOf(currentSessionId);
        Instant activeSince = Instant.now(clock).minus(ACTIVE);

        Map<Integer, List<SignedInSession>> byUser = sessions.stream()
                .collect(Collectors.groupingBy(SignedInSession::userId, LinkedHashMap::new, Collectors.toList()));
        Map<Integer, OnlineAccount> accounts = byUser.isEmpty() ? Map.of()
                : userRepository.findOnlineAccounts(byUser.keySet(), FixedRole.ADMIN.roleName()).stream()
                .collect(Collectors.toMap(OnlineAccount::id, Function.identity(), (a, b) -> a));

        List<OnlineUser> users = new ArrayList<>();
        byUser.forEach((userId, theirs) -> {
            List<OnlineSession> rows = theirs.stream()
                    .sorted(Comparator.comparing(SignedInSession::lastRequest).reversed())
                    .map(session -> new OnlineSession(session.handle(), session.signedInAt(), session.lastRequest(),
                            session.address(), BrowserNames.describe(session.userAgent()), session.userAgent(),
                            session.handle().equals(current), !session.lastRequest().isBefore(activeSince)))
                    .toList();
            OnlineAccount account = accounts.get(userId);
            users.add(new OnlineUser(userId,
                    account == null ? theirs.getFirst().username() : account.username(),
                    account == null ? null : fullNameOf(account),
                    account != null,
                    account != null && account.administrator(),
                    account != null && account.enabled() == 1,
                    rows.getFirst().lastRequest(),
                    rows));
        });
        users.sort(Comparator.comparing(OnlineUser::lastRequest).reversed().thenComparing(OnlineUser::username));
        return new OnlinePage(users, sessions.size(), users.stream().filter(OnlineUser::active).count(), ACTIVE.toMinutes());
    }

    /**
     * Ends one session, named by its handle: its next request lands on the login page.
     *
     * @return the username whose session it was, for the page to say so
     * @throws InvalidDataException {@code onlineUsers.gone} when no live session has the handle (it ended
     *                              meanwhile), {@code onlineUsers.yourOwn} for the session asking, and
     *                              {@code onlineUsers.adminOnly} for an administrator's session asked by someone
     *                              who is not one - nothing ended in each case
     */
    @Transactional
    public String endSession(String handle, int principalId, String currentSessionId) {
        SignedInSession session = activeUserSessions.find(handle).orElseThrow(() ->
                new InvalidDataException("no live session with this handle", "onlineUsers.gone"));
        if (session.handle().equals(ActiveUserSessions.handleOf(currentSessionId))) {
            throw new InvalidDataException("the session asking cannot end itself here", "onlineUsers.yourOwn");
        }
        requireAdministratorFor(session.userId(), principalId);

        actionHistoryService.saveActionHistory(EntityEnum.User, session.userId(), ActionEnum.UPDATE_CHANGE_STATE, principalId,
                "END SESSION", "Ended a session of [" + session.username() + "] signed in at " + session.signedInAt()
                        + ", last used " + session.lastRequest());
        activeUserSessions.endSession(handle);
        logger.info("a session of user id={} ended by user id={}", session.userId(), principalId);
        return session.username();
    }

    /**
     * Ends every session of this person - but the one asking, when they end their own others.
     *
     * @return how many were ended; none is not a refusal
     * @throws InvalidDataException {@code onlineUsers.adminOnly} for an administrator's sessions asked by someone
     *                              who is not one
     */
    @Transactional
    public int endSessionsOf(int userId, int principalId, String currentSessionId) {
        requireAdministratorFor(userId, principalId);
        int ended = activeUserSessions.endSessionsOfExcept(userId, userId == principalId ? currentSessionId : null);
        if (ended > 0) {
            actionHistoryService.saveActionHistory(EntityEnum.User, userId, ActionEnum.UPDATE_CHANGE_STATE, principalId,
                    "END SESSIONS", "Ended " + ended + " session(s) of user id=" + userId);
            logger.info("{} session(s) of user id={} ended by user id={}", ended, userId, principalId);
        }
        return ended;
    }

    /** {@link UserService#requireAdministratorFor}, refusing in this page's words. */
    private void requireAdministratorFor(int userId, int principalId) {
        try {
            userService.requireAdministratorFor(userId, principalId);
        } catch (InvalidDataException e) {
            throw new InvalidDataException(e.getMessage(), "onlineUsers.adminOnly");
        }
    }

    private static String fullNameOf(OnlineAccount account) {
        String name = (Objects.toString(account.firstName(), "") + " " + Objects.toString(account.lastName(), "")).strip();
        return name.isEmpty() ? null : name;
    }
}
