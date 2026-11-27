package com.lemon.mcdevmanagermp.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.lemon.mcdevmanagermp.data.db.dao.AccountDao
import com.lemon.mcdevmanagermp.data.db.dao.ArchivedWorkDao
import com.lemon.mcdevmanagermp.data.db.dao.DayDetailConfigDao
import com.lemon.mcdevmanagermp.data.db.dao.ProfitSharingDao
import com.lemon.mcdevmanagermp.data.db.dao.EcoFeeRecordDao
import com.lemon.mcdevmanagermp.data.db.entity.EcoFeeRecordEntity
import com.lemon.mcdevmanagermp.data.db.dao.PromotionTemplateDao
import com.lemon.mcdevmanagermp.data.db.entity.AccountEntity
import com.lemon.mcdevmanagermp.data.db.entity.ArchivedWorkEntity
import com.lemon.mcdevmanagermp.data.db.entity.DayDetailConfigEntity
import com.lemon.mcdevmanagermp.data.db.entity.ModuleOwnerEntity
import com.lemon.mcdevmanagermp.data.db.entity.ProfitPersonEntity
import com.lemon.mcdevmanagermp.data.db.entity.PromotionTemplateEntity

@Database(
    entities = [
        AccountEntity::class,
        PromotionTemplateEntity::class,
        DayDetailConfigEntity::class,
        ProfitPersonEntity::class,
        ModuleOwnerEntity::class,
        EcoFeeRecordEntity::class,
        ArchivedWorkEntity::class
    ],
    version = 11,
    exportSchema = false
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun promotionTemplateDao(): PromotionTemplateDao
    abstract fun dayDetailConfigDao(): DayDetailConfigDao
    abstract fun profitSharingDao(): ProfitSharingDao
    abstract fun ecoFeeRecordDao(): EcoFeeRecordDao
    abstract fun archivedWorkDao(): ArchivedWorkDao
}

expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

internal fun RoomDatabase.Builder<AppDatabase>.addAppDatabaseMigrations() = addMigrations(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_5_6,
    MIGRATION_6_7,
    MIGRATION_7_8,
    MIGRATION_8_9,
    MIGRATION_9_10,
    MIGRATION_10_11
)

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE account ADD COLUMN headImg TEXT DEFAULT NULL")
    }
}

