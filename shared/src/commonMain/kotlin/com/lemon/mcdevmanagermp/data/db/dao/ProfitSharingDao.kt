package com.lemon.mcdevmanagermp.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.lemon.mcdevmanagermp.data.db.entity.ModuleOwnerEntity
import com.lemon.mcdevmanagermp.data.db.entity.ProfitPersonEntity

@Dao
interface ProfitSharingDao {

    @Query("SELECT * FROM profit_person WHERE accountKey = :accountKey ORDER BY createdAt ASC")
    suspend fun getPeople(accountKey: String): List<ProfitPersonEntity>

    @Query(
        """
        SELECT module_owner.* FROM module_owner
        INNER JOIN profit_person ON profit_person.id = module_owner.personId
        WHERE profit_person.accountKey = :accountKey
        """
    )
    suspend fun getOwnerships(accountKey: String): List<ModuleOwnerEntity>

    @Insert
    suspend fun insertPerson(person: ProfitPersonEntity): Long

    @Query("DELETE FROM profit_person WHERE id = :personId")
    suspend fun deletePerson(personId: Long)

    @Query("DELETE FROM module_owner WHERE itemId = :itemId")
    suspend fun deleteOwnerships(itemId: String)

    @Upsert
    suspend fun upsertOwnerships(ownerships: List<ModuleOwnerEntity>)

    @Transaction
    suspend fun replaceOwnerships(itemId: String, ownerships: List<ModuleOwnerEntity>) {
        deleteOwnerships(itemId)
        if (ownerships.isNotEmpty()) upsertOwnerships(ownerships)
    }
}
