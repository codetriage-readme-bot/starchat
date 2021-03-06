package com.getjenny.starchat.services

/**
  * Created by Angelo Leto <angelo@getjenny.com> on 01/07/16.
  */

import akka.event.{Logging, LoggingAdapter}
import com.getjenny.analyzer.util.RandomNumbers
import com.getjenny.starchat.SCActorSystem
import com.getjenny.starchat.entities._
import org.apache.lucene.search.join._
import org.elasticsearch.action.delete.DeleteResponse
import org.elasticsearch.action.get.{GetResponse, MultiGetItemResponse, MultiGetRequestBuilder, MultiGetResponse}
import org.elasticsearch.action.index.IndexResponse
import org.elasticsearch.action.search.{SearchRequestBuilder, SearchResponse, SearchType}
import org.elasticsearch.action.update.UpdateResponse
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.xcontent.XContentFactory._
import org.elasticsearch.common.xcontent.{XContentBuilder, XContentType}
import org.elasticsearch.index.query.functionscore._
import org.elasticsearch.index.query.{BoolQueryBuilder, InnerHitBuilder, QueryBuilder, QueryBuilders}
import org.elasticsearch.index.reindex.{BulkByScrollResponse, DeleteByQueryAction}
import org.elasticsearch.rest.RestStatus
import org.elasticsearch.script._
import org.elasticsearch.search.SearchHit

import scala.collection.JavaConverters._
import scala.collection.immutable.{List, Map}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scalaz.Scalaz._

object KnowledgeBaseService {
  val elasticClient = KnowledgeBaseElasticClient
  val log: LoggingAdapter = Logging(SCActorSystem.system, this.getClass.getCanonicalName)

  val nested_score_mode = Map[String, ScoreMode]("min" -> ScoreMode.Min, "max" -> ScoreMode.Max,
    "avg" -> ScoreMode.Avg, "total" -> ScoreMode.Total)

  def getIndexName(indexName: String, suffix: Option[String] = None): String = {
    indexName + "." + suffix.getOrElse(elasticClient.kbIndexSuffix)
  }