/**
 * 数据库迁移 2→3：将 email 列重命名为 nickname，语义从邮箱改为昵称标识
 * 运行时 MainViewModel/AutoLoginUseCase 获取 userInfo 后会更新 nickname 字段值
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE account RENAME COLUMN email TO nickname")
    }
}

/**
 * 数据库迁移 3→4：新增 PE 轮播图申请文案模板表。
 * updateContent 列名避开 SQL 保留字 UPDATE（domain 层字段名为 update）。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS promotion_template (
              id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              name TEXT NOT NULL,
              extra TEXT NOT NULL,
              activity TEXT NOT NULL,
              feature TEXT NOT NULL,
              updateContent TEXT NOT NULL,
              createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

/**
 * 数据库迁移 4→5：新增数据追踪页「上次查询配置」表（按账号 + 平台隔离）。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS day_detail_config (
              accountKey TEXT NOT NULL,
              platform TEXT NOT NULL,
              dateSpanDays INTEGER NOT NULL,
              selectedIIDs TEXT NOT NULL,
              updatedAt INTEGER NOT NULL,
              PRIMARY KEY (accountKey, platform)
            )
            """.trimIndent()
        )
    }
}

/**
 * 数据库迁移 5→6：promotion_template 表新增 promoImageUrl 列（PE 轮播图预览图 URL）。
 * Entity 该列声明 @ColumnInfo(defaultValue = "")，与此处 DEFAULT '' 对齐，确保 Room schema 校验通过。
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(connection: SQLiteConnection) {
        val columnExists = connection.prepare(
            "SELECT COUNT(*) FROM pragma_table_info('promotion_template') WHERE name = 'promoImageUrl'"
        ).use { stmt ->
            stmt.step()
            stmt.getLong(0) > 0
        }
        if (!columnExists) {
            connection.execSQL(
                "ALTER TABLE promotion_template ADD COLUMN promoImageUrl TEXT NOT NULL DEFAULT ''"
            )
        }
    }
}

/**
 * 数据库迁移 6→7：account 表新增 email、password、rememberPassword 列（用于记住密码与多账号切换填充）。
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(connection: SQLiteConnection) {
        connection.ensureAccountCredentialColumns()
    }
}

/**
 * 数据库迁移 7→8：保留 v1.2.5 的账号字段，并加入人员与模组权重分账表。
 *
 * 旧定制版也曾使用 schema 7，但其 6→7 只创建分账表。这里的所有操作均为幂等，
 * 因而既能升级官方 v1.2.5 数据库，也能补齐旧定制版缺少的账号字段。
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(connection: SQLiteConnection) {
        connection.ensureAccountCredentialColumns()
        connection.ensureProfitSharingTables()
    }
}

private fun SQLiteConnection.ensureAccountCredentialColumns() {
    val emailExists = prepare(
            "SELECT COUNT(*) FROM pragma_table_info('account') WHERE name = 'email'"
        ).use { stmt ->
            stmt.step()
            stmt.getLong(0) > 0
        }
    if (!emailExists) {
        execSQL("ALTER TABLE account ADD COLUMN email TEXT NOT NULL DEFAULT ''")
    }
    val passwordExists = prepare(
            "SELECT COUNT(*) FROM pragma_table_info('account') WHERE name = 'password'"
        ).use { stmt ->
            stmt.step()
            stmt.getLong(0) > 0
        }
    if (!passwordExists) {
        execSQL("ALTER TABLE account ADD COLUMN password TEXT NOT NULL DEFAULT ''")
    }
    val rememberPasswordExists = prepare(
            "SELECT COUNT(*) FROM pragma_table_info('account') WHERE name = 'rememberPassword'"
        ).use { stmt ->
            stmt.step()
            stmt.getLong(0) > 0
        }
    if (!rememberPasswordExists) {
        execSQL("ALTER TABLE account ADD COLUMN rememberPassword INTEGER NOT NULL DEFAULT 0")
    }
}

private fun SQLiteConnection.ensureProfitSharingTables() {
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS profit_person (
          id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
          accountKey TEXT NOT NULL,
          name TEXT NOT NULL,
          createdAt INTEGER NOT NULL
        )
        """.trimIndent()
    )
    execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS index_profit_person_accountKey_name " +
                "ON profit_person (accountKey, name)"
    )
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS module_owner (
          itemId TEXT NOT NULL,
          personId INTEGER NOT NULL,
          weight REAL NOT NULL,
          PRIMARY KEY (itemId, personId),
          FOREIGN KEY (personId) REFERENCES profit_person(id) ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent()
    )
    execSQL("CREATE INDEX IF NOT EXISTS index_module_owner_personId ON module_owner (personId)")
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS eco_fee_record")
        connection.execSQL(CREATE_ECO_FEE_RECORD_TABLE_SQL)
    }
}

/**
 * 数据库迁移 9→10：
 * 1. 重建 eco_fee_record 表：9 版本把绿宝石计价模组的商品流水按钻石流水计入了反推分母，
 *    旧记录全部作废，快照与费率由后续登录按正确口径重新生成。
 * 2. 新增本地作品归档表 archived_work（按账号 + 作品 ID 隔离）。
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("DROP TABLE IF EXISTS eco_fee_record")
        connection.execSQL(CREATE_ECO_FEE_RECORD_TABLE_SQL)
        connection.execSQL(CREATE_ARCHIVED_WORK_TABLE_SQL)
    }
}

private const val CREATE_ECO_FEE_RECORD_TABLE_SQL = """
    CREATE TABLE IF NOT EXISTS eco_fee_record (
      accountKey TEXT NOT NULL,
      dataMonth TEXT NOT NULL,
      profitDataJson TEXT NOT NULL,
      ecoFeeRate REAL,
      ecoFeeAmount REAL,
      actualIncome REAL,
      playPlanIncome REAL,
      updatedAt INTEGER NOT NULL,
      PRIMARY KEY (accountKey, dataMonth)
    )
"""

private const val CREATE_ARCHIVED_WORK_TABLE_SQL = """
    CREATE TABLE IF NOT EXISTS archived_work (
      accountKey TEXT NOT NULL,
      itemId TEXT NOT NULL,
      archivedAt INTEGER NOT NULL,
      PRIMARY KEY (accountKey, itemId)
    )
"""

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(connection: SQLiteConnection) {
        connection.ensureAccountCredentialColumns()
        connection.ensureProfitSharingTables()
    }
}
