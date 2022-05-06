package thesis

import org.apache.spark.graphx.{Edge, Graph}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions.{col, lag, last, when}
import org.apache.spark.sql.{SparkSession, functions}
import org.slf4j.LoggerFactory

import java.time.Instant


object TopologyGraphGenerator {

  /**
   * @param dataSource Intended datasource
   * @return Tuple of file path and delimiter used
   */
  def getPathAndDelim(dataSource: DataSource): (String, String) = dataSource match {
    case DataSource.Reptilian => ("src/main/resources/reptilia-tortoise-network-sl.csv", " ")
    case DataSource.FbMessages => ("src/main/resources/fb-messages-fix.csv", ",")
    case DataSource.ContactsHyperText => ("src/main/resources/ia-contacts_hypertext2009.csv", ",")
  }

  def generateGraph(
                     threshold: BigDecimal,
                     dataSource: DataSource = DataSource.Reptilian
                   )(implicit spark: SparkSession): Graph[Long, Interval] = {
    val (filePath, delimiter) = getPathAndDelim(dataSource)

    val logger = LoggerFactory.getLogger("TopologyGraphGenerator")
    logger.warn(s"Generating graph from dataset $filePath, threshold: $threshold, delimiter: $delimiter")

    val window = Window.orderBy("from", "to", "time")
    val df = spark.read.option("delimiter", delimiter).csv(filePath)
      .toDF("from", "to", "time")
      .withColumn("previous", when(col("to") === lag("to", 1).over(window), lag("time", 1).over(window)).otherwise(""))
      .withColumn("closeness", when(col("previous") === "", "").otherwise(col("time") - col("previous")))
      .withColumn("id", when(col("closeness") === "" || col("closeness") > threshold, functions.monotonically_increasing_id()))
      .withColumn("edgeId", last(col("id"), ignoreNulls = true).over(window.rowsBetween(Window.unboundedPreceding, 0)))
    val tempDf = df.groupBy("edgeId").agg(functions.min("time").alias("from time"), functions.max("time").alias("to time"))
    val leadDf = df.as("self1").join(tempDf.as("self2"), col("self1.id") === col("self2.edgeId"), "inner").select("from", "to", "from time", "to time")

    val vertices: RDD[(Long, Long)] = leadDf
      .select("from")
      .distinct
      .rdd.map(row => row.getAs[String]("from").toLong)
      .map(long => (long, long))

    val edges: RDD[Edge[Interval]] = leadDf
      .select("from", "to", "from time", "to time")
      .rdd.map(
      row => {
        Edge(row.getAs[String]("from").toLong, row.getAs[String]("to").toLong,
          Interval(Instant.ofEpochSecond(row.getAs[String]("from time").toLong), Instant.ofEpochSecond(row.getAs[String]("to time").toLong)))
      }
    )

    Graph(vertices, edges)
  }
}
