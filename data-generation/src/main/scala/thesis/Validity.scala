package thesis

import org.apache.spark.SparkContext
import org.apache.spark.graphx.{Edge, Graph, VertexId, VertexRDD}
import org.apache.spark.rdd.RDD
import thesis.Action.{CREATE, UPDATE}
import thesis.DataTypes.{Attributes, EdgeId, ValidityAttributeGraph}
import utils.{EntityFilterException, LogUtils, UtilsUtils}

import java.time.Instant
import scala.collection.mutable
import scala.math.Ordered.orderingToOrdered


class Validity(attributeGraph: ValidityAttributeGraph) extends TemporalGraph {
  val underlyingGraph: ValidityAttributeGraph = attributeGraph

  def snapshotAtTimeLandy(instant: Instant): Graph[ValidityEntityPayload, ValidityEntityPayload] = {
    val vertices = localVertices
      .filter(vertex =>
        vertex._2.validFrom < instant &&
          vertex._2.validTo >= instant
      )
      .map(vertex => (vertex._2.id, vertex._2))

    val edges = this.underlyingGraph.edges.filter(edge =>
      edge.attr.validFrom < instant &&
        edge.attr.validTo >= instant
    )
    Graph(vertices, edges)
  }

  override def snapshotAtTime(instant: Instant): Snapshot = {
    val vertices = localVertices
      .filter(vertex =>
        vertex._2.interval.nonInclusiveContains(instant)
      )
      .map(vertex => (vertex._2.id, vertex._2))
      .mapValues(_.attributes)

    val edges = this.underlyingGraph.edges.filter(edge =>
      edge.attr.interval.nonInclusiveContains(instant)
    ).map(e => {
      Edge(e.srcId, e.dstId, SnapshotEdgePayload(e.attr.id, e.attr.attributes))
    })
    Snapshot(Graph(vertices, edges), instant)
  }

  override def directNeighbours(vertexId: VertexId, interval: Interval): RDD[VertexId] = {
    this.underlyingGraph.edges.filter(edge => {
      val edgeInterval = Interval(edge.attr.validFrom, edge.attr.validTo)
      (edge.srcId == vertexId || edge.dstId == vertexId) && interval.overlaps(edgeInterval)
    }
    )
      .map(edge => if (edge.dstId == vertexId) edge.srcId else edge.dstId)
      .distinct
  }

  override def activatedVertices(interval: Interval): RDD[VertexId] = {
    val firstLocalVertices = localVertices // Remove global vertices
      .map(vertex => (vertex._2.id, vertex._2)) // Get the global vertex ID
      .groupByKey() // Group all
      .map(vertex => (vertex._1, getEarliest(vertex._2.toSeq))) // Get first local vertex

    firstLocalVertices.filter(vertex => interval.contains(vertex._2.validFrom))
      .map(vertex => vertex._2.id)
  }

  override def activatedEdges(interval: Interval): RDD[EdgeId] = {
    this.underlyingGraph.edges
      .map(edge => (edge.attr.id, edge.attr))
      .groupByKey()
      .map(edge => (edge._1, getEarliest(edge._2.toSeq)))
      .filter(edge => interval.contains(edge._2.validFrom))
      .map(edge => edge._1)
  }

  /**
   * @param payloads Payload updates for a single entity
   * @return The data that corresponds to the earliest timestamp
   */
  private def getEarliest(payloads: Seq[ValidityEntityPayload]): ValidityEntityPayload = {
    payloads.minBy(_.validFrom)
  }

  /**
   * Get the local vertices, i.e. those who have a payload.
   * Global vertices, which have no payload, are created when edges connect
   * vertices that don't exist from before.
   *
   * @return The local vertices of the graph
   */
  private def localVertices: VertexRDD[ValidityEntityPayload] = {
    this.underlyingGraph.vertices.filter(vertex => vertex._2 != null)
  }

  override def getEntity[T <: Entity](entity: T, timestamp: Instant): Option[(T, Attributes)] = {
    entity match {
      case _: VERTEX => getVertex(entity, timestamp)
      case _: EDGE => getEdge(entity, timestamp)
      case _ => None
    }
  }

  def getVertex[T <: Entity](vertex: T, instant: Instant): Option[(T, Attributes)] = {
    localVertices
      .filter(v => v._2.id == vertex.id)
      .filter(v => v._2.interval.contains(instant))
      .map(v => (vertex, v._2.attributes))
      .collect()
      .headOption
  }

  def getEdge[T <: Entity](edge: T, instant: Instant): Option[(T, Attributes)] = {
    this.underlyingGraph.edges
      .filter(e => e.attr.id == edge.id)
      .filter(e => e.attr.interval.contains(instant))
      .map(e => (edge, e.attr.attributes))
      .collect()
      .headOption
  }

}

object Validity {
  def createEdge(log: LogTSV, validTo: Instant): Edge[ValidityEntityPayload] = {
    log.entity match {
      case _: VERTEX => throw new EntityFilterException
      case EDGE(id, srcId, dstId) => {
        val payload = ValidityEntityPayload(id = id, validFrom = log.timestamp, validTo = validTo, attributes = log.attributes)
        Edge(srcId, dstId, payload)
      }
    }
  }

  def createVertex(log: LogTSV, validTo: Instant): (VertexId, ValidityEntityPayload) = {
    log.entity match {
      case VERTEX(id) => {
        val payload = ValidityEntityPayload(id = id, validFrom = log.timestamp, validTo = validTo, attributes = log.attributes)
        (UtilsUtils.uuid, payload)
      }
      case _: EDGE => throw new EntityFilterException
    }
  }

  def generateEdges(logs: Seq[LogTSV]): Seq[Edge[ValidityEntityPayload]] = {
    val reversedLogs = LogUtils.reverse(logs)
    val aVeryBigDate = Instant.MAX
    var lastTimestamp = aVeryBigDate
    val edges = mutable.MutableList[Edge[ValidityEntityPayload]]()
    for (log <- reversedLogs) {
      // DELETES should not create an edge, but instead record the deletion timestamp to be used later.
      if (log.action == CREATE || log.action == UPDATE) {
        edges += createEdge(log, lastTimestamp)
      }
      lastTimestamp = log.timestamp
    }
    edges
  }

  def generateVertices(logs: Seq[LogTSV]): Seq[(VertexId, ValidityEntityPayload)] = {
    val reversedLogs = LogUtils.reverse(logs)
    val aVeryBigDate = Instant.MAX
    var lastTimestamp = aVeryBigDate
    val vertices = mutable.MutableList[(VertexId, ValidityEntityPayload)]()
    for (log <- reversedLogs) {
      // DELETES should not create a vertex, but instead record the deletion timestamp to be used later.
      if (log.action == CREATE || log.action == UPDATE) {
        vertices += createVertex(log, lastTimestamp)
      }
      lastTimestamp = log.timestamp
    }
    vertices
  }

  def apply(logs: RDD[LogTSV])(implicit sc: SparkContext): Validity = {
    val edges = LogUtils.groupEdgeLogsById(logs)
      .flatMap(actionsByEdge => generateEdges(actionsByEdge._2.toSeq))
    val vertices = LogUtils.groupVertexLogsById(logs)
      .flatMap(actionsByVertex => generateVertices(actionsByVertex._2.toSeq))

    val graph = Graph(vertices, edges)
    new Validity(graph)
  }
}
