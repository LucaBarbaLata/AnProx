package io.github.lucabarbalata.anprox.model

import io.github.lucabarbalata.anprox.model.format.ChartScale
import io.github.lucabarbalata.anprox.model.format.SeriesStats
import io.github.lucabarbalata.anprox.model.json.ProxmoxJson
import io.github.lucabarbalata.anprox.model.pve.RrdPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChartTest {

    @Test
    fun `nice tops in decimal`() {
        assertEquals(1.0, ChartScale.niceCeil(0.0))
        assertEquals(1.0, ChartScale.niceCeil(-3.0))
        assertEquals(1.0, ChartScale.niceCeil(Double.NaN))
        assertEquals(1.0, ChartScale.niceCeil(1.0))
        assertEquals(0.5, ChartScale.niceCeil(0.42))
        assertEquals(2.5, ChartScale.niceCeil(2.1))
        assertEquals(5.0, ChartScale.niceCeil(3.2))
        assertEquals(10.0, ChartScale.niceCeil(7.0))
        assertEquals(2000.0, ChartScale.niceCeil(1234.0))
    }

    @Test
    fun `nice tops in binary units`() {
        val gib = 1024.0 * 1024 * 1024
        assertEquals(1.0, ChartScale.niceCeilBytes(0.0))
        assertEquals(500.0, ChartScale.niceCeilBytes(400.0))
        assertEquals(5 * gib, ChartScale.niceCeilBytes(3.1e9))
        assertEquals(2 * 1024.0 * 1024, ChartScale.niceCeilBytes(1.5 * 1024 * 1024))
    }

    @Test
    fun `stats skip gaps`() {
        val s = SeriesStats.of(listOf(null, 0.2, 0.6, null, 0.4, null))
        assertEquals(0.4, s.latest)
        assertEquals(0.6, s.peak)
        assertEquals(0.4, s.average!!, 1e-9)

        val empty = SeriesStats.of(listOf(null, null))
        assertNull(empty.latest)
        assertNull(empty.average)
        assertNull(empty.peak)
    }

    @Test
    fun `rrd points tolerate missing fields and quoted numbers`() {
        // Shape of GET /nodes/pve1/qemu/100/rrddata?timeframe=hour: a stopped interval has no cpu/mem.
        val json = """
            {"data":[
              {"time":1728900000,"maxcpu":4,"maxmem":8589934592,"maxdisk":68719476736,"disk":0},
              {"time":1728900060,"cpu":"0.125","maxcpu":4,"mem":2147483648,"maxmem":8589934592,
               "netin":1520.5,"netout":880.25,"diskread":0,"diskwrite":4096}
            ]}
        """.trimIndent()
        val points = ProxmoxJson.decodeFromString<ApiEnvelope<List<RrdPoint>>>(json).data!!
        assertNull(points[0].cpu)
        assertEquals(8589934592.0, points[0].maxmem)
        assertEquals(0.125, points[1].cpu)
        assertEquals(1520.5, points[1].netin)
    }
}
