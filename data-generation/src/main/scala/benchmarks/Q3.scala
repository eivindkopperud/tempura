package benchmarks

import org.apache.spark.graphx.Graph
import thesis.DataSource.ContactsHyperText
import thesis.DistributionType.UniformType
import thesis.SnapshotIntervalType.Count
import thesis.TopologyGraphGenerator.generateGraph
import thesis.UpdateDistributions.{addGraphUpdateDistribution, generateLogs}
import thesis.{DataSource, Interval, Landy, SnapshotDelta, VERTEX}
import utils.TimeUtils.secondsToInstant

class Q3(
          iterationCount: Int = 5,
          customColumn: String = "average number of logs for each entity",
          benchmarkSuffixes: Seq[String] = Seq("landy", "snapshot")
        ) extends QueryBenchmark(iterationCount, customColumn, benchmarkSuffixes) {
  val threshold = 40
  val dataSource: DataSource = ContactsHyperText
  val distribution: Int => UniformType = (iteration: Int) => UniformType(1, 1 + 2 * iteration)
  val timestamp = 1082148639L
  val intervalDelta = 1000
  val vertexId = 37

  lazy val graph: Graph[Long, Interval] = {
    generateGraph(threshold, dataSource).mapEdges(edge => {
      val Interval(start, stop) = edge.attr
      if (stop.isBefore(start)) Interval(stop, start) else Interval(start, stop)
    })
  }

  override def execute(iteration: Int): Unit = {
    val g = addGraphUpdateDistribution(graph, distribution(iteration))
    val logs = generateLogs(g)

    val landyGraph = Landy(logs)
    val snapshotDeltaGraph = SnapshotDelta(logs, Count(intervalDelta))

    val expectedLogPrEntity = (iteration + 1).toString

    // Warm up to ensure the first doesn't require more work.
    landyGraph.getEntity(VERTEX(vertexId), 0L)
    snapshotDeltaGraph.getEntity(VERTEX(vertexId), 0L)

    unpersist()
    benchmarks(0).benchmarkAvg(landyGraph.getEntity(VERTEX(vertexId), timestamp), customColumnValue = expectedLogPrEntity)
    unpersist()
    benchmarks(1).benchmarkAvg(snapshotDeltaGraph.getEntity(VERTEX(vertexId), timestamp), customColumnValue = expectedLogPrEntity)
  }
}