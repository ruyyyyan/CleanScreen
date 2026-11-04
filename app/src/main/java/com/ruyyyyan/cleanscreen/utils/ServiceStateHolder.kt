package com.ruyyyyan.cleanscreen.utils

import android.os.Process
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Diagnostic model for recording accessibility service lifecycle events.
 */
data class LifecycleEvent(
    val eventName: String,
    val timestamp: Long,
    val threadName: String,
    val pid: Int,
    val details: String = ""
) {
    fun getFormattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}

/**
 * Enumeration representing the five distinct operational states of the Accessibility Service.
 */
enum class AccessibilityServiceState(val description: String) {
    NOT_CONFIGURED("Not enabled in system settings"),
    CONFIGURED_NOT_BOUND("Enabled in settings, but system has not bound the service"),
    RUNNING("Successfully connected and actively monitoring"),
    CONNECTION_LOST("Service was bound but subsequently disconnected or destroyed"),
    UNKNOWN("Indeterminate state")
}

/**
 * Thread-safe singleton that tracks AccessibilityService lifecycle transitions,
 * in-memory events, and active connection status.
 */
object ServiceStateHolder {

    private const val MAX_EVENT_HISTORY = 60
    private var preferences: SharedPreferences? = null
    private var appContext: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        if (preferences != null) return
        appContext = context.applicationContext
        preferences = context.getSharedPreferences("service_diagnostics", Context.MODE_PRIVATE)
        try {
            val saved = JSONArray(preferences?.getString("events", "[]") ?: "[]")
            for (i in 0 until saved.length()) {
                val e = saved.getJSONObject(i)
                eventHistory.addLast(LifecycleEvent(e.getString("name"), e.getLong("time"),
                    e.getString("thread"), e.getInt("pid"), e.optString("details")))
            }
            while (eventHistory.size > MAX_EVENT_HISTORY) eventHistory.removeFirst()
            lastConnectedTime = preferences?.getLong("connected", 0L) ?: 0L
            lastDisconnectedTime = preferences?.getLong("disconnected", 0L) ?: 0L
        } catch (_: Exception) { eventHistory.clear() }
    }

    private fun persistHistory() {
        val array = JSONArray()
        for (e in eventHistory) array.put(JSONObject().apply {
            put("name", e.eventName); put("time", e.timestamp); put("thread", e.threadName)
            put("pid", e.pid); put("details", e.details)
        })
        preferences?.edit()?.putString("events", array.toString())
            ?.putLong("connected", lastConnectedTime)
            ?.putLong("disconnected", lastDisconnectedTime)?.apply()
    }

    private fun settingsSnapshot(): String = try {
        val resolver = appContext?.contentResolver
        if (resolver == null) "" else {
            val global = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            val services = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            " | global=$global | enabled=$services"
        }
    } catch (_: Exception) { " | settings unavailable" }

    @Volatile
    var isServiceBound: Boolean = false
        private set

    @Volatile
    var lastConnectedTime: Long = 0L
        private set

    @Volatile
    var lastDisconnectedTime: Long = 0L
        private set

    @Volatile
    var lastDisconnectReason: String = "None"
        private set

    private val eventHistory = ArrayDeque<LifecycleEvent>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Records a new lifecycle transition event.
     */
    @Synchronized
    fun recordEvent(eventName: String, details: String = "") {
        val event = LifecycleEvent(
            eventName = eventName,
            timestamp = System.currentTimeMillis(),
            threadName = Thread.currentThread().name,
            pid = Process.myPid(),
            details = details + settingsSnapshot()
        )
        if (eventHistory.size >= MAX_EVENT_HISTORY) {
            eventHistory.removeFirst()
        }
        eventHistory.addLast(event)
        persistHistory()
        notifyListeners()
    }

    /**
     * Marks the service as actively connected.
     */
    fun markServiceConnected() {
        isServiceBound = true
        lastConnectedTime = System.currentTimeMillis()
        recordEvent("ON_SERVICE_CONNECTED", "AccessibilityManager bound successfully")
    }

    /**
     * Marks the service as unbound by the system.
     */
    fun markServiceUnbound(reason: String = "System unbind") {
        isServiceBound = false
        lastDisconnectedTime = System.currentTimeMillis()
        lastDisconnectReason = reason
        recordEvent("ON_UNBIND", reason)
    }

    /**
     * Marks the service as destroyed.
     */
    fun markServiceDestroyed() {
        isServiceBound = false
        lastDisconnectedTime = System.currentTimeMillis()
        lastDisconnectReason = "Service destroyed"
        recordEvent("ON_DESTROY", "AccessibilityService onDestroy invoked")
    }

    /**
     * Retrieves a snapshot copy of recent lifecycle events in reverse chronological order.
     */
    @Synchronized
    fun getEventHistory(): List<LifecycleEvent> {
        return eventHistory.toList().reversed()
    }

    /**
     * Clears all recorded lifecycle history.
     */
    @Synchronized
    fun clearHistory() {
        eventHistory.clear()
        persistHistory()
        notifyListeners()
    }

    /**
     * Subscribes a listener to receive state updates.
     */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    /**
     * Unsubscribes a listener.
     */
    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            try {
                listener.invoke()
            } catch (e: Throwable) {
                // Suppress listener exceptions to prevent lifecycle disruptions
            }
        }
    }
}
