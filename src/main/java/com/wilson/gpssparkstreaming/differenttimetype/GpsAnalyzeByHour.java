package com.wilson.gpssparkstreaming.differenttimetype;

import com.wilson.conf.ConfigurationManager;
import com.wilson.constant.Constants;
import com.wilson.dao.GpsMessageInsertDAO;
import com.wilson.dao.GpsMessageScanDAO;
import com.wilson.dao.factory.DAOFactory;
import com.wilson.util.HBaseUtil;
import com.wilson.util.NumberUtils;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.*;
import org.apache.spark.storage.StorageLevel;
import scala.Tuple2;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;


/**
 * function 计算小时级别GPS消息数据，获得所需指标
 * 数据来源 HBASE中十分钟级别的数据 bd_spark_track_gps_tenMinutes
 * 数据同步 将数据同步到HBASE中 bd_spark_track_gps_hour
 * author by liushuming
 * date 2018-03-29 16:35:00
 *
 */

public class GpsAnalyzeByHour {

    public static void main(String[] args){
        dealGpsByHour();
    }

    //dealGpsMessageByHour();
    public static void dealGpsByHour(){

        SparkConf sparkConf = new SparkConf()
                .setAppName("GpsAnalyzeByHour")
                .setMaster("local[3]");
        JavaSparkContext javaSparkContext = new JavaSparkContext(sparkConf);

        //step1 从hbase中读取数据并且转换为RDD,获取标准格式的数据<terminalId_timeId(小时),指标名_指标值>
        JavaPairRDD<String, String> getFormatRDD = getDataFromHbase(ConfigurationManager.getProperty(Constants.HBASE_TABLE_GPS_TENMINUTE), javaSparkContext);

        //getFormatRDD.persist(StorageLevel.MEMORY_AND_DISK_SER());

        //step2 <terminalId_timeId(小时),指标名_指标值>转化成<terminalId_timeId(小时)__指标名,指标值>
        JavaPairRDD<String, Integer> getAllRDD = getFormatRDD.filter((Tuple2<String, String> v1)->!("gpsOnlinePercent".equals(v1._2.split("_")[0]) || "gpsFrequent".equals(v1._2.split("_")[0])))
                .mapToPair((Tuple2<String, String> stringStringTuple2)->new Tuple2<String, Integer>(stringStringTuple2._1 + "__" + stringStringTuple2._2.split("_")[0]
                        , Integer.parseInt(stringStringTuple2._2.split("_")[1])));


        //step3 计算 gpsFrequentRDD,只需过滤出指标名为gpsFrequent的数据即可
        JavaPairRDD<String, String> gpsFrequentRDD = getFormatRDD.filter((Tuple2<String, String> v1)->"gpsFrequent".equals(v1._2.split("_")[0]));


        //step4 计算除了gpsFrequent和gpsOnlinePercent以外的指标,获得<terminalId_timeId(小时)__指标名,指标值>
        //step4.1 <terminalId_timeId(小时)__指标名,sum>
        JavaPairRDD<String, String> getOtherRDD = getAllRDD.reduceByKey((Integer v1, Integer v2)->v1 + v2)
                //step4.2 <terminalId_timeId(小时)__指标名,sum>转化成<terminalId_timeId(小时),指标名_sum>
                .mapToPair((Tuple2<String, Integer> stringIntegerTuple2)->new Tuple2<String, String>(stringIntegerTuple2._1.split("__")[0]
                        , stringIntegerTuple2._1.split("__")[1] + "_" + stringIntegerTuple2._2));


        //step5 获取在线占比指标
        JavaPairRDD<String, String> onlinePercentRDD = getOnlinePercent(getOtherRDD);

        //step6 union all
        JavaPairRDD<String, String> unionrRDD = gpsFrequentRDD.union(onlinePercentRDD).union(getOtherRDD);

        //step7 将指标插入hbase
        //InsertIntoHBase(gpsFrequentRDD.union(onlinePercentRDD).union(getOtherRDD),ConfigurationManager.getProperty(Constants.HBASE_TABLE_GPS_HOUR));

        if(gpsFrequentRDD.union(onlinePercentRDD).union(getOtherRDD).isEmpty()){
            System.out.println("unionrRDD is null");
        }else{
            System.out.println("unionrRDD is not null");
            InsertIntoHBase(gpsFrequentRDD.union(onlinePercentRDD).union(getOtherRDD)
                    ,ConfigurationManager.getProperty(Constants.HBASE_TABLE_GPS_HOUR));

        }

        System.out.println("InsertIntoHBase is done");
        //step5 关闭javaSparkContext连接
        javaSparkContext.close();

    }

