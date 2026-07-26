package com.mieai.qqbot.app.onboarding

import com.fasterxml.jackson.databind.ObjectMapper
import com.mieai.qqbot.admin.database.DatabaseAdministrationService
import com.mieai.qqbot.admin.database.DatabaseType
import com.mieai.qqbot.admin.onboarding.OnboardingAdministrationService
import com.mieai.qqbot.admin.onboarding.OnboardingOperationException
import com.mieai.qqbot.admin.onboarding.OnboardingStage
import com.mieai.qqbot.admin.onboarding.OnboardingStatusResponse
import com.mieai.qqbot.persistence.admin.AdminUserRepository
import com.mieai.qqbot.persistence.bot.BotRepository
import java.nio.file.Path
import java.time.Clock
import org.springframework.http.HttpStatus

/** File-backed first-use state that remains stable while the business database is switched. */
class FileOnboardingRuntime internal constructor(
    stateFile: Path,
    objectMapper: ObjectMapper,
    private val adminRepository: AdminUserRepository,
    private val botRepository: BotRepository,
    private val databaseService: DatabaseAdministrationService,
    private val clock: Clock,
) : OnboardingAdministrationService {
    private val monitor = Any()
    private val store = OnboardingStateStore(stateFile, objectMapper)
    private lateinit var state: OnboardingFileState

    init {
        initialize()
    }

    override fun status(): OnboardingStatusResponse = synchronized(monitor) {
        reconcileAdministratorState()
        response(state)
    }

    override fun administratorConfigured() = synchronized(monitor) {
        if (!adminRepository.exists()) {
            throw failure(
                "ONBOARDING_ADMIN_REQUIRED",
                "The administrator must be persisted before onboarding can continue",
            )
        }
        if (state.stage == OnboardingStage.ADMIN) persist(OnboardingFileState.database())
    }

    override fun databaseConfigured(
        expectedRevision: Long,
        databaseType: DatabaseType,
    ): OnboardingStatusResponse {
        require(expectedRevision >= 1L) { "expectedRevision must be positive" }
        return synchronized(monitor) {
            reconcileAdministratorState()
            val active = databaseService.current()
            if (active.revision != expectedRevision || active.type != databaseType) {
                throw failure(
                    "DATABASE_CONFIG_CONFLICT",
                    "The active database configuration changed before onboarding could continue",
                )
            }
            when (state.stage) {
                OnboardingStage.ADMIN -> throw failure(
                    "ONBOARDING_ADMIN_REQUIRED",
                    "Configure the administrator before selecting a database",
                )

                OnboardingStage.DATABASE -> persist(OnboardingFileState.bot(databaseType))
                OnboardingStage.BOT -> if (state.databaseType != databaseType) {
                    throw failure(
                        "ONBOARDING_STATE_CONFLICT",
                        "A different database was already selected for onboarding",
                    )
                }

                OnboardingStage.COMPLETE -> return@synchronized response(state)
            }
            response(state)
        }
    }

    override fun complete(): OnboardingStatusResponse = synchronized(monitor) {
        reconcileAdministratorState()
        when (state.stage) {
            OnboardingStage.COMPLETE -> return@synchronized response(state)
            OnboardingStage.ADMIN -> throw failure(
                "ONBOARDING_ADMIN_REQUIRED",
                "Configure the administrator before completing onboarding",
            )

            OnboardingStage.DATABASE -> throw failure(
                "ONBOARDING_DATABASE_REQUIRED",
                "Confirm the active database before completing onboarding",
            )

            OnboardingStage.BOT -> Unit
        }
        val botCount = botCount()
        if (botCount < 1L) {
            throw failure(
                "ONBOARDING_BOT_REQUIRED",
                "At least one QQ bot must be configured before onboarding can be completed",
            )
        }
        persist(OnboardingFileState.complete(requireNotNull(state.databaseType), clock.instant()))
        response(state, botCount)
    }

    private fun initialize() = synchronized(monitor) {
        state = store.load() ?: run {
            val initial = if (adminRepository.exists()) {
                OnboardingFileState.complete(databaseService.current().type, clock.instant())
            } else {
                OnboardingFileState.admin()
            }
            store.save(initial)
            initial
        }
        reconcileAdministratorState()
    }

    private fun reconcileAdministratorState() {
        val adminConfigured = adminRepository.exists()
        if (!adminConfigured && state.stage != OnboardingStage.ADMIN) {
            persist(OnboardingFileState.admin())
        } else if (adminConfigured && state.stage == OnboardingStage.ADMIN) {
            persist(OnboardingFileState.database())
        }
    }

    private fun response(current: OnboardingFileState): OnboardingStatusResponse =
        response(current, botCount())

    private fun response(current: OnboardingFileState, botCount: Long): OnboardingStatusResponse =
        OnboardingStatusResponse(current.stage, current.databaseType, botCount, current.completedAt)

    private fun botCount(): Long = botRepository.findAll().size.toLong()

    private fun persist(next: OnboardingFileState) {
        store.save(next)
        state = next
    }

    private fun failure(code: String, message: String): OnboardingOperationException =
        OnboardingOperationException(HttpStatus.CONFLICT, code, message)
}
