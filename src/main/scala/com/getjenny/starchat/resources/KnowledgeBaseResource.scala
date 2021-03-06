package com.getjenny.starchat.resources

/**
  * Created by Angelo Leto <angelo@getjenny.com> on 27/06/16.
  */

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.pattern.CircuitBreaker
import com.getjenny.starchat.entities._
import com.getjenny.starchat.routing._
import com.getjenny.starchat.services.KnowledgeBaseService

import scala.util.{Failure, Success}

trait KnowledgeBaseResource extends MyResource {

  def knowledgeBaseRoutes: Route =
    pathPrefix("""^(index_(?:[a-z]{1,256})_(?:[A-Za-z0-9_]{1,256}))$""".r ~ Slash ~ "knowledgebase") { indexName =>
      val knowledgeBaseService = KnowledgeBaseService
      pathEnd {
        post {
          authenticateBasicAsync(realm = authRealm,
            authenticator = authenticator.authenticator) { user =>
            authorizeAsync(_ =>
              authenticator.hasPermissions(user, indexName, Permissions.write)) {
              parameters("refresh".as[Int] ? 0) { refresh =>
                entity(as[KBDocument]) { document =>
                  val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                  onCompleteWithBreaker(breaker)(knowledgeBaseService.create(indexName, document, refresh)) {
                    case Success(t) =>
                      t match {
                        case Some(v) =>
                          completeResponse(StatusCodes.Created, StatusCodes.BadRequest, Option {
                            v
                          })
                        case None =>
                          log.error("index(" + indexName + ") route=knowledgeBaseRoutes method=POST")
                          completeResponse(StatusCodes.BadRequest,
                            Option {
                              ReturnMessageData(code = 100, message = "Error indexing new document, empty response")
                            })
                      }
                    case Failure(e) =>
                      log.error("index(" + indexName + ") route=knowledgeBaseRoutes method=POST: " + e.getMessage)
                      completeResponse(StatusCodes.BadRequest,
                        Option {
                          ReturnMessageData(code = 101, message = "Error indexing new document")
                        })
                  }
                }
              }
            }
          }
        } ~
          get {
            authenticateBasicAsync(realm = authRealm,
              authenticator = authenticator.authenticator) { user =>
              authorizeAsync(_ =>
                authenticator.hasPermissions(user, indexName, Permissions.read)) {
                parameters("ids".as[String].*) { ids =>
                  val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                  onCompleteWithBreaker(breaker)(knowledgeBaseService.read(indexName, ids.toList)) {
                    case Success(t) =>
                      completeResponse(StatusCodes.OK, StatusCodes.BadRequest, Option {
                        t
                      })
                    case Failure(e) =>
                      log.error("index(" + indexName + ") route=knowledgeBaseRoutes method=GET: " + e.getMessage)
                      completeResponse(StatusCodes.BadRequest,
                        Option {
                          ReturnMessageData(code = 102, message = e.getMessage)
                        })
                  }
                }
              }
            }
          } ~
          delete {
            authenticateBasicAsync(realm = authRealm,
              authenticator = authenticator.authenticator) { user =>
              authorizeAsync(_ =>
                authenticator.hasPermissions(user, indexName, Permissions.write)) {
                val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                onCompleteWithBreaker(breaker)(knowledgeBaseService.deleteAll(indexName)) {
                  case Success(t) =>
                    completeResponse(StatusCodes.OK, StatusCodes.BadRequest, Option {
                      t
                    })
                  case Failure(e) =>
                    log.error("index(" + indexName + ") route=knowledgeBaseRoutes method=DELETE: " + e.getMessage)
                    completeResponse(StatusCodes.BadRequest,
                      Option {
                        ReturnMessageData(code = 103, message = e.getMessage)
                      })
                }
              }
            }
          }
      } ~
        path(Segment) { id =>
          put {
            authenticateBasicAsync(realm = authRealm,
              authenticator = authenticator.authenticator) { user =>
              authorizeAsync(_ =>
                authenticator.hasPermissions(user, indexName, Permissions.write)) {
                parameters("refresh".as[Int] ? 0) { refresh =>
                  entity(as[KBDocumentUpdate]) { update =>
                    val knowledgeBaseService = KnowledgeBaseService
                    val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                    onCompleteWithBreaker(breaker)(knowledgeBaseService.update(indexName, id, update, refresh)) {
                      case Success(t) =>
                        completeResponse(StatusCodes.Created, StatusCodes.BadRequest, t)
                      case Failure(e) =>
                        log.error("index(" + indexName + ") route=knowledgeBaseRoutes method=PUT: " + e.getMessage)
                        completeResponse(StatusCodes.BadRequest,
                          Option {
                            ReturnMessageData(code = 104, message = e.getMessage)
                          })
                    }
                  }
                }
              }
            }
          } ~
            delete {
              authenticateBasicAsync(realm = authRealm,
                authenticator = authenticator.authenticator) { user =>
                authorizeAsync(_ =>
                  authenticator.hasPermissions(user, indexName, Permissions.write)) {
                  parameters("refresh".as[Int] ? 0) { refresh =>
                    val knowledgeBaseService = KnowledgeBaseService
                    val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                    onCompleteWithBreaker(breaker)(knowledgeBaseService.delete(indexName, id, refresh)) {
                      case Success(t) =>
                        completeResponse(StatusCodes.OK, StatusCodes.BadRequest, t)
                      case Failure(e) =>
                        log.error("index(" + indexName + ") route=knowledgeBaseRoutes method=DELETE : " + e.getMessage)
                        completeResponse(StatusCodes.BadRequest,
                          Option {
                            ReturnMessageData(code = 105, message = e.getMessage)
                          })
                    }
                  }
                }
              }
            }
        }
    }

  def knowledgeBaseSearchRoutes: Route =
    pathPrefix("""^(index_(?:[a-z]{1,256})_(?:[A-Za-z0-9_]{1,256}))$""".r ~ Slash ~ "knowledgebase_search") { indexName =>
      pathEnd {
        post {
          authenticateBasicAsync(realm = authRealm,
            authenticator = authenticator.authenticator) { user =>
            authorizeAsync(_ =>
              authenticator.hasPermissions(user, indexName, Permissions.write)) {
              entity(as[KBDocumentSearch]) { docsearch =>
                val knowledgeBaseService = KnowledgeBaseService
                val breaker: CircuitBreaker = StarChatCircuitBreaker.getCircuitBreaker()
                onCompleteWithBreaker(breaker)(knowledgeBaseService.search(indexName, docsearch)) {
                  case Success(t) =>
                    completeResponse(StatusCodes.OK, StatusCodes.BadRequest, Option {
                      t
                    })
                  case Failure(e) =>
                    log.error("index(" + indexName + ") route=decisionTableSearchRoutes method=POST: " + e.getMessage)
                    completeResponse(StatusCodes.BadRequest,
                      Option {
                        ReturnMessageData(code = 106, message = e.getMessage)
                      })
                }
              }
            }
          }
        }
      }
    }
}