    //获取在线占比指标
    private static JavaPairRDD<String, String> getOnlinePercent(JavaPairRDD<String, String> stringStringJavaPairRDD1) {

        //step1 获取gpsTotalNumRDD
        return stringStringJavaPairRDD1.filter((Tuple2<String, String> v1)->"gpsTotalNum".equals(v1._2.split("_")[0]))
                //step2 获取gpsOnlineTotalRDD
                .union(stringStringJavaPairRDD1.filter((Tuple2<String, String> v1)->"gpsOnlineTotal".equals(v1._2.split("_")[0])))
                .reduceByKey(new Function2<String, String, String>() {
                    @Override
                    public String call(String v1, String v2) throws Exception {
                        //step3 <terminalId_timeId(小时),gpsTotalNum_指标值>和<terminalId_timeId(小时),gpsOnlineTotal_指标值>,转化成<terminalId_timeId(小时),gpsOnlinePercent_指标值>
                        if(v2.equals("null")){ return null; }
                        else{
                            if(Integer.parseInt(v1.split("_")[1])>=Integer.parseInt(v2.split("_")[1])){
                                return ConfigurationManager.getProperty(Constants.HBASE_COLUMN_GPSONLINEPERCENT) + "_" + Double.toString(NumberUtils.formatDouble(Double.parseDouble(v2.split("_")[1])/Double.parseDouble(v1.split("_")[1]),2));
                            }else{
                                return ConfigurationManager.getProperty(Constants.HBASE_COLUMN_GPSONLINEPERCENT) + "_" + Double.toString(NumberUtils.formatDouble(Double.parseDouble(v1.split("_")[1])/Double.parseDouble(v2.split("_")[1]),2));
                            }
                        }
                    }
                });

    }

    //从hbase中读取数据并且转换为RDD,获取标准格式的数据<terminalId_timeId(小时),指标名_指标值>
    private static JavaPairRDD<String,String> getDataFromHbase(String property, JavaSparkContext javaSparkContext) {
        GpsMessageScanDAO gpsMessageScanDAO = DAOFactory.gpsMessageScanDAO();
        JavaPairRDD<ImmutableBytesWritable, Result> getHbaseDataRDD
                = gpsMessageScanDAO.scanByFilter(ConfigurationManager.getProperty(Constants.HBASE_TABLE_GPS_TENMINUTE)
                ,javaSparkContext
                //目前仅仅作为测试
                ,"2018"
                //过滤策略，假如当前时间是2018_04_20 16:01,则匹配出"2018042015",即当前时间的上一个小时
                //,DateUtils.NowTimeStamp2TenMinuteLastHour()
        );


        //<一条hbase的数据>转化成<terminalId_timeId(小时),指标名_指标值>

        return getHbaseDataRDD.flatMapToPair(new PairFlatMapFunction<Tuple2<ImmutableBytesWritable,Result>, String, String>() {
            @Override
            public Iterator<Tuple2<String, String>> call(Tuple2<ImmutableBytesWritable, Result> immutableBytesWritableResultTuple2) throws Exception {
                ArrayList<Tuple2<String, String>> list = new ArrayList<Tuple2<String, String>>();
                Cell[] cells = immutableBytesWritableResultTuple2._2.rawCells();

                for (int i = 0; i < cells.length; i++) {
                    //这里要注意，因为是从十分钟到小时，所以20180330141到2018033014,所以要截取到总长度减去1
                    list.add(new Tuple2<String, String>((Bytes.toString(CellUtil.cloneRow(cells[i])).substring(0,(Bytes.toString(CellUtil.cloneRow(cells[i])).length()-1)))
                            , Bytes.toString(CellUtil.cloneQualifier(cells[i])) + "_" + Bytes.toString(CellUtil.cloneValue(cells[i]))));
                }

                return list.iterator();
            }
        });

    }

    //将统计好的指标数据插入到hbase
    private static void InsertIntoHBase(JavaPairRDD<String, String> unionedRDD,final String hbaseTable) {
        unionedRDD.foreachPartition(new VoidFunction<Iterator<Tuple2<String, String>>>() {
            @Override
            public void call(Iterator<Tuple2<String, String>> iterator)
                    throws Exception {
                //GPSMessageOutput2这是作为一个对象来存储<terminalId_timeId，指标名_指标值>
                //优化前:原来是将指标转化为一个对象进行插入操作
                //优化后:直接组合为一个字符串(terminalId_timeId__指标名_指标值)进行插入
                //优化原理是使用字符串比对象节约内存
                List<String> gpsMessageOutputsList = new ArrayList<String>();
                //HBaseUtil.init("zkHost");
                if (iterator.hasNext()) {
                    while (iterator.hasNext()) {
                        Tuple2<String, String> tuple = iterator.next();
                        //数据格式为<terminalId_timeId,指标名_指标值>
                        gpsMessageOutputsList.add(tuple._1 + "__" + tuple._2);

                    }
                    if(0 !=gpsMessageOutputsList.size()){
                        GpsMessageInsertDAO gpsMessageOutputDao = DAOFactory.gpsMessageInsertDAO();
                        gpsMessageOutputDao.insertBatch(hbaseTable, gpsMessageOutputsList);
                    }


                    if (!gpsMessageOutputsList.isEmpty()) {
                        GpsMessageInsertDAO gpsMessageOutputDao = DAOFactory.gpsMessageInsertDAO();
                        gpsMessageOutputDao.insertBatch(hbaseTable, gpsMessageOutputsList);
                    }


                }

            }

        });
    }




}

