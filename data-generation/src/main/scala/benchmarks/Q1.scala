package benchmarks

import thesis.SnapshotIntervalType.Count
import thesis.UpdateDistributions.loadOrGenerateLogs
import thesis.{CorrelationMode, DistributionType, Interval, SnapshotDelta, Validity}
import utils.TimeUtils.secondsToInstant

import java.time.Instant

class Q1(
          distributionType: DistributionType,
          correlationMode: CorrelationMode = CorrelationMode.PositiveCorrelation,
          iterationCount: Int = 5,
          customColumn: String = "Number of logs",
          benchmarkSuffixes: Seq[String] = Seq("landy", "snapshot")
        ) extends ComparisonBenchmark(distributionType, iterationCount, customColumn, benchmarkSuffixes) {

  override def execute(iteration: Int): Unit = {
    logger.warn(s"i $iteration: Generating distribution and logs")
    val logs = loadOrGenerateLogs(graph, distribution(iteration), dataSource, correlationMode = correlationMode)

    val numberOfLogs = logs.count()
    logger.warn(s"i $iteration: Number of logs $numberOfLogs")

    logger.warn(s"i $iteration: Generating graphs")
    val landyGraph = Validity(logs)
    val snapshotDeltaGraph = SnapshotDelta(logs, Count((numberOfLogs / 10).toInt))

    val interval = Interval(0, Instant.MAX)

    // Warm up to ensure the first doesn't require more work.
    logger.warn(s"i $iteration: Running warmup")
    landyGraph.directNeighbours(vertexId, Interval(0, 0)).collect()
    snapshotDeltaGraph.directNeighbours(vertexId, Interval(0, 0)).collect()

    logger.warn(s"i $iteration: Unpersisting, then running landy")
    unpersist()
    benchmarks(0).benchmarkAvg(landyGraph.directNeighbours(vertexId, interval).collect(), customColumnValue = numberOfLogs.toString)

    logger.warn(s"i $iteration: Unpersisting, then running snapshotsdelta")
    unpersist()
    benchmarks(1).benchmarkAvg(snapshotDeltaGraph.directNeighbours(vertexId, interval).collect(), customColumnValue = numberOfLogs.toString)
  }
}