  def search(indexName: String, documentSearch: KBDocumentSearch): Future[Option[SearchKBDocumentsResults]] = {
    val client: TransportClient = elasticClient.getClient()
    val searchBuilder : SearchRequestBuilder = client.prepareSearch(getIndexName(indexName))
      .setTypes(elasticClient.kbIndexSuffix)
      .setSearchType(SearchType.DFS_QUERY_THEN_FETCH)

    searchBuilder.setMinScore(documentSearch.min_score.getOrElse(
      Option{elasticClient.queryMinThreshold}.getOrElse(0.0f))
    )

    val boolQueryBuilder : BoolQueryBuilder = QueryBuilders.boolQuery()

    documentSearch.doctype match {
      case Some(doctype) => boolQueryBuilder.filter(QueryBuilders.termQuery("doctype", doctype))
      case _ => ;
    }

    documentSearch.verified match {
      case Some(verified) => boolQueryBuilder.filter(QueryBuilders.termQuery("verified", verified))
      case _ => ;
    }

    documentSearch.topics match {
      case Some(topics) => boolQueryBuilder.filter(QueryBuilders.termQuery("topics.base", topics))
      case _ => ;
    }

    documentSearch.dclass match {
      case Some(dclass) => boolQueryBuilder.filter(QueryBuilders.termQuery("dclass", dclass))
      case _ => ;
    }

    documentSearch.state match {
      case Some(state) => boolQueryBuilder.filter(QueryBuilders.termQuery("state", state))
      case _ => ;
    }

    documentSearch.status match {
      case Some(status) => boolQueryBuilder.filter(QueryBuilders.termQuery("status", status))
      case _ => ;
    }

    documentSearch.question match {
      case Some(questionQuery) =>
        boolQueryBuilder.must(QueryBuilders.boolQuery()
          .must(QueryBuilders.matchQuery("question.stem_bm25", questionQuery))
          .should(QueryBuilders.matchPhraseQuery("question.raw", questionQuery)
            .boost(elasticClient.questionExactMatchBoost))
        )

        val questionNegativeNestedQuery: QueryBuilder = QueryBuilders.nestedQuery(
          "question_negative",
          QueryBuilders.matchQuery("question_negative.query.base", questionQuery)
            .minimumShouldMatch(elasticClient.questionNegativeMinimumMatch)
            .boost(elasticClient.questionNegativeBoost),
          ScoreMode.Total
        ).ignoreUnmapped(true)
          .innerHit(new InnerHitBuilder().setSize(100))

        boolQueryBuilder.should(
          questionNegativeNestedQuery
        )
      case _ => ;
    }

    if(documentSearch.random.isDefined && documentSearch.random.getOrElse(false)) {
      val randomBuilder = new RandomScoreFunctionBuilder().seed(RandomNumbers.getInt())
      val functionScoreQuery: QueryBuilder = QueryBuilders.functionScoreQuery(randomBuilder)
      boolQueryBuilder.must(functionScoreQuery)
    }

    if(documentSearch.question_scored_terms.isDefined) {
      val queryTerms = QueryBuilders.boolQuery()
        .should(QueryBuilders.matchQuery("question_scored_terms.term", documentSearch.question_scored_terms.get))
      val script: Script = new Script("doc[\"question_scored_terms.score\"].value")
      val scriptFunction = new ScriptScoreFunctionBuilder(script)
      val functionScoreQuery: QueryBuilder = QueryBuilders.functionScoreQuery(queryTerms, scriptFunction)

      val nestedQuery: QueryBuilder = QueryBuilders.nestedQuery(
        "question_scored_terms",
        functionScoreQuery,
        nested_score_mode.getOrElse(elasticClient.queriesScoreMode, ScoreMode.Total)
      ).ignoreUnmapped(true).innerHit(new InnerHitBuilder().setSize(100))

      boolQueryBuilder.should(nestedQuery)
    }

    if(documentSearch.answer.isDefined) {
      boolQueryBuilder.must(QueryBuilders.matchQuery("answer.stem", documentSearch.answer.get))
    }

    if(documentSearch.conversation.isDefined)
      boolQueryBuilder.must(QueryBuilders.matchQuery("conversation", documentSearch.conversation.get))

    searchBuilder.setQuery(boolQueryBuilder)

    val search_response : SearchResponse = searchBuilder
      .setFrom(documentSearch.from.getOrElse(0)).setSize(documentSearch.size.getOrElse(10))
      .execute()
      .actionGet()

    val documents : Option[List[SearchKBDocument]] = Option { search_response.getHits.getHits.toList.map( { case(e) =>

      val item: SearchHit = e

      // val fields : Map[String, GetField] = item.getFields.toMap
      val id : String = item.getId

      // val score : Float = fields.get("_score").asInstanceOf[Float]
      val source : Map[String, Any] = item.getSourceAsMap.asScala.toMap

      val conversation : String = source.get("conversation") match {
        case Some(t) => t.asInstanceOf[String]
        case None => ""
      }

      val indexInConversation : Option[Int] = source.get("index_in_conversation") match {
        case Some(t) => Option { t.asInstanceOf[Int] }
        case None => None : Option[Int]
      }

      val question : String = source.get("question") match {
        case Some(t) => t.asInstanceOf[String]
        case None => ""
      }

      val questionNegative : Option[List[String]] = source.get("question_negative") match {
        case Some(t) =>
          val res = t.asInstanceOf[java.util.ArrayList[java.util.HashMap[String, String]]]
            .asScala.map(_.asScala.get("query")).filter(_.isDefined).map(_.get).toList
          Option { res }
        case None => None: Option[List[String]]
      }

      val questionScoredTerms: Option[List[(String, Double)]] = source.get("question_scored_terms") match {
        case Some(t) => Option {
          t.asInstanceOf[java.util.ArrayList[java.util.HashMap[String, Any]]].asScala
            .map(pair =>
              (pair.getOrDefault("term", "").asInstanceOf[String],
                pair.getOrDefault("score", 0.0).asInstanceOf[Double]))
            .toList
        }
        case None => None : Option[List[(String, Double)]]
      }

      val answer : String = source.get("answer") match {
        case Some(t) => t.asInstanceOf[String]
        case None => ""
      }

      val answerScoredTerms: Option[List[(String, Double)]] = source.get("answer_scored_terms") match {
        case Some(t) => Option {
          t.asInstanceOf[java.util.ArrayList[java.util.HashMap[String, Any]]].asScala
            .map(pair =>
              (pair.getOrDefault("term", "").asInstanceOf[String],
                pair.getOrDefault("score", 0.0).asInstanceOf[Double]))
            .toList
        }
        case None => None : Option[List[(String, Double)]]
      }

      val verified : Boolean = source.get("verified") match {
        case Some(t) => t.asInstanceOf[Boolean]
        case None => false
      }

      val topics : Option[String] = source.get("topics") match {
        case Some(t) => Option { t.asInstanceOf[String] }
        case None => None : Option[String]
      }

      val dclass : Option[String] = source.get("dclass") match {
        case Some(t) => Option { t.asInstanceOf[String] }
        case None => None : Option[String]
      }

      val doctype : String = source.get("doctype") match {
        case Some(t) => t.asInstanceOf[String]
        case None => doctypes.normal
      }

      val state : Option[String] = source.get("state") match {
        case Some(t) => Option { t.asInstanceOf[String] }
        case None => None : Option[String]
      }

      val status : Int = source.get("status") match {
        case Some(t) => t.asInstanceOf[Int]
        case None => 0
      }

      val document : KBDocument = KBDocument(id = id, conversation = conversation,
        index_in_conversation = indexInConversation, question = question,
        question_negative = questionNegative,
        question_scored_terms = questionScoredTerms,
        answer = answer,
        answer_scored_terms = answerScoredTerms,
        verified = verified,
        topics = topics,
        dclass = dclass,
        doctype = doctype,
        state = state,
        status = status)

      val searchDocument : SearchKBDocument = SearchKBDocument(score = item.getScore, document = document)
      searchDocument
    }) }

    val filteredDoc : List[SearchKBDocument] = documents.getOrElse(List[SearchKBDocument]())

    val maxScore : Float = search_response.getHits.getMaxScore
    val total : Int = filteredDoc.length
    val searchResults : SearchKBDocumentsResults = SearchKBDocumentsResults(total = total, max_score = maxScore,
      hits = filteredDoc)

    val searchResultsOption : Future[Option[SearchKBDocumentsResults]] = Future { Option { searchResults } }
    searchResultsOption
  }

