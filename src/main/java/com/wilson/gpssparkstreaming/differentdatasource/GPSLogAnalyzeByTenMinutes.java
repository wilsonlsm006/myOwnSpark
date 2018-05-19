package com.wilson.gpssparkstreaming.differentdatasource;import com.clearspring.analytics.util.Lists;import com.wilson.conf.ConfigurationManager;import com.wilson.constant.Constants;import com.wilson.dao.GpsMessageInsertDAO;import com.wilson.dao.factory.DAOFactory;import com.wilson.util.CalculateUtils;import com.wilson.util.DateUtils;import com.wilson.util.KafkaProducer;import com.wilson.util.SparkUtils;import kafka.producer.KeyedMessage;import org.apache.spark.api.java.JavaPairRDD;import org.apache.spark.api.java.JavaRDD;import org.apache.spark.api.java.function.*;import org.apache.spark.storage.StorageLevel;import org.apache.spark.streaming.api.java.*;import scala.Tuple2;import java.util.*;import static com.wilson.util.DateUtils.TimeStamp2TenMinuteMillisecond;/** * spark streaming处理跨duration的数据 * 数据处理策略：处理数据分为两个部分，第一个部分是在一个duration中连接和关闭配对的，这一块容易计算; *             第二个部分是一组连接和关闭是跨duration的。这一块比较难处理，具体处理策略如下： *             将没有配对的数据重新发送到kafka,下一个duration再处理，直到配对成功。 *             注意：为了方便计算，引入一个标志位，来判断是否是当前duration的数据。 * 数据接入:kafka,使用测试数据 * 数据加工：spark streaming流处理 * 数据同步:hbase,写入"bd_spark_gpslog_tenMinutes"表 * author by wilson * date:2018-02-27 17:00:00 * */public class GPSLogAnalyzeByTenMinutes {	//处理GPS平台日志数据	public static void dealGPSLogTenMinutes(JavaDStream<String> kafkaGPSRMessageInputDstream) {		//step1 数据预处理，将kafka源接入的数据转化为可以处理的标准模式<serialNum_terminalId,timeId_onlineStatus>格式		JavaPairDStream<String, String> formatFromKafkaDStream = getFormatFromKafkaDStream(kafkaGPSRMessageInputDstream);		//持久化机制		formatFromKafkaDStream.persist(StorageLevel.MEMORY_AND_DISK_SER());		//step2 统计十分钟级别平台日志指标,返回<terminalId_timeId(十分钟格式),targetName(指标名称)_targetValue(指标值)>		JavaPairDStream<String, String> connectNumDStream = getConnectNum(formatFromKafkaDStream);		JavaPairDStream<String, String> closeNumDStream = getCloseNum(formatFromKafkaDStream);		JavaPairDStream<String, String> durationDStream = getGPSLogDuration(formatFromKafkaDStream);		JavaPairDStream<String, String> unionedDStrea = connectNumDStream.union(closeNumDStream).union(durationDStream);		//step3 将统计的指标插入HBaseb表中		insertIntoHBase(unionedDStrea,ConfigurationManager.getProperty(Constants.HBASE_TABLE_GPS_TENMINUTE));		//step4 将没有配对的数据再次发送到kafka		sentNotPairDataToKafka(formatFromKafkaDStream);	}	/**	 * function:将没有配对的kafka数据从新发送到kafka，直到配对成功	 * 输入数据:<serialNum_terminalId,timeId_onlineStatus>格式,	 *          实时产生的gpsLog数据格式为serialNum:1519659181610963,terminalId:1503420,time:1519660981609,online:0,serverIp:192.168.171.231	 * author:wilsonlsm006@163.com	 * date:2018-03-08 17:42:00	 * param formatFromKafkaDStream	 */	private static void sentNotPairDataToKafka(JavaPairDStream<String, String> formatFromKafkaDStream) {		formatFromKafkaDStream.foreachRDD(new VoidFunction<JavaPairRDD<String, String>>() {			@Override			public void call(JavaPairRDD<String, String> stringStringJavaPairRDD) throws Exception {				//step1 <serialNum_terminalId,timeId_onlineStatus>转化成<serialNum_terminalId,timeId_onlineStatus__timeId_onlineStatus>				stringStringJavaPairRDD.reduceByKey((String v1,String v2)->v1 + "__" + v2)						//step2 过滤，返回不配对的数据						.filter( (Tuple2<String, String> v1) ->v1._2.split("__").length == 1)						//step3 将<serialNum_terminalId,timeId_onlineStatus__null>转化成最初的字符串。						//serverIp字段对于统计没有用处，所以统一设置为111.111.111.111						.map(v1-> "serialNum:"+v1._1.split("_")[0]+",terminalId:"+v1._1.split("_")[1]								+",time:"+v1._2.split("_")[0]+",online:"+v1._2.split("_")[1]+",serverIp:111.111.111.111")						.foreachPartition(new VoidFunction<Iterator<String>>() {							@Override							public void call(Iterator<String> tuple2Iterator) throws Exception {								// 得到单例的 kafka producer(是在每个executor上单例，job中producer数目与executor数目相同，并行输出，性能较好)								KafkaProducer kafkaProducer =										KafkaProducer.getInstance(ConfigurationManager.getProperty(Constants.KAFKA_METADATA_BROKER_LIST));								// 批量发送 推荐								List<KeyedMessage<String, String>> messageList = Lists.newArrayList();								while (tuple2Iterator.hasNext()) {									messageList.add(new KeyedMessage<String, String>(ConfigurationManager.getProperty(Constants.KAFKA_TOPICS_OFFLINE), tuple2Iterator.next()));								}								//一次性发送messageList到kafka的topic中								kafkaProducer.send(messageList);								//kafkaProducer.shutdown();							}						});			}		});	}	/**	 * function 将统计的指标插入HBase中	 * 输入数据:<terminalId_timeId,targetName_targetValue>，比如<1_20180308164,指标名称_指标值>,指标主要有三种,连接次数、关闭次数和连接时长	 * author wilsonlsm006@163.com	 * date 2018-03-06 15:30:00	 * param formatFromKafkaDStream	 */	private static void insertIntoHBase(JavaPairDStream<String, String> unionedDStream, final String hbaseTableName) {		unionedDStream.foreachRDD(new VoidFunction<JavaPairRDD<String, String>>() {			@Override			public void call(JavaPairRDD<String, String> stringStringJavaPairRDD) throws Exception {				stringStringJavaPairRDD.foreachPartition(new VoidFunction<Iterator<Tuple2<String, String>>>() {					@Override					public void call(Iterator<Tuple2<String, String>> iterator) throws Exception {						List<String> gpsMessageOutputsList = new ArrayList<String>();						if(iterator.hasNext()){							while(iterator.hasNext()) {								Tuple2<String, String> tuple = iterator.next();								//数据格式为<terminalId_timeId,指标名_指标值>								gpsMessageOutputsList.add(tuple._1+"__"+tuple._2);							}							if(!gpsMessageOutputsList.isEmpty()){								GpsMessageInsertDAO gpsMessageOutputDao = DAOFactory.gpsMessageInsertDAO();								gpsMessageOutputDao.insertBatch(hbaseTableName,gpsMessageOutputsList);							}						}					}				});			}		});	}	/**	 * function 统计十分钟内连接时长	 * 输入数据:<serialNum_terminalId,timeId_onlineStatus>格式	 * author wilsonlsm006@163.com	 * date 2018-03-06 15:53:00	 * param formatFromKafkaDStream	 * return <terminalId_timeId,"connectDuration"_sum>	 */	private static JavaPairDStream<String, String> getGPSLogDuration(JavaPairDStream<String, String> formatFromKafkaDStream) {		/**		 * 这部分处理是最麻烦的，分成很多种情况进行处理，实质是将数据进行分类处理的过程，难点在于数据分类很细，但是又要考虑到所有情况		 *      ***********************************************************************************************************		 *      01  跨时间段配对(已经完成)		 *          过滤条件:根据serialNum_terminalId配对,但是出于不同的十分钟内		 *      ***********************************************************************************************************		 *      02  十分钟内配对的数据(已经完成)		 *          过滤条件:根据根据serialNum_terminalId配对,计算差值。		 *      ***********************************************************************************************************		 *      06 没有配对的数据  只要是没有配对的数据，会传入连接的时间和当前十分钟的时间，进行计算(已经完成)		 *      ***********************************************************************************************************		 *		 */		//02  跨时间段配对(已经完成)		JavaPairDStream<String, String> pairForMoreTenminuteDStream = getDurationPairForMoreTenminuteDStream(formatFromKafkaDStream);		//05  十分钟内配对的数据(已经完成)		JavaPairDStream<String, String> pairForTenminuteDStream = getDurationPairForTenminuteDStream(formatFromKafkaDStream);		//06  没有配对的数据(已经完成)		JavaPairDStream<String, String> notPairDStream = getDurationNotPairDStream(formatFromKafkaDStream);		//return <terminalId_timeId,"connectDuration"_sum>		return  pairForMoreTenminuteDStream.union(pairForTenminuteDStream).union(notPairDStream).reduceByKey				((String v1, String v2)->v1.split("_")[0]+"_"+(CalculateUtils.getAddSeconds(v1.split("_")[1],v2.split("_")[1])));	}	/**	 * function 统计不配对的数据	 * 输入数据:<serialNum_terminalId,timeId_onlineStatus_storeStatus>格式,	 *         实时产生的测试数据格式 <serialNum_terminalId,timeId__onlineStatus_0>	 *         离线的数据格式 <serialNum_terminalId,timeId_1_1>	 *         其中 timeId为1519660981609	 * author wilsonlsm006@163.com	 * date 2018-03-28 14:49:00	 * param formatFromKafkaDStream	 * return <terminalId_timeId,"connectDuration"_sum>	 */	private static JavaPairDStream<String, String> getDurationNotPairDStream(JavaPairDStream<String, String> formatFromKafkaDStream) {		Function<JavaPairRDD<String, String>, JavaPairRDD<String, String>> delDurationFunc				= new Function<JavaPairRDD<String, String>, JavaPairRDD<String, String>>() {			@Override			public JavaPairRDD<String, String> call(JavaPairRDD<String, String> formatFromKafkaRDD) throws Exception {				//step1 <serialNum_terminalId,timeId_onlineStatus_storeStatus>转化成<serialNum_terminalId,timeId__timeId>				return formatFromKafkaRDD.reduceByKey((String v1, String v2)->v1+"__"+v2)						//step2 过滤，返回不配对的数据						.filter((Tuple2<String, String> v1)->v1._2.split("__").length==1)						.flatMapToPair(new PairFlatMapFunction<Tuple2<String,String>, String, String>() {							//step3 <serialNum_terminalId,timeId>转化成<serialNum_terminalId,timeId_当前十分钟的最大值>							// 转化成<terminalId_timeId(十分钟格式),connectDuration_sum()>							@Override							public Iterator<Tuple2<String, String>> call(Tuple2<String, String> stringStringTuple2) throws Exception {								ArrayList<Tuple2<String,String>> list=new ArrayList<Tuple2<String,String>>();								List getPairList=SparkUtils.getPairDurationList2(stringStringTuple2._2.split("_")[0]+"__"										+(DateUtils.getNowTenminuteEnd()).toString());								//因为获取到当前时间的十分钟段，会到下一个十分钟段，相当于多加了一个，所以这里要少区一个								for(int i=0;i<getPairList.size()-1;i++){									if(i==0){										list.add(new Tuple2<String, String>(												stringStringTuple2._1.split("_")[1]+"_"+getPairList.get(i).toString()												,ConfigurationManager.getProperty(Constants.HBASE_COLUMN_GPSLOGCONNECTIONDURATION)+"_"												+DateUtils.getSeconds(stringStringTuple2._2.split("_")[0]												,(DateUtils.getTenminuteEndtMillisecond(stringStringTuple2._2.split("_")[0])).toString())));									}else{										list.add(new Tuple2<String, String>(												stringStringTuple2._1.split("_")[1]+"_"+getPairList.get(i).toString()												,ConfigurationManager.getProperty(Constants.HBASE_COLUMN_GPSLOGCONNECTIONDURATION)+"_"												+600));									}								}								return list.iterator();							}						});			}		};		return formatFromKafkaDStream.transformToPair(delDurationFunc);	}	/**	 * function 十分钟内配对的数据(正在开发)	 *          过滤条件:根据根据serialNum_terminalId配对,计算差值。	 * 输入数据:<serialNum_terminalId,timeId_onlineStatus_storeStatus>格式,	 *         其中 timeId为1519660981609	 * author wilsonlsm006@163.com	 * date 2018-03-20 14:12:00	 * param formatFromKafkaDStream	 * return <terminalId_timeId(十分钟格式),"connectDuration"_sum>	 */	private static JavaPairDStream<String,String> getDurationPairForTenminuteDStream(JavaPairDStream<String, String> formatFromKafkaDStream) {		Function<JavaPairRDD<String, String>, JavaPairRDD<String, String>> delDurationFunc				= new Function<JavaPairRDD<String, String>, JavaPairRDD<String, String>>() {			@Override			public JavaPairRDD<String, String> call(JavaPairRDD<String, String> formatFromKafkaRDD) throws Exception {				//step1 <serialNum_terminalId,timeId_onlineStatus_storeStatus>转化成<serialNum_terminalId,timeId___timeId>				return formatFromKafkaRDD.reduceByKey((String v1, String v2) -> v1.split("_")[0] + "__" + v2.split("_")[0])						//step2 过滤，返回配对的数据						.filter((Tuple2<String, String> v1) -> v1._2.split("__").length == 2 && DateUtils.TimeStamp2TenMinuteMillisecond(v1._2.split("__")[0])								.equals(DateUtils.TimeStamp2TenMinuteMillisecond(v1._2.split("__")[1])))						//step3 <serialNum_terminalId,timeId___timeId>转化成<terminalId_timeId(十分钟级别格式),connectDuration_sum>						.mapToPair((Tuple2<String, String> stringStringTuple2) -> new Tuple2<String, String>(stringStringTuple2._1.split("_")[1] + "_"								+ DateUtils.TimeStamp2TenMinuteMillisecond(stringStringTuple2._2.split("__")[0])								, ConfigurationManager.getProperty(Constants.HBASE_COLUMN_GPSLOGCONNECTIONDURATION) + "_"								+ DateUtils.getSeconds(stringStringTuple2._2.split("__")[1], stringStringTuple2._2.split("__")[0])));			}		};		return formatFromKafkaDStream.transformToPair(delDurationFunc);	}	/**	 * function 统计十分钟内跨时间段配对	 *          过滤条件:根据serialNum_terminalId配对，但是属于不同十分钟内的	 * 输入数据:<serialNum_terminalId,timeId_onlineStatus_storeStatus>格式,	 *         实时产生的测试数据格式 <serialNum_terminalId,timeId__onlineStatus_0>	 *         离线的数据格式 <serialNum_terminalId,timeId_1_1>	 *         其中 timeId为1519660981609	 * author wilsonlsm006@163.com	 * date 2018-03-06 15:53:00	 * param formatFromKafkaDStream	 * return <terminalId_timeId,"connectDuration"_sum>	 */	private static JavaPairDStream<String, String> getDurationPairForMoreTenminuteDStream(JavaPairDStream<String, String> formatFromKafkaDStream) {		Function<JavaPairRDD<String, String>, JavaPairRDD<String, String>> delDurationFunc				= new Function<JavaPairRDD<String, String>, JavaPairRDD<String, String>>() {			@Override			public JavaPairRDD<String, String> call(JavaPairRDD<String, String> formatFromKafkaRDD) throws Exception {				//step1 <serialNum_terminalId,timeId_onlineStatus_storeStatus>转化成<serialNum_terminalId,timeId__timeId>				return formatFromKafkaRDD.reduceByKey((String v1, String v2)->v1.split("_")[0]+"__"+v2.split("_")[0])						//step2 过滤，返回在不同十分钟段内配对的数据						.filter((Tuple2<String, String> v1)->								(v1._2.split("__").length==2)&&										!(DateUtils.TimeStamp2TenMinuteMillisecond(v1._2.split("__")[0])).equals(DateUtils.TimeStamp2TenMinuteMillisecond(v1._2.split("__")[1])))						.flatMapToPair(new PairFlatMapFunction<Tuple2<String,String>, String, String>() {							//step3 <serialNum_terminalId,timeId__timeId>							// 转化成<terminalId_timeId(十分钟格式),connectDuration_sum()>							@Override							public Iterator<Tuple2<String, String>> call(Tuple2<String, String> stringStringTuple2) throws Exception {								ArrayList<Tuple2<String,String>> list=new ArrayList<Tuple2<String,String>>();								List getPairList=SparkUtils.getPairDurationList2(stringStringTuple2._2);								for(int i=0;i<getPairList.size();i++){									if(i==0){										list.add(new Tuple2<String, String>(												stringStringTuple2._1.split("_")[1]+"_"+getPairList.get(i).toString()												,ConfigurationManager.getProperty(Constants.HBASE_COLUMN_GPSLOGCONNECTIONDURATION)+"_"												+DateUtils.getSeconds(CalculateUtils.getSmallerNum(stringStringTuple2._2.split("__")[0],stringStringTuple2._2.split("__")[1])												,DateUtils.getTenminuteEndtMillisecond(CalculateUtils.getSmallerNum(stringStringTuple2._2.split("__")[0],stringStringTuple2._2.split("__")[1])).toString())));									}else if(i==getPairList.size()-1){										list.add(new Tuple2<String, String>(												stringStringTuple2._1.split("_")[1]+"_"+getPairList.get(i).toString()												,ConfigurationManager.getProperty(Constants.HBASE_COLUMN_GPSLOGCONNECTIONDURATION)+"_"												+DateUtils.getSeconds(CalculateUtils.getBiggerNum(stringStringTuple2._2.split("__")[0],stringStringTuple2._2.split("__")[1])												,DateUtils.getTenminuteStartMillisecond(CalculateUtils.getBiggerNum(stringStringTuple2._2.split("__")[0],stringStringTuple2._2.split("__")[1])).toString())));									}else{										list.add(new Tuple2<String, String>(												stringStringTuple2._1.split("_")[1]+"_"+getPairList.get(i).toString()												,ConfigurationManager.getProperty(Constants.HBASE_COLUMN_GPSLOGCONNECTIONDURATION)+"_"												+600));									}								}								return list.iterator();							}						});			}		};		return formatFromKafkaDStream.transformToPair(delDurationFunc);	}	/**	 * function 统计十分钟内断开的次数	 * 输入数据:<serialNum_terminalId,timeId_onlineStatus_storeStatus>格式,	 *         实时产生的测试数据格式 <serialNum_terminalId,timeId__onlineStatus_0>	 *         离线的数据格式 <serialNum_terminalId,timeId__1_1>	 * author wilsonlsm006@163.com	 * date 2018-03-06 15:17:00	 * param formatFromKafkaDStream	 * return <terminalId_timeId,"closeNum"_sum>	 */	private static JavaPairDStream<String,String> getCloseNum(JavaPairDStream<String, String> formatFromKafkaDStream) {		Function<JavaPairRDD<String, String>, JavaPairRDD<String, String>> dealCloseNumFunc				= new Function<JavaPairRDD<String, String>, JavaPairRDD<String, String>>() {			@Override			public JavaPairRDD<String, String> call(JavaPairRDD<String, String> formatFromKafkaDStream) throws Exception {				//step1 过滤，留下<serialNum_terminalId,timeId_onlineStatus(0)_storeStatus>的数据				return formatFromKafkaDStream.filter((Tuple2<String, String> v1) -> v1._2.split("_")[1].equals("0"))						//step2 <serialNum_terminalId,timeId_onlineStatus_storeStatus>转化成<terminalId_timeId(20180326141)>						.mapToPair((Tuple2<String, String> stringStringTuple2) -> new Tuple2<String, Integer>(								stringStringTuple2._1.split("_")[1] + "_" + TimeStamp2TenMinuteMillisecond(stringStringTuple2._2.split("_")[0]), 1))						//step3 <terminalId_timeId(20180326141),1>经过reduceByKey转化为<terminalId_timeId,sum>						.reduceByKey((Integer v1, Integer v2) -> v1 + v2)						//step4 <terminalId_timeId,sum>转化成返回插入hbase的标准数据<terminalId_timeId,"connectNum"_sum>						.mapToPair((Tuple2<String, Integer> stringIntegerTuple2) -> new Tuple2<String, String>(stringIntegerTuple2._1								, ConfigurationManager.getProperty(Constants.HBASE_COLUMN_GPSLOGCLOSENUM) + "_" + stringIntegerTuple2._2.toString()));			}		};		return formatFromKafkaDStream.transformToPair(dealCloseNumFunc);	}	/**	 * function 统计十分钟内连接的次数	 * 输入数据:<serialNum_terminalId,timeId_onlineStatus_storeStatus>格式	 * author wilsonlsm006@163.com	 * date 2018-03-06 11:10:00	 * param formatFromKafkaDStream	 * return <terminalId_timeId,"connectNum"_sum>	 */	private static JavaPairDStream<String, String> getConnectNum(JavaPairDStream<String, String> formatFromKafkaDStream) {		Function<JavaPairRDD<String, String>, JavaPairRDD<String, String>> dealConnectNumFunc				= new Function<JavaPairRDD<String, String>, JavaPairRDD<String, String>>(){			@Override			public JavaPairRDD<String, String> call(JavaPairRDD<String, String> formatRDD) throws Exception {				//step1 过滤，留下<serialNum_terminalId,timeId_onlineStatus(1)>连接的标志				return formatRDD.filter((Tuple2<String, String> v1)->v1._2.split("_")[1].equals("1"))						//step2 <serialNum_terminalId,timeId_onlineStatus>转化成<terminalId_timeId(20180326141),1>						.mapToPair((Tuple2<String, String> stringStringTuple2)->new Tuple2<String, Integer>(								stringStringTuple2._1.split("_")[1] + "_" + TimeStamp2TenMinuteMillisecond(stringStringTuple2._2.split("_")[0]), 1))						//step3 <terminalId_timeId(20180326141),1>经过reduceByKey转化为<terminalId_timeId,sum>						.reduceByKey((Integer v1, Integer v2)->v1+v2)						//step4 <terminalId_timeId,sum>转化成返回插入hbase的标准数据<terminalId_timeId,"connectNum"_sum>						.mapToPair((Tuple2<String, Integer> stringIntegerTuple2)->new Tuple2<String, String>(stringIntegerTuple2._1								, ConfigurationManager.getProperty(Constants.HBASE_COLUMN_GPSLOGCONNECTNUM)+"_" + stringIntegerTuple2._2.toString()));			}		};		return formatFromKafkaDStream.transformToPair(dealConnectNumFunc);	}	/**	 *  function:针对不同的应用，将kafka数据源进行预处理工作，转化为针对不同应用可以处理的标准数据源，方便后续处理.(正式环境下这里需要稍微修改下)	 *         针对目前的测试环境下:	 *         kafka数据源包含两种类型数据，一种是实时产生的数据，另外一种是离线数据，即前面duration中没有配对的数据。	 *         输入的数据格式:serialNum_terminalId__timeId_onlineStatus	 *         输出的数据格式：<serialNum_terminalId,timeId_onlineStatus>格式	 *         ps:这里的timeId格式为毫秒级别的时间戳,比如1519660981609,转化为20180227000301,2018年2月27号，凌晨0点3分1秒	 * author :wilsonlsm006@163.com	 * date :2018-03-09 11:36:00	 * param kafkaGPSRMessageInputDstream	 * return 标准输出的数据格式	 */	private static JavaPairDStream<String,String> getFormatFromKafkaDStream(JavaDStream<String> kafkaGPSRMessageInputDstream) {		Function<JavaRDD<String>, JavaPairRDD<String, String>> dealFormatFunc				= new Function<JavaRDD<String>, JavaPairRDD<String, String>>() {			@Override			public JavaPairRDD<String, String> call(JavaRDD<String> kafkaInputRDD) throws Exception {				//生产环境下的数据格式 serialNum:1519659181610963,terminalId:1503420,time:1519660981609,online:0,serverIp:192.168.171.231				//轨迹异常分析系统中预处理数据的格式<serialNum_terminalId,timeId_onlineStatus>				//step1 过滤，将脏数据过滤				return kafkaInputRDD.filter(inputStr->inputStr.split(",").length==5)						.mapToPair(inputStr->new Tuple2<String,String>								(inputStr.split(",")[0].split(":")[1]+ "_"+inputStr.split(",")[1].split(":")[1]										, inputStr.split(",")[2].split(":")[1]+ "_"+inputStr.split(",")[3].split(":")[1]));			}		};		return kafkaGPSRMessageInputDstream.transformToPair(dealFormatFunc);	}}