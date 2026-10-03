package com.hnp.filemanagement.identity.domain;

import com.hnp.filemanagement.audit.domain.ActionEnum;
import com.hnp.filemanagement.audit.domain.ActionHistoryService;
import com.hnp.filemanagement.audit.domain.EntityEnum;
import com.hnp.filemanagement.identity.persistence.SignInAccount;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.LoginAttempts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The locked sign-ins (roadmap 12.1): the names 2.7.4's lock holds or counts failures against
 * ({@link LoginAttempts}, issue 105), with the account each belongs to, and a lock lifted early.
 *
 * <p>What is listed is this instance's memory since its start - nothing is stored, and a restart
 * empties it as it lifts every lock. A name nobody has is listed too: a run of them is how
 * someone guessing names shows itself. Unlocking forgets a name's failures and its lock, as a
 * right password would; it is recorded in {@code action_history} and the log. Lifting the lock on
 * an account that holds ADMIN asks ADMIN ({@link UserService#requireAdministratorFor}, issue 91) -
 * otherwise this would be a way round the lock on the accounts it matters most for.
 */
@Service
@Transactional(readOnly = true)
public class SignInLockService {

    private static final Logger logger = LoggerFactory.getLogger(SignInLockService.class);

    /** Names on one page of the list. */
    public static final int PAGE_SIZE = 50;

    /** The entity id an unlock is recorded against when no account has the name. */
    static final int NO_ACCOUNT = 0;

    /**
     * One name as the page shows it: what is counted against it ({@link LoginAttempts.Entry}) and
     * the account that has it, if one does - its id, its username as stored, enabled, ADMIN.
     */
    public record LockedSignIn(String key, String username, int failures, Instant lastFailure, Instant lockedUntil,
                               boolean locked, int refusedWhileLocked, List<String> addresses,
                               Integer accountId, String accountUsername, boolean accountEnabled,
                               boolean administrator) {

        public boolean hasAccount() {
            return accountId != null;
        }
    }

    /** A page of names, the latest failure first, and whether there are pages either side - never a count. */
    public record LockedSignInPage(List<LockedSignIn> rows, int page, boolean hasPrevious, boolean hasNext,
                                   int maxFailures, long lockMinutes) {
    }

    private final LoginAttempts loginAttempts;
    private final UserRepository userRepository;
    private final UserService userService;
    private final ActionHistoryService actionHistoryService;
    private final Clock clock;

    public SignInLockService(LoginAttempts loginAttempts, UserRepository userRepository, UserService userService,
                             ActionHistoryService actionHistoryService, Clock clock) {
        this.loginAttempts = loginAttempts;
        this.userRepository = userRepository;
        this.userService = userService;
        this.actionHistoryService = actionHistoryService;
        this.clock = clock;
    }

    /** One page of the names counted now, with their accounts in one statement (none for an empty page). */
    public LockedSignInPage page(int page) {
        int number = Math.max(page, 0);
        List<LoginAttempts.Entry> all = loginAttempts.current();
        long from = (long) number * PAGE_SIZE;
        List<LoginAttempts.Entry> entries = from >= all.size()
                ? List.of()
                : all.subList((int) from, (int) Math.min(all.size(), from + PAGE_SIZE));

        Map<String, SignInAccount> accounts = new HashMap<>();
        if (!entries.isEmpty()) {
            List<String> upperNames = entries.stream().map(entry -> entry.key().toUpperCase(Locale.ROOT)).toList();
            for (SignInAccount account : userRepository.findSignInAccounts(upperNames, FixedRole.ADMIN.roleName())) {
                accounts.put(account.username().toLowerCase(Locale.ROOT), account);
            }
        }

        Instant now = Instant.now(clock);
        List<LockedSignIn> rows = entries.stream().map(entry -> {
            SignInAccount account = accounts.get(entry.key());
            return new LockedSignIn(entry.key(), entry.username(), entry.failures(), entry.lastFailure(),
                    entry.lockedUntil(), entry.lockedAt(now), entry.refusedWhileLocked(), entry.addresses(),
                    account == null ? null : account.id(),
                    account == null ? null : account.username(),
                    account != null && account.enabled() == 1,
                    account != null && account.administrator());
        }).toList();

        return new LockedSignInPage(rows, number, number > 0, from + PAGE_SIZE < all.size(),
                loginAttempts.maxFailures(), loginAttempts.lockDuration().toMinutes());
    }

    /**
     * Lifts the lock on this name and forgets its failures. Whether there was anything to lift -
     * {@code false} for a name not counted now, which is recorded nowhere. Refused, with nothing
     * changed, for an account holding ADMIN unless the principal holds it too.
     */
    @Transactional
    public boolean unlock(String name, int principalId) {
        LoginAttempts.Entry entry = name == null ? null : loginAttempts.entryFor(name);
        if (entry == null) {
            return false;
        }
        Optional<User> account = userRepository.findByUsernameIgnoreCase(entry.key());
        account.ifPresent(user -> userService.requireAdministratorFor(user.getId(), principalId));

        actionHistoryService.saveActionHistory(EntityEnum.User, account.map(User::getId).orElse(NO_ACCOUNT),
                ActionEnum.UPDATE_CHANGE_STATE, principalId, "UNLOCK SIGN-IN",
                "Unlock sign-in for [" + entry.key() + "]: " + entry.failures() + " failure(s)"
                        + (entry.lockedAt(Instant.now(clock)) ? ", locked until " + entry.lockedUntil() : ", not locked")
                        + (account.isPresent() ? "" : "; no account has this name"));
        boolean unlocked = loginAttempts.unlock(entry.key());
        logger.info("sign-in unlocked: username=[{}] account id={} by user id={}",
                entry.key(), account.map(User::getId).orElse(null), principalId);
        return unlocked;
    }
}