  def create(indexName: String, document: KBDocument, refresh: Int): Future[Option[IndexDocumentResult]] = Future {
    val builder : XContentBuilder = jsonBuilder().startObject()

    builder.field("id", document.id)
    builder.field("conversation", document.conversation)

    document.index_in_conversation match {
      case Some(t) => builder.field("index_in_conversation", t)
      case None => ;
    }

    builder.field("question", document.question)

    document.question_negative match {
      case Some(t) =>
        val array = builder.startArray("question_negative")
        t.foreach(q => {
          array.startObject().field("query", q).endObject()
        })
        array.endArray()
      case None => ;
    }

    document.question_scored_terms match {
      case Some(t) =>
        val array = builder.startArray("question_scored_terms")
        t.foreach{case(term, score) =>
          array.startObject().field("term", term)
            .field("score", score).endObject()
        }
        array.endArray()
      case None => ;
    }

    builder.field("answer", document.answer)

    document.answer_scored_terms match {
      case Some(t) =>
        val array = builder.startArray("answer_scored_terms")
        t.foreach{case(term, score) =>
          array.startObject().field("term", term)
            .field("score", score).endObject()
        }
        array.endArray()
      case None => ;
    }

    builder.field("verified", document.verified)

    document.topics match {
      case Some(t) => builder.field("topics", t)
      case None => ;
    }
    builder.field("doctype", document.doctype)

    document.dclass match {
      case Some(t) => builder.field("dclass", t)
      case None => ;
    }
    document.state match {
      case Some(t) => builder.field("state", t)
      case None => ;
    }

    builder.field("status", document.status)

    builder.endObject()

    val json: String = builder.string()
    val client: TransportClient = elasticClient.getClient()
    val response: IndexResponse =
      client.prepareIndex().setIndex(getIndexName(indexName)).setType(elasticClient.kbIndexSuffix)
        .setId(document.id)
        .setSource(json, XContentType.JSON).get()

    if (refresh =/= 0) {
      val refresh_index = elasticClient.refreshIndex(getIndexName(indexName))
      if(refresh_index.failed_shards_n > 0) {
        throw new Exception("KnowledgeBase : index refresh failed: (" + indexName + ")")
      }
    }

    val doc_result: IndexDocumentResult = IndexDocumentResult(index = response.getIndex,
      dtype = response.getType,
      id = response.getId,
      version = response.getVersion,
      created = response.status === RestStatus.CREATED
    )

    Option {doc_result}
  }

