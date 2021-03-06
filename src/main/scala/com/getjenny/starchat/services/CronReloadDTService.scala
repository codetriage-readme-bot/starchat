package com.getjenny.starchat.services

/**
  * Created by Angelo Leto <angelo@getjenny.com> on 23/08/17.
  */

import akka.actor.{Actor, Props}
import akka.event.{Logging, LoggingAdapter}
import com.getjenny.starchat.SCActorSystem
import com.getjenny.starchat.entities._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}
import scalaz.Scalaz._

object CronReloadDTService  {
  implicit def executionContext: ExecutionContext = SCActorSystem.system.dispatcher
  private[this] val log: LoggingAdapter = Logging(SCActorSystem.system, this.getClass.getCanonicalName)
  private[this] val analyzerService: AnalyzerService.type = AnalyzerService
  private[this] val dtReloadService: DtReloadService.type = DtReloadService
  private[this] val systemIndexManagementService: SystemIndexManagementService.type = SystemIndexManagementService
  private[this] var updateTimestamp: Long = 0

  val tickMessage = "tick"

  class ReloadAnalyzersTickActor extends Actor {
    def receive: PartialFunction[Any, Unit] = {
      case `tickMessage` =>
        dtReloadService.allDTReloadTimestamp(Some(updateTimestamp)) match {
          case Some(indices) =>
            indices.foreach { dtReloadEntry =>
              val indexAnalyzers: Option[ActiveAnalyzers] =
                analyzerService.analyzersMap.get(dtReloadEntry.indexName)
              val localReloadTimestamp = indexAnalyzers match {
                case Some(ts) => ts.lastReloadingTimestamp
                case _ => dtReloadService.DT_RELOAD_TIMESTAMP_DEFAULT
              }

              if (dtReloadEntry.timestamp > 0 && localReloadTimestamp < dtReloadEntry.timestamp) {
                log.info("dt reloading, timestamp for index(" + dtReloadEntry.indexName + "): "
                  + dtReloadEntry.timestamp)

                val reloadResult: Try[Option[DTAnalyzerLoad]] =
                  Await.ready(
                    analyzerService.loadAnalyzers(indexName = dtReloadEntry.indexName), 60.seconds
                  ).value.getOrElse(
                    Failure(
                      throw new Exception("ReloadAnalyzersTickActor: getting an empty response reloading analyzers"))
                  )
                updateTimestamp = math.max(updateTimestamp, localReloadTimestamp)
                reloadResult match {
                  case Success(relRes) =>
                    log.info("Analyzer loaded for index(" + dtReloadEntry + "), remote ts: " + dtReloadEntry )
                    analyzerService.analyzersMap(dtReloadEntry.indexName)
                      .lastReloadingTimestamp = dtReloadEntry.timestamp
                  case Failure(e) =>
                    log.error("unable to load analyzers for index(" +
                      dtReloadEntry + ") from the cron job" + e.getMessage)
                }
              }
            }
          case _ =>
            log.debug("No entries on dt reload index")
        }
    }
  }

  def reloadAnalyzers(): Unit = {
    if (systemIndexManagementService.elasticClient.dtReloadCheckFrequency > 0) {
      val reloadDecisionTableActorRef =
        SCActorSystem.system.actorOf(Props(new ReloadAnalyzersTickActor))
      val delay: Int = if(systemIndexManagementService.elasticClient.dtReloadCheckDelay >= 0) {
        systemIndexManagementService.elasticClient.dtReloadCheckDelay
      } else {
        val r = scala.util.Random
        val d = r.nextInt(math.abs(systemIndexManagementService.elasticClient.dtReloadCheckDelay))
        d
      }

      SCActorSystem.system.scheduler.schedule(
        delay seconds,
        systemIndexManagementService.elasticClient.dtReloadCheckFrequency seconds,
        reloadDecisionTableActorRef,
        tickMessage)
    }
  }

  def reloadAnalyzersOnce(): Unit = {
    val updateEventsActorRef = SCActorSystem.system.actorOf(Props(new ReloadAnalyzersTickActor))
    SCActorSystem.system.scheduler.scheduleOnce(0 seconds, updateEventsActorRef, tickMessage)
  }

}
