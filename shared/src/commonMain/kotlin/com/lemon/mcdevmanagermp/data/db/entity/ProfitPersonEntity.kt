package com.lemon.mcdevmanagermp.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "profit_person",
    indices = [Index(value = ["accountKey", "name"], unique = true)]
)
data class ProfitPersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountKey: String,
    val name: String,
    val createdAt: Long
)
