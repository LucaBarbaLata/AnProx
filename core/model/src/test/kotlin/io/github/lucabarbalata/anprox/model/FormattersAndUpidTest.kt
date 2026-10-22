package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.format.Formatters
import io.github.lucabarbalata.anprox.model.pve.TaskLogLine
import io.github.lucabarbalata.anprox.model.pve.TaskStatus
import io.github.lucabarbalata.anprox.model.pve.Upid
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormattersAndUpidTest {
    private val en = Locale.US

    @Test
    fun `a task that timed out on the guest's lock is told apart`() {
        // As the test host answered right after a rollback with RAM, while `qm cleanup` held the lock.
        assertTrue(TaskStatus.isLockTimeout("can't lock file '/var/lock/qemu-server/lock-100.conf' - got timeout"))
        assertFalse(TaskStatus.isLockTimeout("VM is locked (backup)"))
        assertFalse(TaskStatus.isLockTimeout("OK"))
        assertFalse(TaskStatus.isLockTimeout(null))
    }

    @Test
    fun `a running task's log is read on from its last line`() {
        val empty = listOf(TaskLogLine(1, "no content"))         // what Proxmox VE answers for an empty log
        assertEquals(0, TaskLogLine.nextStart(emptyList()))
        assertEquals(0, TaskLogLine.nextStart(empty))
        val first = TaskLogLine.merge(empty, listOf(TaskLogLine(1, "starting"), TaskLogLine(2, "INFO: 10%")))
        assertEquals(listOf("starting", "INFO: 10%"), first.map { it.t })
        assertEquals(1, TaskLogLine.nextStart(first))
        val next = TaskLogLine.merge(first, listOf(TaskLogLine(2, "INFO: 10% done"), TaskLogLine(3, "TASK OK")))
        assertEquals(listOf("starting", "INFO: 10% done", "TASK OK"), next.map { it.t })
        assertEquals(next, TaskLogLine.merge(next, emptyList()))  // nothing new
        assertEquals(2, TaskLogLine.nextStart(next.takeLast(1)))  // only the last lines kept
    }

    @Test
    fun bytes() {
        assertEquals("—", Formatters.bytes(null, en))
        assertEquals("512 B", Formatters.bytes(512, en))
        assertEquals("1.50 KiB", Formatters.bytes(1536, en))
        assertEquals("64.0 GiB", Formatters.bytes(64L * 1024 * 1024 * 1024, en))
        assertEquals("1.82 TiB", Formatters.bytes(2_000_000_000_000, en))
    }

    @Test
    fun percent() {
        assertEquals("42%", Formatters.percent(0.4231, en))
        assertEquals("4.2%", Formatters.percent(0.042, en))
        assertEquals("0%", Formatters.percent(0.0, en))
        assertEquals("—", Formatters.percent(null, en))
    }

    @Test
    fun duration() {
        assertEquals("1d 2h", Formatters.duration(93_784))
        assertEquals("2h 5m", Formatters.duration(7_500))
        assertEquals("1m 5s", Formatters.duration(65))
        assertEquals("9s", Formatters.duration(9))
    }

    @Test
    fun `parses PVE UPID`() {
        val u = Upid.parse("UPID:pve1:0003A2F1:01B2C3D4:65A1B2C3:qmstart:100:root@pam:")!!
        assertEquals("pve1", u.node)
        assertEquals(0x0003A2F1L, u.pid)
        assertEquals(0x65A1B2C3L, u.startTimeEpochSeconds)
        assertEquals("qmstart", u.type)
        assertEquals("100", u.id)
        assertEquals("root@pam", u.user)
    }

    @Test
    fun `parses PBS UPID with escaped id`() {
        val u = Upid.parse("UPID:pbs:000003E8:0000A1B2:00000005:65A1B2C3:verificationjob:store1\\x3av\\x2d1:root@pam:")!!
        assertEquals("verificationjob", u.type)
        assertEquals("store1:v-1", u.id)
        assertEquals(0x65A1B2C3L, u.startTimeEpochSeconds)
    }

    @Test
    fun `rejects garbage`() {
        assertNull(Upid.parse("nope"))
        assertNull(Upid.parse("UPID:x:y"))
    }
}
