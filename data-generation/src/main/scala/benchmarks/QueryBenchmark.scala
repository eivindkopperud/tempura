package benchmarks

import org.apache.spark.SparkContext
import org.apache.spark.sql.SparkSession
import org.slf4j.{Logger, LoggerFactory}
import thesis.SparkConfiguration.getSparkSession
import utils.{Benchmark, FileWriter}

abstract class QueryBenchmark(val iterationCount: Int, val customColumn: String, benchmarkSuffixes: Seq[String] = Seq()) extends Serializable {
  implicit val spark: SparkSession = getSparkSession
  implicit val sc: SparkContext = spark.sparkContext
  var benchmarks: Seq[Benchmark] = Seq()

  val logger: Logger = LoggerFactory.getLogger(getClass.getSimpleName)

  def run: Unit = {
    logger.warn("RUN STARTING")
    initialize
    for (i <- 1 to iterationCount) {
      logger.warn(s"Iteration $i")
      execute(i)
    }
    logger.warn("Running teardown")
    tearDown()
    logger.warn("RUN FINISHED")
  }

  private def initialize: Unit = {
    benchmarkSuffixes.length match {
      case 0 => initializeSingle()
      case 1 => initializeSingle()
      case _ => initializeMultiple(benchmarkSuffixes)
    }
    benchmarks.foreach(_.writeHeader())
  }

  def initializeSingle(): Unit = {
    val fileWriter = FileWriter(filename = getClass.getSimpleName)
    benchmarks = benchmarks :+ Benchmark(fileWriter, textPrefix = getClass.getSimpleName, customColumn = customColumn)
  }

  def initializeMultiple(benchmarkSuffixes: Seq[String]): Unit = {
    benchmarkSuffixes.foreach(suffix => {
      val benchmarkId = s"${getClass.getSimpleName}-$suffix"
      val fileWriter = FileWriter(filename = benchmarkId)
      benchmarks = benchmarks :+ Benchmark(fileWriter, textPrefix = benchmarkId, customColumn = customColumn)
    })
  }

  def execute(iteration: Int): Unit

  def tearDown(): Unit = benchmarks.foreach(_.close())

  def unpersist(): Unit = sc.getPersistentRDDs.foreach(_._2.unpersist(blocking = true))


}
