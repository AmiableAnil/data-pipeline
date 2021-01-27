package org.sunbird.job.function

import java.lang.reflect.Type

import akka.dispatch.ExecutionContexts
import com.google.gson.reflect.TypeToken
import org.apache.commons.lang3.StringUtils
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.slf4j.LoggerFactory
import org.sunbird.job.{BaseProcessFunction, Metrics}
import org.sunbird.job.publish.domain.PublishMetadata
import org.sunbird.job.publish.helpers.QuestionSetPublisher
import org.sunbird.job.publish.util.QuestionPublishUtil
import org.sunbird.job.task.QuestionSetPublishConfig
import org.sunbird.job.util.{CassandraUtil, HttpUtil, Neo4JUtil, ScalaJsonUtil}
import org.sunbird.publish.core.{ExtDataConfig, ObjectData}
import org.sunbird.publish.util.CloudStorageUtil

import scala.concurrent.ExecutionContext

class QuestionSetPublishFunction(config: QuestionSetPublishConfig, httpUtil: HttpUtil,
                                 @transient var neo4JUtil: Neo4JUtil = null,
                                 @transient var cassandraUtil: CassandraUtil = null,
                                 @transient var cloudStorageUtil: CloudStorageUtil = null)
                                (implicit val stringTypeInfo: TypeInformation[String])
  extends BaseProcessFunction[PublishMetadata, String](config) with QuestionSetPublisher {

	private[this] val logger = LoggerFactory.getLogger(classOf[QuestionSetPublishFunction])
	val mapType: Type = new TypeToken[java.util.Map[String, AnyRef]]() {}.getType
	private val readerConfig = ExtDataConfig(config.questionSetKeyspaceName, config.questionSetTableName)
	private val qReaderConfig = ExtDataConfig(config.questionKeyspaceName, config.questionTableName)
	@transient var ec: ExecutionContext = _
	private val pkgTypes = List("SPINE", "ONLINE")


	override def open(parameters: Configuration): Unit = {
		super.open(parameters)
		cassandraUtil = new CassandraUtil(config.cassandraHost, config.cassandraPort)
		neo4JUtil = new Neo4JUtil(config.graphRoutePath, config.graphName)
		cloudStorageUtil = new CloudStorageUtil(config)
		ec = ExecutionContexts.global
	}

	override def close(): Unit = {
		super.close()
		cassandraUtil.close()
	}

	override def metricsList(): List[String] = {
		List(config.questionSetPublishEventCount)
	}

	override def processElement(data: PublishMetadata, context: ProcessFunction[PublishMetadata, String]#Context, metrics: Metrics): Unit = {
		logger.info("QuestionSet publishing started for : " + data.identifier)
		val obj = getObject(data.identifier, data.pkgVersion, readerConfig)(neo4JUtil, cassandraUtil)
		logger.info("processElement ::: obj hierarchy ::: "+ScalaJsonUtil.serialize(obj.hierarchy.getOrElse(Map())))
		val messages:List[String] = validate(obj, obj.identifier, validateQuestionSet)
		if (messages.isEmpty) {
			// Get all the questions from hierarchy
			val qList: List[ObjectData] = getQuestions(obj, qReaderConfig)(cassandraUtil)
			println("qList ::: "+qList)
			// Filter out questions having visibility parent (which need to be published)
			val childQuestions: List[ObjectData] = qList.filter(q => isValidChildQuestion(q))
			//TODO: Remove below statement
			childQuestions.foreach(ch=> println("child questions visibility parent identifier : "+ch.identifier))
			// Publish Child Questions
			QuestionPublishUtil.publishQuestions(obj.identifier, childQuestions)(neo4JUtil, cassandraUtil, qReaderConfig)
			// Enrich Object as well as hierarchy
			val enrichedObj = enrichObject(obj)(neo4JUtil, cassandraUtil, readerConfig)
			logger.info("object enrichment done...")
			logger.info(" obj metadata post enrichment :: "+ScalaJsonUtil.serialize(enrichedObj.metadata))
			logger.info(" obj hierarchy post enrichment :: "+ScalaJsonUtil.serialize(enrichedObj.hierarchy.get))
			// Generate ECAR
			val ecarRes: Map[String, String]  = generateEcar(enrichedObj, pkgTypes)(ec, cloudStorageUtil)
			println("ecar response ::: "+ecarRes)

			// Generate PDF URL
			val (pdfUrl, previewUrl) = getPdfFileUrl(qList, enrichedObj, "questionSetTemplate.vm")(httpUtil, cloudStorageUtil)
			val finalPdfUrl = pdfUrl.getOrElse("")
			val finalPreviewUrl = previewUrl.getOrElse("")
			println("finalPdfUrl ::: "+finalPdfUrl)
			println("finalPreviewUrl ::: "+finalPreviewUrl)
			//TODO: update the root metadata with ecar urls. (downloadUrl & variants, pdfUrl, previewUrl)
			//TODO: Implement the dummyFunc function to save hierarchy into cassandra.
			saveOnSuccess(enrichedObj, dummyFunc)(neo4JUtil)
			logger.info("QuestionSet publishing completed successfully for : " + data.identifier)
		} else {
			saveOnFailure(obj, messages)(neo4JUtil)
			logger.info("QuestionSet publishing failed for : " + data.identifier)
		}
	}

	def isValidChildQuestion(obj: ObjectData): Boolean = {
		StringUtils.equalsIgnoreCase("Parent", obj.metadata.getOrElse("visibility", "").asInstanceOf[String])
	}

}
