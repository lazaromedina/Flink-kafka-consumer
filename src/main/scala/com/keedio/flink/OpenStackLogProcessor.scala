package com.keedio.flink

import java.sql.Timestamp

import com.datastax.driver.core.Cluster
import com.datastax.driver.core.Cluster.Builder
import org.apache.flink.api.java.tuple._
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.streaming.api.scala.{DataStream, _}
import org.apache.flink.streaming.connectors.cassandra.{CassandraSink, ClusterBuilder}
import org.apache.flink.streaming.connectors.kafka._
import org.apache.flink.streaming.util.serialization._
import org.apache.log4j.Logger

import scala.collection.Map

/**
  * Created by luislazaro on 8/2/17.
  * lalazaro@keedio.com
  * Keedio
  */
class OpenStackLogProcessor

object OpenStackLogProcessor {
  val LOG: Logger = Logger.getLogger(classOf[OpenStackLogProcessor])

  def main(args: Array[String]): Unit = {
    val env: StreamExecutionEnvironment = StreamExecutionEnvironment.getExecutionEnvironment
    val parameterTool = ParameterTool.fromArgs(args)
    val stream: DataStream[String] = env
      .addSource(new FlinkKafkaConsumer08[String](
        parameterTool.getRequired("topic"), new SimpleStringSchema(), parameterTool.getProperties))


//    val stream: DataStream[String] = stream0.map(s => {
//      var aux = ""
//      try {
//        aux = s.split("root:")(1).trim
//      } catch {
//        case e: ArrayIndexOutOfBoundsException => LOG.error("Cannot split string by pattern " + s)
//      }
//      aux
//    })
    //val streamOfLogs: DataStream[LogEntry] = stream.map(string => new LogEntry(string, Seq("date", "time", "pid", "loglevel")))

    val listOfKeys: Map[String, Int] = Map("1h" -> 3600, "6h" -> 21600, "12h" -> 43200, "24h" -> 86400, "1w" -> 604800, "1m" -> 2419200)

    val listNodeCounter: Map[DataStream[Tuple5[String, String, String, String, String]], Int] = listOfKeys
      .map(e => (stringToTupleNC(stream, e._1, "az1", "boston", "compute"), e._2))

    val listServiceCounter: Map[DataStream[Tuple5[String, String, String, String, String]], Int] = listOfKeys
      .map(e => (stringToTupleSC(stream, e._1, "az1", "boston"), e._2))

    val listStackService: Map[DataStream[Tuple5[String, String, String, String, Int]], Int] = listOfKeys
      .map(e => (stringToTupleSS(stream, e._1, "boston"), e._2))

    val rawLog: DataStream[Tuple7[String, String, String, String, String, Timestamp, String]] = stringToTupleRL(stream, "boston", "compute")


    //SINKING
    listNodeCounter.foreach { t => CassandraSink.addSink(t._1.javaStream)
      .setQuery("INSERT INTO redhatpoc.counters_nodes (id, loglevel, az, region, node_type, ts) VALUES (?, ?, ?, ?, ?, now()) USING TTL " + t._2 + ";")
      .setClusterBuilder(new ClusterBuilder() {
        override def buildCluster(builder: Builder): Cluster = {
          builder.addContactPoint(parameterTool.getRequired("cassandra.host")).build()
        }
      })
      .build()
    }

    listServiceCounter.foreach { t => CassandraSink.addSink(t._1.javaStream)
      .setQuery("INSERT INTO redhatpoc.counters_services (id, loglevel, az, region, service, ts) VALUES (?, ?, ?, ?, ?, now()) USING TTL " + t._2 + ";")
      .setClusterBuilder(new ClusterBuilder() {
        override def buildCluster(builder: Builder): Cluster = {
          builder.addContactPoint(parameterTool.getRequired("cassandra.host")).build()
        }
      })
      .build()
    }

    listStackService.foreach { t => CassandraSink.addSink(t._1.javaStream)
      .setQuery("INSERT INTO redhatpoc.stack_services (id, region, loglevel, service, ts, timeframe) VALUES (?, ?, ?, ?, now(), ?) USING TTL " + t._2 + ";")
      .setClusterBuilder(new ClusterBuilder() {
        override def buildCluster(builder: Builder): Cluster = {
          builder.addContactPoint(parameterTool.getRequired("cassandra.host")).build()
        }
      })
      .build()
    }

    CassandraSink.addSink(rawLog.javaStream)
      .setQuery("INSERT INTO redhatpoc.raw_logs (date, region, loglevel, service, node_type, log_ts, payload) VALUES (?, ?, ?, ?, ?, ?, ?);")
      .setClusterBuilder(new ClusterBuilder() {
        override def buildCluster(builder: Builder): Cluster = {
          builder.addContactPoint(parameterTool.getRequired("cassandra.host")).build()
        }
      })
      .build()


    env.execute()

  }


