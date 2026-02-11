package at.tuwrraphael.digit

import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

data class CalendarEvent(
    val id: Long,
    val title: String?,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val calendarId : Int
)

class CalendarService(private val context: Context) {

    fun requestPermissions(): Boolean {
        val readGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        //val writeGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        return readGranted
                //&& writeGranted
    }

    fun getNextEvent(): CalendarEvent? {
        if (!requestPermissions()) {
            // Berechtigung nicht erteilt
            return null
        }
        val now = System.currentTimeMillis()
        val endOfDay = now + 24 * 60 * 60 * 1000
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTEND} <= ?"
        val selectionArgs = arrayOf(now.toString(), endOfDay.toString())
        val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

        val cursor: Cursor? = context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )

        var nextEvent: CalendarEvent? = null
        cursor?.use {
            while (it.moveToNext()) {
                val allDay = it.getInt(it.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)) != 0
                if (allDay) continue
                val start = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events.DTSTART))
                val end = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events.DTEND))
                if (end > now) {
                    nextEvent = CalendarEvent(
                        id = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events._ID)),
                        title = it.getString(it.getColumnIndexOrThrow(CalendarContract.Events.TITLE)),
                        start = start,
                        end = end,
                        allDay = allDay,
                        calendarId = it.getInt(it.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_ID))
                    )
                    break
                }
            }
        }
        return nextEvent
    }
}
