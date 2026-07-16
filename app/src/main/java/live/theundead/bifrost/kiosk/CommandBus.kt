package live.theundead.bifrost.kiosk

/**
 * Process-local hand-off for controller commands: [LinkService] receives them
 * (live stream or heartbeat fallback) and [MainActivity] — which owns the
 * screen and WebView actions — consumes them. A plain listener slot, not a
 * broadcast: both ends live in the one kiosk process, and the activity is
 * effectively immortal under lock task.
 */
object CommandBus {
    @Volatile
    var listener: ((String) -> Unit)? = null

    /** Deliver a command; `false` when no listener is attached (activity gone —
     * the caller falls back to its own minimal handling). */
    fun dispatch(cmd: String): Boolean {
        val l = listener ?: return false
        l(cmd)
        return true
    }
}
