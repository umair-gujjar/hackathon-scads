package eu.streamline.hackathon.flink.scala.job

import java.io.File
import java.util.Date

import eu.streamline.hackathon.common.data.GDELTEvent
import eu.streamline.hackathon.flink.operations.GDELTInputFormat
import org.apache.commons.io.FileUtils
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.core.fs.Path
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.functions.timestamps.BoundedOutOfOrdernessTimestampExtractor
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows
import org.apache.flink.streaming.api.windowing.time.Time

import scala.collection.JavaConverters._

/**
  * Things which are excluded from the analysis:
  * Religion2Code is ignored
  * Subclusters of Religion1Code is ignored
  * Event location is ignored
  */
object FlinkScalaJob {

  def main(args: Array[String]): Unit = {

    val ashery = true
    val exportHeader = true
    val graphVisualization = false

    val parameters = ParameterTool.fromArgs(args)
    val pathToGDELT = parameters.get("path")
    val religion = parameters.get("religion", "CHR")
    System.out.println("Path: " + pathToGDELT)

    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)

    implicit val typeInfo: TypeInformation[GDELTEvent] = createTypeInformation[GDELTEvent]
    implicit val dateInfo: TypeInformation[Date] = createTypeInformation[Date]

    val source = env
      .readFile[GDELTEvent](new GDELTInputFormat(new Path(pathToGDELT)), pathToGDELT)
      .setParallelism(1)

    val filteredStream: DataStream[GDELTEvent] = source
      .filter((event: GDELTEvent) => {
        event.goldstein != null &&
          event.avgTone != null &&
          event.quadClass != null
      }) //Prevent Nullpointer exceptions
      .assignTimestampsAndWatermarks(new BoundedOutOfOrdernessTimestampExtractor[GDELTEvent](Time.seconds(0)) {
      override def extractTimestamp(element: GDELTEvent): Long = {
        element.dateAdded.getTime
      }
    })

    if (graphVisualization) {
      filteredStream
        .filter(el => el.actor1Code_religion1Code != null & el.actor2Code_religion1Code != null)
        .keyBy(_.actor1Code_religion1Code.substring(0, 3))
        .addSink(new KMeansSink(5))
    }

    //We parallelize for recepient & actor of the event here.
    val keyed1Stream = filteredStream
      .filter(event => event.actor1Geo_countryCode != null
        && event.actor1Code_religion1Code != null) //Prevent NPE
      .map(event => GDELTEventWrapper(event, event.actor1Geo_countryCode, event.actor1Code_religion1Code.substring(0, 3), actorNumber = 1))
      .keyBy(wrapper => wrapper.country + wrapper.religionPrefix) //Introduce Partitioning

    val keyed2Stream = filteredStream
      .filter(event => event.actor2Geo_countryCode != null
        && event.actor2Code_religion1Code != null)
      .map(event => GDELTEventWrapper(event, event.actor2Geo_countryCode, event.actor2Code_religion1Code.substring(0, 3), actorNumber = 2))
      .keyBy(wrapper => wrapper.country + wrapper.religionPrefix)

    //Aggregation global
    val aggregatedGlobal1Stream= keyed1Stream
      .window(TumblingEventTimeWindows.of(Time.days(200)))
      .aggregate(new ProjectNameAggregation())

    val aggregatedGlobal2Stream : DataStream[WindowResult] = keyed2Stream
      .window(TumblingEventTimeWindows.of(Time.days(1)))
      .apply((key, win, it, coll) => new MyWindowFunction().apply(key, win, it.asJava, coll))

    //Windowed aggregation
    /*val aggregatedWindow1Stream= keyed1Stream
      .window(TumblingEventTimeWindows.of(Time.days(10)))
      .apply(new MyWindowFunction())
      .aggregate(new ProjectNameAggregation())*/

    //CSV Sink
    val file = new File("storage/export_global.csv")
    file.delete()
    if (exportHeader) {
      FileUtils.writeStringToFile(file, "country,religionPrefix,actorNumber,count,avgGoldstein,avgAvgTone,sumQuadClass1,sumQuadClass2,sumQuadClass3,sumQuadClass4", true)
    }
    //aggregatedGlobal1Stream.addSink(res => FileUtils.writeStringToFile(file, res.productIterator.mkString(",") + "\n", true))

    aggregatedGlobal2Stream.addSink(res => FileUtils.writeStringToFile(file, res.productIterator.mkString(",") + "\n", true))

    /*
    K-Means export, for ashery
     */
    if (ashery) {
      val f1 = new File("storage/kmeans_aggregated_1.csv")
      f1.delete()
      val header = "country,religion,goldstein,avgTone"
      FileUtils.writeStringToFile(f1, s"$header\n", true)
      aggregatedGlobal1Stream.addSink(res => FileUtils.writeStringToFile(f1, s"${res.country},${res.religionPrefix},${res.avgGoldstein},${res.avgAvgTone}\n", true))
    }

    env.execute("Flink Scala GDELT Analyzer")

  }

}

case class GDELTEventWrapper(gDELTEvent: GDELTEvent, country: String, religionPrefix: String, actorNumber: Int)