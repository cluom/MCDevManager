package com.lemon.mcdevmanagermp.data.repository

import com.lemon.mcdevmanagermp.data.common.AppContext
import com.lemon.mcdevmanagermp.data.db.entity.ModuleOwnerEntity
import com.lemon.mcdevmanagermp.data.db.entity.ProfitPersonEntity
import com.lemon.mcdevmanagermp.domain.profitsharing.ModuleOwnership
import com.lemon.mcdevmanagermp.domain.profitsharing.ProfitPerson
import com.lemon.mcdevmanagermp.domain.profitsharing.ProfitSharingRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.time.Clock

class ProfitSharingRepositoryImpl : ProfitSharingRepository {

    companion object {
        val INSTANCE by lazy { ProfitSharingRepositoryImpl() }
    }

    private val dao by lazy { AppContext.database.profitSharingDao() }
    private val changeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val changes: Flow<Unit> = changeEvents

    override suspend fun getPeople(accountKey: String): List<ProfitPerson> =
        dao.getPeople(accountKey).map { it.toDomain() }

    override suspend fun getOwnerships(accountKey: String): List<ModuleOwnership> =
        dao.getOwnerships(accountKey).map { it.toDomain() }

    override suspend fun addPerson(accountKey: String, name: String): Long {
        val id = dao.insertPerson(
            ProfitPersonEntity(
                accountKey = accountKey,
                name = name.trim(),
                createdAt = Clock.System.now().toEpochMilliseconds()
            )
        )
        changeEvents.tryEmit(Unit)
        return id
    }

    override suspend fun deletePerson(personId: Long) {
        dao.deletePerson(personId)
        changeEvents.tryEmit(Unit)
    }

    override suspend fun replaceOwnerships(
        itemId: String,
        ownerships: List<ModuleOwnership>
    ) {
        dao.replaceOwnerships(itemId, ownerships.map { it.toEntity() })
        changeEvents.tryEmit(Unit)
    }

    private fun ProfitPersonEntity.toDomain() = ProfitPerson(id, accountKey, name, createdAt)

    private fun ModuleOwnerEntity.toDomain() = ModuleOwnership(itemId, personId, weight)

    private fun ModuleOwnership.toEntity() = ModuleOwnerEntity(itemId, personId, weight)
}