  /**
    * Extract value "log-level" form a common syslog line.
    * Value for log level info is expected to be the fourth word in a standarized syslog line.
    * If standard is not met, use argument 'exp' for marking off an expression.
    * Example:
    * - common syslog: "2017-02-10 06:18:07.264 3397 INFO eventlet.wsgi.server ...some other stuff"
    * - irregular syslog: "whatever myMachineName: 2017-02-10 06:18:07.264 3397 INFO eventlet.wsgi.server ...some other stuff"
    * exp = "myMachine:"
    *
    * @param s
    * @param exp
    * @return
    */
  def getFieldFromString(s: String, exp: String = "", position: Int): String = {
    var requiredValue: String = ""
    try {
      requiredValue = s.trim.split("\\s+")(position)
    } catch {
      case e: ArrayIndexOutOfBoundsException => LOG.error("Cannot parse string: does line contains loglevel info or timestamp? " + s)
    }
    requiredValue
  }

  /**
    * Function for transforming DataStream[String] to DataStream[Tuple5 o Tuple6]
    *
    * @param stream
    * @param timeKey
    * @param az
    * @param region
    * @param node
    * @return
    */
  def stringToTupleNC(stream: DataStream[String], timeKey: String, az: String, region: String, node: String): DataStream[Tuple5[String, String, String, String, String]] = {
    stream
      .map(string => {
        val logLevel: String = getFieldFromString(string, "", 3)
        new Tuple5(timeKey, logLevel, az, region, node)
      })
      .filter(t => t.f1 match {
        case "INFO" => true
        case "ERROR" => true
        case "WARNING" => true
        case _ => false
      })
  }

  def stringToTupleSC(stream: DataStream[String], timeKey: String, az: String, region: String): DataStream[Tuple5[String, String, String, String, String]] = {
    stream
      .map(string => {
        val logLevel: String = getFieldFromString(string, "", 3)
        val service: String = getFieldFromString(string, "", 4) match {
          case "" => "keystone"
          case _ => getFieldFromString(string, "", 4)
        }
        new Tuple5(timeKey, logLevel, az, region, service)
      })
      .filter(t => t.f1 match {
        case "INFO" => true
        case "ERROR" => true
        case "WARNING" => true
        case _ => false
      })
  }

  def stringToTupleSS(stream: DataStream[String], timeKey: String, region: String): DataStream[Tuple5[String, String, String, String, Int]] = {
    stream
      .map(string => {
        val logLevel: String = getFieldFromString(string, "", 3)
        val pieceTime: String = getFieldFromString(string, "", 1)
        val timeframe: Int = getMinutesFromTimePieceLogLine(pieceTime)
        val service: String = getFieldFromString(string, "", 4) match {
          case "" => "keystone"
          case _ => getFieldFromString(string, "root:", 4)
        }
        new Tuple5(timeKey, region, logLevel, service, timeframe)
      })
      .filter(t => t.f2 match {
        case "INFO" => true
        case "ERROR" => true
        case "WARNING" => true
        case _ => false
      })
  }

  def stringToTupleRL(stream: DataStream[String], region: String, node_type: String): DataStream[Tuple7[String, String, String, String, String, Timestamp, String]] = {
    stream
      .map(string => {
        val logLevel: String = getFieldFromString(string, "", 3)
        val pieceDate: String = getFieldFromString(string, "", 0)
        val service: String = getFieldFromString(string, "", 4) match {
          case "" => "keystone"
          case _ => getFieldFromString(string, "", 4)
        }
        val stringtimestamp: String = new String(getFieldFromString(string, "", 0) + " " + getFieldFromString(string, "", 1))
        var log_ts = new Timestamp(0L)
        try {
          log_ts = Timestamp.valueOf(stringtimestamp)
        } catch {
          case e: IllegalArgumentException => LOG.info("cannot create timestamp from string " + stringtimestamp)
        }
        val payload = string
        new Tuple7(pieceDate, region, logLevel, service, node_type, log_ts, payload)
      })
      .filter(t => t.f2 match {
        case "INFO" => true
        case "ERROR" => true
        case "WARNING" => true
        case _ => false
      })
  }


  /**
    * Get minutes from time token in syslog
    * 09:40 == 09*60 + 40
    *
    * @param pieceTime
    * @return
    */
  def getMinutesFromTimePieceLogLine(pieceTime: String): Int = {
    var pieceHour = 0
    var pieceMinute = 0
    try {
      pieceHour = pieceTime.split(":")(0).toInt * 60
      pieceMinute = pieceTime.split(":")(1).toInt
    } catch {
      case e: NumberFormatException => LOG.error("!>>>>>>>>>>>>>>>>>>>>>>>>>>>> string cannot be cast to Integer: " + pieceTime)
      case e: ArrayIndexOutOfBoundsException => LOG.error("!>>>>>>>>>>>>>>>>>>>>>>>>>>>> malformed piece of time : " + pieceTime)
    }
    pieceHour + pieceMinute
  }

}
