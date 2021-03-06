package thesis

import org.apache.spark.graphx._
import org.apache.spark.rdd.RDD
import org.apache.spark.rdd.RDD.rddToOrderedRDDFunctions
import org.slf4j.{Logger, LoggerFactory}
import thesis.Action.{CREATE, DELETE, UPDATE}
import thesis.DataTypes.{AttributeGraph, Attributes, EdgeId}
import thesis.SnapshotDelta._
import utils.{EntityFilterException, LogUtils}

import java.time.{Duration, Instant}
import scala.collection.mutable
import scala.math.Ordering.Implicits.infixOrderingOps

class SnapshotDelta(val graphs: mutable.MutableList[Snapshot],
                    val logs: RDD[LogTSV],
                    val snapshotType: SnapshotIntervalType) extends TemporalGraph {
  val logger: Logger = getLogger

  def forwardApplyLogs(graph: AttributeGraph, logsToApply: RDD[LogTSV]): AttributeGraph = {
    Graph(
      applyVertexLogsToSnapshot(graph, logsToApply),
      applyEdgeLogsToSnapshot(graph, logsToApply)
    )
  }

  /** Get most recent materialized snapshot
   * Meaning only retrieve snapshots materialized before the given instant
   * This is because we have no way of backwards applying graph
   *
   * @param graphs  List of graphs that we assume is non-empty
   * @param instant the given instant
   * @return Possibly a snapshot if it makes sense
   */
  def getMostRecentMaterializedSnapshot(graphs: Seq[Snapshot], instant: Instant): Option[Snapshot] = {
    val graph = graphs.reduceLeft((snap1, snap2) =>
      if (instant.isBefore(snap2.instant)) {
        snap1
      } else {
        snap2
      })
    if (instant.isBefore(graph.instant)) {
      None // The instant is in the interval before any graphs have been materialized
    } else {
      Some(graph)
    }

  }

  override def activatedVertices(interval: Interval): RDD[VertexId] = {
    val relevantLogs = getLogsInInterval(logs, interval)
    val vertexSquash = getSquashedActionsByVertexId(relevantLogs)

    vertexSquash.filter(_._2.action == CREATE).map(_._1)
  }

  override def activatedEdges(interval: Interval): RDD[EdgeId] = {
    val relevantLogs = getLogsInInterval(logs, interval)
    val edgeSquash = getSquashedActionsByEdgeId(relevantLogs)

    edgeSquash.filter(_._2.action == CREATE).map(_._1)
  }

  override def snapshotAtTime(instant: Instant): Snapshot = {

    val mostRecentMaterializedSnapshot = getMostRecentMaterializedSnapshot(graphs, instant)
    mostRecentMaterializedSnapshot match {
      case Some(snapshot) =>
        if (snapshot.instant == instant) {
          logger.warn("The queried graph is already materialized")
          Snapshot(snapshot.graph, instant)
        } else {
          logger.warn("Closest graph is in the past. Need to apply logs forwards")
          val logsToApply = getLogsInInterval(logs, Interval(snapshot.instant, instant))
          Snapshot(forwardApplyLogs(snapshot.graph, logsToApply), instant)
        }
      case None =>
        logger.warn("Closest graph in the future, and there is no one in the past.")
        val initialLogs = getLogsInInterval(logs, Interval(Instant.MIN, instant))
        Snapshot(createGraph(initialLogs), instant)
    }
  }

  override def directNeighbours(vertexId: VertexId, interval: Interval): RDD[VertexId] = {
    val filteredEdgeLogs = logs.filter(log => log.action == CREATE || log.action == DELETE)
      .filter(log => log.entity match {
        case _: VERTEX => false
        case EDGE(_, srcId, dstId) => srcId == vertexId || dstId == vertexId
      })

    LogUtils.groupEdgeLogsById(filteredEdgeLogs)
      .map(log => log._2.map(_.action) match {
        case CREATE :: Nil => (log._2.toSeq(0).entity match {
          case _: VERTEX => throw new EntityFilterException
          case EDGE(_, srcId, dstId) => if (vertexId == srcId) dstId else srcId
        }, Interval(log._2.toSeq(0).timestamp, Instant.MAX))
        case CREATE :: DELETE :: Nil => (log._2.toSeq(0).entity match { // We assume the the logs are sorted and that DELETE::CREATE can not happen
          case _: VERTEX => throw new EntityFilterException
          case EDGE(_, srcId, dstId) => if (vertexId == srcId) dstId else srcId
        }, Interval(log._2.toSeq(0).timestamp, log._2.toSeq(1).timestamp))
        case _ => throw new IllegalStateException(s"Only CREATE or CREATE+DELETE allowed here.")
      })
      .filter(idWithInterval => interval.overlaps(idWithInterval._2))
      .map(_._1)
      .distinct
  }

  /** Get entity at a certain point in time
   *
   * This method shares a lot of functionality with getting a snapshot at a specific time,
   * but only for a single entity. A lot has been duplicated because we try to only include relevant
   * logs and save processing with this. (Through benchmarking its roughly 60% faster than just using
   * snapshotAtTime() and filtering the specific entity)
   *
   * TODO: Support getEdges using src and dstId (in another function)
   *
   * @param entity  entity to be found
   * @param instant that specific time
   * @return Possibly the desired entity if it exists at the time
   */
  override def getEntity[T <: Entity](entity: T, instant: Instant): Option[(T, Attributes)] = {
    val possibleMaterializedSnapshot = getMostRecentMaterializedSnapshot(graphs, instant)
    possibleMaterializedSnapshot match {
      case Some(snapshot) => getMaterializedEntity(entity, instant, snapshot)
      case None => getUnmaterializedEntity(entity, instant)
    }
  }

  def getMaterializedEntity[T <: Entity](entity: T, instant: Instant, snapshot: Snapshot): Option[(T, Attributes)] = {
    val Snapshot(graph, materializedInstant) = snapshot
    val possibleEntityAttributes = entity match {
      case _: VERTEX => graph.vertices.lookup(entity.id).headOption
      case _: EDGE => graph.edges.map(e => (e.attr.id, e)).lookup(entity.id).headOption.map(_.attr.attributes)
    }

    val relevantLogs = LogUtils.filterEntityLogsById(getLogsInInterval(logs, Interval(materializedInstant, instant)), entity)
    val possibleLog = getPossibleSquashedLogForEntity(relevantLogs, entity)
    (possibleEntityAttributes, possibleLog) match {
      case (Some(entityAttributes), Some(log)) => log.action match {
        case Action.CREATE => throw new IllegalStateException("The entity existed in the last snapshot, thus not CREATE can exist here")
        case Action.UPDATE => Some(entity, rightWayMergeHashMap(entityAttributes, log.attributes))
        case Action.DELETE => None // Entity was deleted
      }
      case (Some(entityAttributes), None) => Some((entity, entityAttributes))
      case (None, Some(log)) => log.action match { // Entity was created in the interval
        case Action.CREATE => Some(entity, log.attributes)
        case Action.UPDATE => throw new IllegalStateException("The entity didn't exist in the last snapshot, therefore no UPDATE can exist")
        case Action.DELETE => None // Entity was created and promptly deleted
      }
      case (None, None) => None // The entity never existed in interval between materialized snapshot and instant
    }
  }

  def getUnmaterializedEntity[T <: Entity](entity: T, instant: Instant): Option[(T, Attributes)] = {
    val relevantLogs = LogUtils.filterEntityLogsById(getLogsInInterval(logs, Interval(Instant.MIN, instant)), entity)
    val possibleLog = getPossibleSquashedLogForEntity(relevantLogs, entity)
    possibleLog match {
      case Some(log) => log.action match {
        case Action.CREATE => Some(entity, log.attributes)
        case Action.UPDATE => throw new IllegalStateException("If it's unmaterialized then there has to be a CREATE")
        case Action.DELETE => None // The entity has been deleted
      }
      case None => None // The entity never existed in the interval (Instant.min,instant)
    }
  }

  def getPossibleSquashedLogForEntity(logs: RDD[LogTSV], entity: Entity): Option[LogTSV] = {
    entity match {
      case _: VERTEX => getSquashedActionsByVertexId(logs).lookup(entity.id).headOption
      case _: EDGE => getSquashedActionsByEdgeId(logs).lookup(entity.id).headOption
    }
  }
}


