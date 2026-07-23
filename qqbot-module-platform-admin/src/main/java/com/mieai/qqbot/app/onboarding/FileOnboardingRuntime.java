package com.mieai.qqbot.app.onboarding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mieai.qqbot.admin.database.DatabaseAdministrationService;
import com.mieai.qqbot.admin.database.DatabaseConfigurationView;
import com.mieai.qqbot.admin.database.DatabaseType;
import com.mieai.qqbot.admin.onboarding.OnboardingAdministrationService;
import com.mieai.qqbot.admin.onboarding.OnboardingOperationException;
import com.mieai.qqbot.admin.onboarding.OnboardingStage;
import com.mieai.qqbot.admin.onboarding.OnboardingStatusResponse;
import com.mieai.qqbot.persistence.admin.AdminUserRepository;
import com.mieai.qqbot.persistence.bot.BotRepository;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/** File-backed first-use state that remains stable while the business database is switched. */
public class FileOnboardingRuntime implements OnboardingAdministrationService {
    private final Object monitor = new Object();
    private final OnboardingStateStore store;
    private final AdminUserRepository adminRepository;
    private final BotRepository botRepository;
    private final DatabaseAdministrationService databaseService;
    private final Clock clock;
    private OnboardingFileState state;

    FileOnboardingRuntime(
            Path stateFile,
            ObjectMapper objectMapper,
            AdminUserRepository adminRepository,
            BotRepository botRepository,
            DatabaseAdministrationService databaseService,
            Clock clock) {
        store = new OnboardingStateStore(stateFile, objectMapper);
        this.adminRepository = Objects.requireNonNull(adminRepository, "adminRepository must not be null");
        this.botRepository = Objects.requireNonNull(botRepository, "botRepository must not be null");
        this.databaseService = Objects.requireNonNull(databaseService, "databaseService must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        initialize();
    }

    @Override
    public OnboardingStatusResponse status() {
        synchronized (monitor) {
            reconcileAdministratorState();
            return response(state);
        }
    }

    @Override
    public void administratorConfigured() {
        synchronized (monitor) {
            if (!adminRepository.exists()) {
                throw failure(
                        "ONBOARDING_ADMIN_REQUIRED",
                        "The administrator must be persisted before onboarding can continue");
            }
            if (state.stage() == OnboardingStage.ADMIN) {
                persist(OnboardingFileState.database());
            }
        }
    }

    @Override
    public OnboardingStatusResponse databaseConfigured(
            long expectedRevision,
            DatabaseType databaseType) {
        if (expectedRevision < 1L) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
        Objects.requireNonNull(databaseType, "databaseType must not be null");
        synchronized (monitor) {
            reconcileAdministratorState();
            DatabaseConfigurationView active = databaseService.current();
            if (active.revision() != expectedRevision || active.type() != databaseType) {
                throw failure(
                        "DATABASE_CONFIG_CONFLICT",
                        "The active database configuration changed before onboarding could continue");
            }
            switch (state.stage()) {
                case ADMIN -> throw failure(
                        "ONBOARDING_ADMIN_REQUIRED",
                        "Configure the administrator before selecting a database");
                case DATABASE -> persist(OnboardingFileState.bot(databaseType));
                case BOT -> {
                    if (state.databaseType() != databaseType) {
                        throw failure(
                                "ONBOARDING_STATE_CONFLICT",
                                "A different database was already selected for onboarding");
                    }
                }
                case COMPLETE -> {
                    return response(state);
                }
            }
            return response(state);
        }
    }

    @Override
    public OnboardingStatusResponse complete() {
        synchronized (monitor) {
            reconcileAdministratorState();
            if (state.stage() == OnboardingStage.COMPLETE) {
                return response(state);
            }
            if (state.stage() == OnboardingStage.ADMIN) {
                throw failure(
                        "ONBOARDING_ADMIN_REQUIRED",
                        "Configure the administrator before completing onboarding");
            }
            if (state.stage() == OnboardingStage.DATABASE) {
                throw failure(
                        "ONBOARDING_DATABASE_REQUIRED",
                        "Confirm the active database before completing onboarding");
            }
            long botCount = botCount();
            if (botCount < 1L) {
                throw failure(
                        "ONBOARDING_BOT_REQUIRED",
                        "At least one QQ bot must be configured before onboarding can be completed");
            }
            persist(OnboardingFileState.complete(state.databaseType(), clock.instant()));
            return response(state, botCount);
        }
    }

    Path stateFile() {
        return store.file();
    }

    private void initialize() {
        synchronized (monitor) {
            state = store.load().orElseGet(() -> {
                OnboardingFileState initial = adminRepository.exists()
                        ? OnboardingFileState.complete(databaseService.current().type(), clock.instant())
                        : OnboardingFileState.admin();
                store.save(initial);
                return initial;
            });
            reconcileAdministratorState();
        }
    }

    private void reconcileAdministratorState() {
        boolean adminConfigured = adminRepository.exists();
        if (!adminConfigured && state.stage() != OnboardingStage.ADMIN) {
            persist(OnboardingFileState.admin());
        } else if (adminConfigured && state.stage() == OnboardingStage.ADMIN) {
            persist(OnboardingFileState.database());
        }
    }

    private OnboardingStatusResponse response(OnboardingFileState current) {
        return response(current, botCount());
    }

    private static OnboardingStatusResponse response(OnboardingFileState current, long botCount) {
        return new OnboardingStatusResponse(
                current.stage(),
                current.databaseType(),
                botCount,
                current.completedAt());
    }

    private long botCount() {
        return botRepository.findAll().size();
    }

    private void persist(OnboardingFileState next) {
        store.save(next);
        state = next;
    }

    private static OnboardingOperationException failure(String code, String message) {
        return new OnboardingOperationException(HttpStatus.CONFLICT, code, message);
    }
}