  def update(indexName: String, id: String, document: KBDocumentUpdate, refresh: Int):
  Future[Option[UpdateDocumentResult]] = Future {
    val builder : XContentBuilder = jsonBuilder().startObject()

    document.conversation match {
      case Some(t) => builder.field("conversation", t)
      case None => ;
    }

    document.question match {
      case Some(t) => builder.field("question", t)
      case None => ;
    }

    document.question_negative match {
      case Some(t) =>
        val array = builder.startArray("question_negative")
        t.foreach(q => {
          array.startObject().field("query", q).endObject()
        })
        array.endArray()
      case None => ;
    }

    document.question_scored_terms match {
      case Some(t) =>
        val array = builder.startArray("question_scored_terms")
        t.foreach{case(term, score) =>
          array.startObject().field("term", term)
            .field("score", score).endObject()
        }
        array.endArray()
      case None => ;
    }

    document.index_in_conversation match {
      case Some(t) => builder.field("index_in_conversation", t)
      case None => ;
    }

    document.answer match {
      case Some(t) => builder.field("answer", t)
      case None => ;
    }

    document.answer_scored_terms match {
      case Some(t) =>
        val array = builder.startArray("answer_scored_terms")
        t.foreach{
          case(term, score) =>
            array.startObject().field("term", term).field("score", score).endObject()
        }
        array.endArray()
      case None => ;
    }

    document.verified match {
      case Some(t) => builder.field("verified", t)
      case None => ;
    }

    document.topics match {
      case Some(t) => builder.field("topics", t)
      case None => ;
    }

    document.dclass match {
      case Some(t) => builder.field("dclass", t)
      case None => ;
    }

    document.doctype match {
      case Some(t) => builder.field("doctype", t)
      case None => ;
    }

    document.state match {
      case Some(t) => builder.field("state", t)
      case None => ;
    }

    document.status match {
      case Some(t) => builder.field("status", t)
      case None => ;
    }

    builder.endObject()

    val client: TransportClient = elasticClient.getClient()
    val response: UpdateResponse = client.prepareUpdate().setIndex(getIndexName(indexName))
      .setType(elasticClient.kbIndexSuffix).setId(id)
      .setDoc(builder)
      .get()

    if (refresh =/= 0) {
      val refresh_index = elasticClient.refreshIndex(getIndexName(indexName))
      if(refresh_index.failed_shards_n > 0) {
        throw new Exception("KnowledgeBase : index refresh failed: (" + indexName + ")")
      }
    }

    val docResult: UpdateDocumentResult = UpdateDocumentResult(index = response.getIndex,
      dtype = response.getType,
      id = response.getId,
      version = response.getVersion,
      created = response.status === RestStatus.CREATED
    )

    Option {docResult}
  }

  def deleteAll(indexName: String): Future[Option[DeleteDocumentsResult]] = Future {
    val client: TransportClient = elasticClient.getClient()
    val qb: QueryBuilder = QueryBuilders.matchAllQuery()
    val response: BulkByScrollResponse =
      DeleteByQueryAction.INSTANCE.newRequestBuilder(client).setMaxRetries(10)
        .source(getIndexName(indexName))
        .filter(qb)
        .get()

    val deleted: Long = response.getDeleted

    val result: DeleteDocumentsResult = DeleteDocumentsResult(message = "delete", deleted = deleted)
    Option {result}
  }

  def delete(indexName: String, id: String, refresh: Int): Future[Option[DeleteDocumentResult]] = Future {
    val client: TransportClient = elasticClient.getClient()
    val response: DeleteResponse = client.prepareDelete().setIndex(getIndexName(indexName))
      .setType(elasticClient.kbIndexSuffix).setId(id).get()

    if (refresh =/= 0) {
      val refresh_index = elasticClient.refreshIndex(getIndexName(indexName))
      if(refresh_index.failed_shards_n > 0) {
        throw new Exception("KnowledgeBase : index refresh failed: (" + indexName + ")")
      }
    }

    val docResult: DeleteDocumentResult = DeleteDocumentResult(index = response.getIndex,
      dtype = response.getType,
      id = response.getId,
      version = response.getVersion,
      found = response.status =/= RestStatus.NOT_FOUND
    )

    Option {docResult}
  }

