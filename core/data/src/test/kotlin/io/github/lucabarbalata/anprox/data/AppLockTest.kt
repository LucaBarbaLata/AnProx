package io.github.lucabarbalata.anprox.data

import io.github.lucabarbalata.anprox.data.security.AppLock
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLockTest {
    private var now = 1_000_000L
    private val lock = AppLock { now }

    @Test
    fun `a fresh start is locked only when the lock is enabled`() {
        assertFalse(lock.ready.value)
        lock.configure(enabled = true, lockAfterMillis = 60_000)
        assertTrue(lock.ready.value)
        assertTrue(lock.locked.value)

        val off = AppLock { now }
        off.configure(enabled = false, lockAfterMillis = 60_000)
        assertFalse(off.locked.value)
    }

    @Test
    fun `short trips to other apps stay unlocked, long ones lock`() {
        lock.configure(enabled = true, lockAfterMillis = 60_000)
        lock.unlock()

        lock.onBackground(); now += 30_000; lock.onForeground()
        assertFalse(lock.locked.value)

        lock.onBackground(); now += 60_000; lock.onForeground()
        assertTrue(lock.locked.value)
    }

    @Test
    fun `lock after zero locks every time`() {
        lock.configure(enabled = true, lockAfterMillis = 0)
        lock.unlock()
        lock.onBackground(); lock.onForeground()
        assertTrue(lock.locked.value)
    }

    @Test
    fun `the authentication prompt itself does not count as leaving`() {
        lock.configure(enabled = true, lockAfterMillis = 0)
        lock.authenticating = true
        lock.onBackground(); now += 120_000; lock.onForeground()
        lock.authenticating = false
        lock.unlock()
        assertFalse(lock.locked.value)
    }

    @Test
    fun `turning the lock on later does not lock right away, turning it off unlocks`() {
        lock.configure(enabled = false, lockAfterMillis = 60_000)
        lock.configure(enabled = true, lockAfterMillis = 60_000)
        assertFalse(lock.locked.value)

        lock.onBackground(); now += 90_000; lock.onForeground()
        assertTrue(lock.locked.value)
        lock.configure(enabled = false, lockAfterMillis = 60_000)
        assertFalse(lock.locked.value)

        // Disabled: leaving for a long time changes nothing.
        lock.onBackground(); now += 600_000; lock.onForeground()
        assertFalse(lock.locked.value)
    }

    @Test
    fun `coming back without having left does nothing`() {
        lock.configure(enabled = true, lockAfterMillis = 0)
        lock.unlock()
        lock.onForeground()
        assertFalse(lock.locked.value)
    }
}
