package com.xixi.timetable.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.xixi.timetable.data.entity.CourseEntity
import com.xixi.timetable.data.entity.SessionEntity

/**
 * 应用数据库。
 *
 * 进程内单例：一律通过 [get] 获取实例，内部使用 applicationContext，避免持有 Activity。
 */
@Database(
    entities = [CourseEntity::class, SessionEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun courseDao(): CourseDao

    companion object {
        private const val DB_NAME: String = "kobi.db"

        @Volatile
        private var instance: AppDatabase? = null

        /** 进程内单例；用 applicationContext */
        fun get(context: Context): AppDatabase {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                val cached = instance
                if (cached != null) {
                    cached
                } else {
                    val created = Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        DB_NAME
                    ).build()
                    instance = created
                    created
                }
            }
        }

        /**
         * 取已初始化的单例，未初始化时返回 null。
         *
         * 供 [CourseRepository] 内部获取事务入口使用；仓库实例由 `CourseRepository.get(context)`
         * 创建，其时 [get] 必然已被调用，因此不会为 null。
         */
        internal fun currentOrNull(): AppDatabase? = instance
    }
}