object SnapshotDelta {
  def getLogger: Logger = LoggerFactory.getLogger("SnapShotDelta")

  def apply[VD, ED](logs: RDD[LogTSV], snapType: SnapshotIntervalType): SnapshotDelta = {
    snapType match {
      // TODO the first argument logsWithSortableKey should be moved into the function
      case SnapshotIntervalType.Time(duration) => createSnapshotModel(logs.map(log => (log.timestamp.getEpochSecond, log)), snapType)
      case SnapshotIntervalType.Count(numberOfActions) => createSnapshotModel(logs.zipWithIndex().map(x => (x._2, x._1)), snapType)
    }
  }

  /** Create snapshotDelta model
   *
   * This function works for both a timed and a counter based model.
   *
   * There is maybe too many mins and maxs that can have a performance hit
   *
   * @param logsWithSortableKey  Logs with a sortable key, either seconds or amount of actions
   * @param snapshotIntervalType The type of snapshot
   * @return SnapShotDelta object
   */
  def createSnapshotModel(logsWithSortableKey: RDD[(Long, LogTSV)], snapshotIntervalType: SnapshotIntervalType): SnapshotDelta = {
    getLogger.warn(s"Creating SnapshotModel with type:$snapshotIntervalType")
    val max = logsWithSortableKey.map(_._1).max() // This will value is only used for Time, but it should be lazy, so its fine
    val min = logsWithSortableKey.map(_._1).min() // This will be 0 for Count and the earliest timestamp for Time

    val (numberOfSnapShots, interval) = snapshotIntervalType match {
      case SnapshotIntervalType.Time(duration) => (((max - min).toFloat / duration).ceil.toInt, duration)
      case SnapshotIntervalType.Count(number) => ((logsWithSortableKey.count().toFloat / number).ceil.toInt, number) // .count() should in _theory_ be the same as 'max'. So these two lines could in theory be identical
    }

    val initLogs = logsWithSortableKey.filterByRange(min, interval - 1).map(_._2)
    val initTimestamp = snapshotIntervalType match {
      case SnapshotIntervalType.Time(duration) => Instant.ofEpochSecond(duration - 1)
      case SnapshotIntervalType.Count(numberOfActions) => initLogs.map(_.timestamp).max()
    }
    val initialGraph = createGraph(initLogs)
    val graphs = mutable.MutableList(Snapshot(initialGraph, initTimestamp))

    for (i <- 1 until numberOfSnapShots) {
      val previousSnapshot = graphs(i - 1)

      // Get subset of logs
      val logInterval = logsWithSortableKey
        .filterByRange(min + (interval * i), min + (interval * (i + 1)) - 1) // This lets ut get a slice of the LTSVs (filterByRange is inclusive)
        .map(_._2) // This maps the type into back to its original form

      val newVertices = applyVertexLogsToSnapshot(previousSnapshot.graph, logInterval)
      val newEdges = applyEdgeLogsToSnapshot(previousSnapshot.graph, logInterval)

      val graphTimestamp = snapshotIntervalType match {
        case SnapshotIntervalType.Time(duration) => Instant.ofEpochSecond(min + (interval * (i + 1) - 1))
        case SnapshotIntervalType.Count(numberOfActions) => logInterval.map(_.timestamp).max()
      }
      val newGraphWithTimestamp = Snapshot(Graph(newVertices, newEdges), graphTimestamp)
      graphs += newGraphWithTimestamp
    }
    new SnapshotDelta(graphs, logsWithSortableKey.map(_._2), snapshotIntervalType)
  }