  def read(indexName: String, ids: List[String]): Future[Option[SearchKBDocumentsResults]] = {
    val client: TransportClient = elasticClient.getClient()
    val multigetBuilder: MultiGetRequestBuilder = client.prepareMultiGet()
    multigetBuilder.add(getIndexName(indexName), elasticClient.kbIndexSuffix, ids:_*)
    val response: MultiGetResponse = multigetBuilder.get()

    val documents : Option[List[SearchKBDocument]] = Option { response.getResponses
      .toList.filter((p: MultiGetItemResponse) => p.getResponse.isExists).map( { case(e) =>

      val item: GetResponse = e.getResponse

      val id : String = item.getId

      val source : Map[String, Any] = item.getSource.asScala.toMap

      val conversation : String = source.get("conversation") match {
        case Some(t) => t.asInstanceOf[String]
        case None => ""
      }

      val indexInConversation : Option[Int] = source.get("index_in_conversation") match {
        case Some(t) => Option { t.asInstanceOf[Int] }
        case None => None : Option[Int]
      }

      val question : String = source.get("question") match {
        case Some(t) => t.asInstanceOf[String]
        case None => ""
      }

      val questionNegative : Option[List[String]] = source.get("question_negative") match {
        case Some(t) =>
          val res = t.asInstanceOf[java.util.ArrayList[java.util.HashMap[String, String]]]
            .asScala.map(_.getOrDefault("query", None.orNull)).filter(_ =/= None.orNull).toList
          Option { res }
        case None => None: Option[List[String]]
      }

      val questionScoredTerms: Option[List[(String, Double)]] = source.get("question_scored_terms") match {
        case Some(t) =>
          Option {
            t.asInstanceOf[java.util.ArrayList[java.util.HashMap[String, Any]]]
              .asScala.map(_.asScala.toMap)
              .map(term => (term.getOrElse("term", "").asInstanceOf[String],
                term.getOrElse("score", 0.0).asInstanceOf[Double])).filter { case (term, _) => term =/= "" }.toList
          }
        case None => None : Option[List[(String, Double)]]
      }

      val answer : String = source.get("answer") match {
        case Some(t) => t.asInstanceOf[String]
        case None => ""
      }

      val answerScoredTerms: Option[List[(String, Double)]] = source.get("answer_scored_terms") match {
        case Some(t) =>
          Option {
            t.asInstanceOf[java.util.ArrayList[java.util.HashMap[String, Any]]]
              .asScala.map(_.asScala.toMap)
              .map(term => (term.getOrElse("term", "").asInstanceOf[String],
                term.getOrElse("score", 0.0).asInstanceOf[Double]))
              .filter{case(term,_) => term =/= ""}.toList
          }
        case None => None : Option[List[(String, Double)]]
      }

      val verified : Boolean = source.get("verified") match {
        case Some(t) => t.asInstanceOf[Boolean]
        case None => false
      }

      val topics : Option[String] = source.get("topics") match {
        case Some(t) => Option { t.asInstanceOf[String] }
        case None => None : Option[String]
      }

      val dclass : Option[String] = source.get("dclass") match {
        case Some(t) => Option { t.asInstanceOf[String] }
        case None => None : Option[String]
      }

      val doctype : String = source.get("doctype") match {
        case Some(t) => t.asInstanceOf[String]
        case None => doctypes.normal
      }

      val state : Option[String] = source.get("state") match {
        case Some(t) => Option { t.asInstanceOf[String] }
        case None => None : Option[String]
      }

      val status : Int = source.get("status") match {
        case Some(t) => t.asInstanceOf[Int]
        case None => 0
      }

      val document : KBDocument = KBDocument(id = id, conversation = conversation,
        index_in_conversation = indexInConversation,
        question = question,
        question_negative = questionNegative,
        question_scored_terms = questionScoredTerms,
        answer = answer,
        answer_scored_terms = answerScoredTerms,
        verified = verified,
        topics = topics,
        dclass = dclass,
        doctype = doctype,
        state = state,
        status = status)

      val searchDocument : SearchKBDocument = SearchKBDocument(score = .0f, document = document)
      searchDocument
    }) }

    val filteredDoc : List[SearchKBDocument] = documents.getOrElse(List[SearchKBDocument]())

    val maxScore : Float = .0f
    val total : Int = filteredDoc.length
    val searchResults : SearchKBDocumentsResults = SearchKBDocumentsResults(total = total, max_score = maxScore,
      hits = filteredDoc)

    val searchResultsOption : Future[Option[SearchKBDocumentsResults]] = Future { Option { searchResults } }
    searchResultsOption
  }

}
