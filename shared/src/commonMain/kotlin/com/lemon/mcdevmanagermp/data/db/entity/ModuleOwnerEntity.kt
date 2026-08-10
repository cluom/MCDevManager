package com.lemon.mcdevmanagermp.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "module_owner",
    primaryKeys = ["itemId", "personId"],
    foreignKeys = [
        ForeignKey(
            entity = ProfitPersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("personId")]
)
data class ModuleOwnerEntity(
    val itemId: String,
    val personId: Long,
    val weight: Double
)
