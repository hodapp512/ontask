package dev.ontask.track

import java.lang.management.ManagementFactory

data class ProcMetrics(val rssMb: Double?, val cpuPct: Double?)

/** Process memory/CPU, sampled only when the diagnostics screen asks. */
class SystemMetrics {
    private val os = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
    private var lastCpuNs = os.processCpuTime
    private var lastWall = System.nanoTime()
    private val startWall = System.nanoTime()
    private val startCpu = lastCpuNs

    fun heapMb(): Double = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576.0

    /** CPU % of one core since the previous call (or app start for the first), plus the average since start. */
    fun jvmCpu(): Pair<Double, Double> {
        val cpu = os.processCpuTime; val wall = System.nanoTime()
        val recent = (cpu - lastCpuNs).toDouble() / (wall - lastWall).coerceAtLeast(1) * 100
        val avg = (cpu - startCpu).toDouble() / (wall - startWall).coerceAtLeast(1) * 100
        lastCpuNs = cpu; lastWall = wall
        return recent to avg
    }

    /** RSS via `ps`; for another pid (the helper) as well as our own. */
    fun ps(pid: Long): ProcMetrics = runCatching {
        val p = ProcessBuilder("ps", "-o", "rss=,%cpu=", "-p", pid.toString()).redirectErrorStream(true).start()
        val parts = p.inputStream.bufferedReader().readText().trim().split(Regex("\\s+"))
        p.waitFor()
        ProcMetrics(parts[0].toDouble() / 1024, parts[1].toDouble())
    }.getOrDefault(ProcMetrics(null, null))

    fun self(): ProcMetrics = ps(ProcessHandle.current().pid())
}
