import com.ruyyyyan.cleanscreen.data.rules.ScanBudget

fun main() {
    var now = 0L
    val budget = ScanBudget(maxNodes = 3, maxDurationNanos = 100, clock = { now })
    check(budget.visit())
    check(budget.visit())
    check(budget.visit())
    check(!budget.visit()) // The next rule and fallback share the same exhausted budget.
    val timed = ScanBudget(maxNodes = 100, maxDurationNanos = 100, clock = { now })
    now = 99
    check(timed.visit())
    now = 100
    check(!timed.visit()) // Exact deadline stops work even when nodes remain.
    check(!ScanBudget(maxNodes = 0, clock = { now }).visit())
    val fresh = ScanBudget(clock = { now })
    check(fresh.visit()) // A new scan does not inherit the old scan's limits.
    println("ScanBudget: node limit, shared exhaustion, deadline, zero limit, fresh scan passed")
}