  def updateEdgeWithLog(edge: Edge[SnapshotEdgePayload], log: LogTSV): Edge[SnapshotEdgePayload] = {
    edge
      .copy(attr =
        edge.attr
          .copy(attributes = rightWayMergeHashMap(edge.attr.attributes, log.attributes)))
  }

  /** Retrieve logs in the inclusive interval
   *
   * @param logs     logs
   * @param interval interval
   * @return
   */
  def getLogsInInterval(logs: RDD[LogTSV], interval: Interval): RDD[LogTSV] = {
    logs
      .map(log => (log.timestamp, log))
      .filterByRange(interval.start, interval.stop)
      .map(_._2)
  }

  def applyEdgeLogsToSnapshot(snapshot: AttributeGraph, logs: RDD[LogTSV]): RDD[Edge[SnapshotEdgePayload]] = {
    val uuid = java.util.UUID.randomUUID.toString.take(5) // For debugging purposes
    val previousSnapshotEdgesKV = snapshot.edges.map(edge => (edge.attr.id, edge))

    val squashedEdgeActions = getSquashedActionsByEdgeId(logs)

    // Combine edges from the snapshot with the current log interval
    val joinedEdges = previousSnapshotEdgesKV.fullOuterJoin(squashedEdgeActions)

    // Create, update or omit (delete) edges based on values are present or not.
    val newSnapshotEdges: RDD[Edge[SnapshotEdgePayload]] = joinedEdges.flatMap(outerJoinedEdge => outerJoinedEdge._2 match {
      case (None, None) => throw new IllegalStateException("The full outer join f*ucked up")
      case (Some(previousEdge), None) => Some(Edge(previousEdge.srcId, previousEdge.dstId, previousEdge.attr)) // Edge existed in the last snapshot, but no changes were done in this interval
      case (Some(previousEdge), Some(log)) => log.action match { // There has been a change to the edge in the interval
        case DELETE => None // Edge or {source,destination} vertex was deleted in the new interval
        case UPDATE => // Edge updated in this interval
          Some(updateEdgeWithLog(previousEdge, log))
        case CREATE =>
          throw new IllegalStateException(s"$uuid An CREATE should never happen since this case should be an action referencing an existing entity")
      }
      case (None, Some(newEdgeAction)) => newEdgeAction.action match { // Edge was introduced in this interval
        case DELETE => None // Edge was introduced then promptly deleted (possibly due to a deleted vertex)
        case CREATE => // Edge was created
          Some(logToEdge(newEdgeAction))
        case UPDATE =>
          throw new IllegalStateException(s"$uuid An UPDATE should never happen if it's new because of how merging of LogTSVs are done $newEdgeAction")
      }
    })
    newSnapshotEdges
  }

