/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zeppelin.spark;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import javaslang.Tuple;
import javaslang.Tuple3;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.spark.SparkContext;
import org.apache.spark.sql.AnalysisException;
import org.apache.spark.sql.SparkSession;
import org.apache.zeppelin.conf.ZeppelinConfiguration;
import org.apache.zeppelin.interpreter.AbstractInterpreter;
import org.apache.zeppelin.interpreter.ZeppelinContext;
import org.apache.zeppelin.interpreter.InterpreterContext;
import org.apache.zeppelin.interpreter.InterpreterException;
import org.apache.zeppelin.interpreter.InterpreterResult;
import org.apache.zeppelin.interpreter.InterpreterResult.Code;
import org.apache.zeppelin.interpreter.thrift.InterpreterCompletion;
import org.apache.zeppelin.interpreter.util.SqlSplitter;
import org.apache.zeppelin.scheduler.Scheduler;
import org.apache.zeppelin.scheduler.SchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zeppelin.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spark SQL interpreter for Zeppelin.
 */
public class SparkSqlInterpreter extends AbstractInterpreter {
  private static final Logger LOGGER = LoggerFactory.getLogger(SparkSqlInterpreter.class);

  private SparkInterpreter sparkInterpreter;
  private SqlSplitter sqlSplitter;

  public SparkSqlInterpreter(Properties property) {
    super(property);
  }

  @Override
  public void open() throws InterpreterException {
    this.sparkInterpreter = getInterpreterInTheSameSessionByClassName(SparkInterpreter.class);
    this.sqlSplitter = new SqlSplitter();
  }

  public boolean concurrentSQL() {
    return Boolean.parseBoolean(getProperty("zeppelin.spark.concurrentSQL"));
  }

  @Override
  public void close() {}

  @Override
  protected boolean isInterpolate() {
    return Boolean.parseBoolean(getProperty("zeppelin.spark.sql.interpolation", "false"));
  }

  @Override
  public ZeppelinContext getZeppelinContext() {
    return null;
  }

  @Override
  public InterpreterResult internalInterpret(String st, InterpreterContext context)
      throws InterpreterException {
    if (sparkInterpreter.isUnsupportedSparkVersion()) {
      return new InterpreterResult(Code.ERROR, "Spark "
          + sparkInterpreter.getSparkVersion().toString() + " is not supported");
    }
    Utils.printDeprecateMessage(sparkInterpreter.getSparkVersion(), context, properties);
    sparkInterpreter.getZeppelinContext().setInterpreterContext(context);
    Object sqlContext = sparkInterpreter.getSQLContext();
    SparkContext sc = sparkInterpreter.getSparkContext();
    try {
      st = injectOtherDb(st);
    } catch (IOException e) {
      try{
        context.out.write(e.getCause().getMessage());
        context.out.flush();
        return new InterpreterResult(Code.ERROR);
      }catch(IOException ex) {
        LOGGER.error("Fail to write output", ex);
        return new InterpreterResult(Code.ERROR);
      }

    }
    List<String> sqls = sqlSplitter.splitSql(st);
    int maxResult = Integer.parseInt(context.getLocalProperties().getOrDefault("limit",
            "" + sparkInterpreter.getZeppelinContext().getMaxResult()));

    sc.setLocalProperty("spark.scheduler.pool", context.getLocalProperties().get("pool"));
    sc.setJobGroup(Utils.buildJobGroupId(context), Utils.buildJobDesc(context), false);
    String curSql = null;
    ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();


    try {


      if (!sparkInterpreter.isScala212()) {
        // TODO(zjffdu) scala 2.12 still doesn't work for codegen (ZEPPELIN-4627)

      Thread.currentThread().setContextClassLoader(sparkInterpreter.getScalaShellClassLoader());
      }
      Method method = sqlContext.getClass().getMethod("sql", String.class);
      for (String sql : sqls) {
        curSql = sql;
        String result = sparkInterpreter.getZeppelinContext()
                .showData(method.invoke(sqlContext, sql), maxResult);
        context.out.write(result);
      }
      context.out.flush();
    } catch (Exception e) {
      try {
        if (e.getCause() instanceof AnalysisException) {
          // just return the error message from spark if it is AnalysisException
          context.out.write(e.getCause().getMessage());
          context.out.flush();
          return new InterpreterResult(Code.ERROR);
        } else {
          LOGGER.error("Error happens in sql: {}", curSql, e);
          context.out.write("\nError happens in sql: " + curSql + "\n");
          if (Boolean.parseBoolean(getProperty("zeppelin.spark.sql.stacktrace", "false"))) {
            if (e.getCause() != null) {
              context.out.write(ExceptionUtils.getStackTrace(e.getCause()));
            } else {
              context.out.write(ExceptionUtils.getStackTrace(e));
            }
          } else {
            StringBuilder msgBuilder = new StringBuilder();
            if (e.getCause() != null) {
              msgBuilder.append(e.getCause().getMessage());
            } else {
              msgBuilder.append(e.getMessage());
            }
            msgBuilder.append("\nset zeppelin.spark.sql.stacktrace = true to see full stacktrace");
            context.out.write(msgBuilder.toString());
          }
          context.out.flush();
          return new InterpreterResult(Code.ERROR);
        }
      } catch (IOException ex) {
        LOGGER.error("Fail to write output", ex);
        return new InterpreterResult(Code.ERROR);
      }
    } finally {
      sc.clearJobGroup();
      if (!sparkInterpreter.isScala212()) {
        Thread.currentThread().setContextClassLoader(originalClassLoader);
      }
    }

    return new InterpreterResult(Code.SUCCESS);
  }


