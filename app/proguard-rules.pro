# 本项目 release 未开启混淆（isMinifyEnabled = false），
# 此文件保留占位，便于日后开启 R8 时补充规则。

# Room 生成的实现类通过反射加载，保留实体与 DAO 相关成员
-keep class androidx.room.** { *; }
-keep class com.xixi.timetable.data.** { *; }

# 广播接收器由系统反射实例化，必须保留
-keep class com.xixi.timetable.notify.** { *; }
