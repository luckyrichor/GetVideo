package com.getvideo.app

import android.app.Instrumentation
import android.os.Bundle
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.getvideo.app.data.local.*
import kotlinx.coroutines.runBlocking

/** Isolated Room database: never mutates the user's audio database. */
class AccountIsolationInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val report = Bundle()
        val name = "account-isolation-check.db"
        var db: AppDatabase? = null
        try {
            runBlocking {
                targetContext.deleteDatabase(name)
                db = Room.databaseBuilder(targetContext, AppDatabase::class.java, name).build()
                val dao = db!!.trackDao()
                fun track(id: Long, scope: String) = TrackEntity(id,"local","fixture-$id","Fixture","验证",1.0,1,"wav","","",0,cloudScope=scope)
                dao.upsertAll(listOf(track(-1,"local"), track(5,"acct_a"), track(6,"acct_b")))
                dao.setActive(ActiveScopeEntity(scope="acct_a"))
                check(dao.allNow().map { it.id }.toSet() == setOf(-1L,5L))
                dao.setActive(ActiveScopeEntity(scope="acct_b"))
                check(dao.allNow().map { it.id }.toSet() == setOf(-1L,6L))
                check(dao.byId(5) == null)
                dao.saveCursor(CloudSyncEntity("acct_a",100))
                dao.saveCursor(CloudSyncEntity("acct_b",200))
                check(dao.cloudCursor("acct_a")==100L && dao.cloudCursor("acct_b")==200L)
                db!!.close(); db=null
                SQLiteDatabase.openDatabase(targetContext.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use {
                    it.execSQL("ALTER TABLE tracks DROP COLUMN cloudScope")
                    it.execSQL("ALTER TABLE tracks DROP COLUMN remoteTrackId")
                    it.execSQL("DROP TABLE active_scope")
                    it.execSQL("DROP TABLE cloud_mapping")
                    it.execSQL("DROP TABLE cloud_sync")
                    it.version=6
                }
                db=Room.databaseBuilder(targetContext,AppDatabase::class.java,name).addMigrations(AppDatabase.MIGRATION_6_7).build()
                val migrated=db!!.trackDao().allNow()
                check(migrated.map { it.id }.toSet()==setOf(-1L,5L,6L))
                check(migrated.filter { it.id>0 }.all { it.cloudScope=="legacy_device" && it.remoteTrackId==it.id })
                report.putString("stream","AccountIsolation PASS: scope queries, separate cursors and Room 6->7 preserve records\n")
            }
            finish(-1,report)
        } catch(e:Throwable) { report.putString("stream",e.stackTraceToString());finish(0,report) }
        finally { db?.close();targetContext.deleteDatabase(name) }
    }
}