  def logToEdge(log: LogTSV): Edge[SnapshotEdgePayload] = {
    log.entity match {
      case _: VERTEX => throw new EntityFilterException
      case EDGE(id, srcId, dstId) => Edge(srcId, dstId, SnapshotEdgePayload(id, log.attributes))
    }
  }

  def getSquashedActionsByEdgeId(logs: RDD[LogTSV]): RDD[(Long, LogTSV)] = {
    LogUtils.groupEdgeLogsById(logs)
      .map(edgeWithActions => (edgeWithActions._1, mergeLogTSVs(edgeWithActions._2)))

  }

  def applyVertexLogsToSnapshot(snapshot: AttributeGraph, logs: RDD[LogTSV]): RDD[(VertexId, Attributes)] = {
    val uuid = java.util.UUID.randomUUID.toString.take(5) // For debug purposes
    val previousSnapshotVertices = snapshot.vertices

    val vertexIDsWithAction: RDD[(VertexId, LogTSV)] = getSquashedActionsByVertexId(logs)

    // Combine vertices from the snapshot with the current log interval
    val joinedVertices = previousSnapshotVertices.fullOuterJoin(vertexIDsWithAction)

    // Create, update or omit (delete) vertices based on values are present or not.
    val newSnapshotVertices = joinedVertices.flatMap(outerJoinedVertex => outerJoinedVertex._2 match {
      case (None, None) => throw new IllegalStateException("The full outer join f*ucked up")
      case (Some(snapshotVertex), None) => Some((outerJoinedVertex._1, snapshotVertex)) // No changes to the vertex in this interval
      case (Some(snapshotVertex), Some(newVertex)) => newVertex.action match { // Changes to the vertex in this interval
        case DELETE => None // Vertex was deleted
        case UPDATE => // Vertex was updated
          Some((outerJoinedVertex._1, rightWayMergeHashMap(snapshotVertex, newVertex.attributes)))
        case CREATE => throw new IllegalStateException(s"$uuid An CREATE should never happen since this case should be an action referencing an existing vertex: $snapshotVertex $newVertex ")
      }
      case (None, Some(newVertex)) => newVertex.action match { // Vertex was introduced in this interval
        case DELETE => None // Vertex was introduced then promptly deleted
        case CREATE => // Vertex was created
          Some((outerJoinedVertex._1, newVertex.attributes))
        case UPDATE =>
          throw new IllegalStateException(s"$uuid An UPDATE should never happen if the vertex is new in the interval, because of the way merging og LogTSVs are done: Vertex $newVertex")
      }
    })
    newSnapshotVertices
  }

  def getSquashedActionsByVertexId(logs: RDD[LogTSV]): RDD[(VertexId, LogTSV)] = {
    LogUtils.groupVertexLogsById(logs)
      .map(vertexWithActions => (vertexWithActions._1, mergeLogTSVs(vertexWithActions._2)))
  }