  /**
   * inject mysql/mongodb table to spark sql session
   * the table that need to inject should be declared like : %interpreterName%.dbName.tableName
   * table will be fully load into spark session without any filter
   * @param st
   * @return
   * @throws IOException
   */
  public String injectOtherDb(String st) throws IOException {
    LOGGER.info("##### 开始处理spark sql:"+st);
    ZeppelinConfiguration zConf = ZeppelinConfiguration.create();
    File interpreterSettingPath = new File(zConf.getInterpreterSettingPath(true));
    // load interpreter settings
    LOGGER.info(String.format("##### 从%s读取解释器配置", interpreterSettingPath));
    String json = FileUtils.readFromFile(interpreterSettingPath);
    LOGGER.info(json);
    JSONObject interpreterSettings =  JSON.parseObject(json).getJSONObject("interpreterSettings");
    // get spark session
    LOGGER.info("##### 开始获取spark session");
    SparkSession session = (SparkSession) sparkInterpreter.getSparkSession();
    LOGGER.info("##### 获取到spark session");
    // try to get interpreter name and db_name and table_name
    Pattern pattern = Pattern.compile("([-_0-9a-zA-Z]*)\\.([-_0-9a-zA-Z]*)\\.([-_0-9a-zA-Z]*)");
    Matcher matcher = pattern.matcher(st);
    HashMap<String, Tuple3<String,String,String>> imap = new HashMap<>();
    while(matcher.find() && !imap.containsKey(matcher.group())){

      // the value of map is interpreter_id, db_name, table_name
      imap.put(matcher.group(), new Tuple3<>(matcher.group(1),matcher.group(2),matcher.group(3)));
    }
    for(Tuple3<String,String,String> info:imap.values()){
      String interpreterId = info._1;
      String dbName = info._2;
      String tableName = info._3;
      LOGGER.info(String.format("##### 尝试获取解释器配置:%s", interpreterId));
      // interpreter group
      String iGroup = interpreterSettings.getJSONObject(interpreterId).getString("group");
      // if interpreter group is jdbc, then try to match Driver
      if(iGroup.equals("jdbc")){
        String jdbcUrl = interpreterSettings.getJSONObject(interpreterId).getJSONObject("properties").getJSONObject("default.url").getString("value");
        // mysql
        if(jdbcUrl.startsWith("jdbc:mysql://")){
          String user = interpreterSettings.getJSONObject(interpreterId).getJSONObject("properties").getJSONObject("default.user").getString("value");
          String password = interpreterSettings.getJSONObject(interpreterId).getJSONObject("properties").getJSONObject("default.password").getString("value");
          LOGGER.info("##### 开始注入虚拟表");
          Properties properties = new Properties();
          properties.setProperty("user", user);
          properties.setProperty("password", password);
          properties.setProperty("driver","com.mysql.cj.jdbc.Driver");
          String newTableName = String.format("%s_%s_%s", interpreterId,dbName,tableName);
          String sqlStr = String.format("(select * from %s.%s) as t", dbName,tableName);
          session.read().jdbc(
                  jdbcUrl,
                  sqlStr,
                  properties)
                  .registerTempTable(newTableName);
          st = st.replaceAll(interpreterId+"\\."+dbName+"\\."+tableName,newTableName);
          LOGGER.info("##### 完成注入");
        }
        else{
          throw new IOException(String.format("Unsupported interpreter: %s", interpreterId));
        }
      }

    }
    return st;


  }

  @Override
  public void cancel(InterpreterContext context) throws InterpreterException {
    SparkContext sc = sparkInterpreter.getSparkContext();
    sc.cancelJobGroup(Utils.buildJobGroupId(context));
  }

  @Override
  public FormType getFormType() {
    return FormType.SIMPLE;
  }


  @Override
  public int getProgress(InterpreterContext context) throws InterpreterException {
    return sparkInterpreter.getProgress(context);
  }

  @Override
  public Scheduler getScheduler() {
    if (concurrentSQL()) {
      int maxConcurrency = Integer.parseInt(getProperty("zeppelin.spark.concurrentSQL.max", "10"));
      return SchedulerFactory.singleton().createOrGetParallelScheduler(
          SparkSqlInterpreter.class.getName() + this.hashCode(), maxConcurrency);
    } else {
      // getSparkInterpreter() calls open() inside.
      // That means if SparkInterpreter is not opened, it'll wait until SparkInterpreter open.
      // In this moment UI displays 'READY' or 'FINISHED' instead of 'PENDING' or 'RUNNING'.
      // It's because of scheduler is not created yet, and scheduler is created by this function.
      // Therefore, we can still use getSparkInterpreter() here, but it's better and safe
      // to getSparkInterpreter without opening it.
      try {
        return getInterpreterInTheSameSessionByClassName(SparkInterpreter.class, false)
            .getScheduler();
      } catch (InterpreterException e) {
        throw new RuntimeException("Fail to getScheduler", e);
      }
    }
  }
}