  def getSquashedActionsByEntityId(logs: RDD[LogTSV]): (RDD[(VertexId, LogTSV)], RDD[(EdgeId, LogTSV)]) = (getSquashedActionsByVertexId(logs), getSquashedActionsByEdgeId(logs))

  def mergeLogTSVs(logs: Iterable[LogTSV]): LogTSV = logs.reduce(mergeLogTSV)

  def mergeLogTSV(prevLog: LogTSV, nextLog: LogTSV): LogTSV = {
    (prevLog.action, nextLog.action) match {
      case (UPDATE, DELETE) => nextLog // DELETE nullifies previous operations
      case (CREATE, DELETE) => nextLog // DELETE nullifies previous operations
      case (UPDATE, UPDATE) =>
        nextLog.copy(attributes = rightWayMergeHashMap(prevLog.attributes, nextLog.attributes)) // Could be either l1 or l2 that is copied
      case (CREATE, UPDATE) =>
        prevLog.copy(attributes = rightWayMergeHashMap(prevLog.attributes, nextLog.attributes))
      case (DELETE, UPDATE) if (prevLog.timestamp == nextLog.timestamp) => prevLog // This edge case does happen
      case (prevLogState, nextLogState) =>
        throw new IllegalStateException(s"($prevLogState,$nextLogState encountered when merging logs. The dataset is inconsistent \n Prev: $prevLog \n Next: $nextLog")

      // Cases that should not happen. We assume the LogTSVs are consistent and makes sense
      // example (DELETE, DELETE), (DELETE, UPDATE) // These do not make sense
    }
  }

  /** Merge two hashmaps with preference for dominantAttributes
   *
   * Attributes is type alias for HashMap[(String,String)]
   *
   * @param attributes         recessive HashMap
   * @param dominantAttributes dominant HashMap
   * @return merged HashMap
   */
  def rightWayMergeHashMap(attributes: Attributes, dominantAttributes: Attributes): Attributes = {
    attributes.merged(dominantAttributes)((_, y) => y)
  }

  /** Create graph from an initial list of LogTSVs
   *
   * @param logs Initial log entries
   * @return new Graph
   */
  def createGraph(logs: RDD[LogTSV]): AttributeGraph = {
    getLogger.warn("Creating (most likely) initial graph from RDD[LogTSV]")
    val firstSnapshotVertices = getInitialVerticesFromLogs(logs)
    val firstSnapshotEdges = getInitialEdgesFromLogs(logs)

    Graph(firstSnapshotVertices, firstSnapshotEdges)
  }

  def getInitialVerticesFromLogs(logs: RDD[LogTSV]): RDD[(VertexId, Attributes)] = {
    val verticesWithAction = getSquashedActionsByVertexId(logs)
    verticesWithAction.flatMap(vertexTuple => vertexTuple._2.action match {
      case CREATE => Some(vertexTuple._1, vertexTuple._2.attributes) // Vertex was created
      case DELETE => None // Vertex was created and deleted
      case UPDATE => throw new IllegalStateException("This should never happen based on the way we do merging")
      // This last case UPDATE should never happen because of the way we do merging?
    })
  }

  def getInitialEdgesFromLogs(logs: RDD[LogTSV]): RDD[Edge[SnapshotEdgePayload]] = {
    val edgesWithAction = getSquashedActionsByEdgeId(logs)
    edgesWithAction.flatMap(edge => edge._2.action match {
      case CREATE => Some(logToEdge(edge._2)) // Edge was created
      case DELETE => None // Edge was created and deleted (Possibly because of a deleted vertex)
      case UPDATE => throw new IllegalStateException("This should never happen based on the way we do merging")
      // This last case UPDATE should never happen because of the way we do merging?
    })
  }

  /** Returns the closest graph for a given instant
   * Currently unused but might be relevant later
   *
   * @param instant   the timestamp
   * @param snapshot1 snapshot1
   * @param snapshot2 snapshot2
   * @return
   */
  def returnClosestGraph(instant: Instant)(snapshot1: Snapshot, snapshot2: Snapshot): Snapshot =
    if (Duration.between(snapshot1.instant, instant).abs() <= Duration.between(snapshot2.instant, instant).abs()) {
      snapshot1
    } else {
      snapshot2
    }
}
