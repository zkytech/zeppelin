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

package org.apache.zeppelin.livy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import com.google.gson.reflect.TypeToken;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.http.auth.AuthSchemeProvider;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.InvalidCredentialsException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.conn.ssl.SSLContextBuilder;
import org.apache.http.impl.auth.SPNegoSchemeFactory;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.zeppelin.conf.ZeppelinConfiguration;
import org.apache.zeppelin.interpreter.*;
import org.apache.zeppelin.user.AuthenticationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.security.kerberos.client.KerberosRestTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.Principal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;

import org.apache.zeppelin.interpreter.thrift.InterpreterCompletion;

import static java.lang.String.format;

/**
 * Base class for livy interpreters.
 */
public abstract class BaseLivyInterpreter extends Interpreter {

  protected static final Logger LOGGER = LoggerFactory.getLogger(BaseLivyInterpreter.class);
  private static Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  private static final String SESSION_NOT_FOUND_PATTERN = "(.*)\"Session '\\d+' not found.\"(.*)";

  protected volatile SessionInfo sessionInfo;
  private String livyURL;
  private int sessionCreationTimeout;
  private int pullStatusInterval;
  private int maxLogLines;
  protected boolean displayAppInfo;
  private boolean restartDeadSession;
  protected LivyVersion livyVersion;
  private RestTemplate restTemplate;
  private Map<String, String> customHeaders = new HashMap<>();

  // delegate to sharedInterpreter when it is available
  protected LivySharedInterpreter sharedInterpreter;

  Set<Object> paragraphsToCancel = Collections.newSetFromMap(
      new ConcurrentHashMap<Object, Boolean>());
  private ConcurrentHashMap<String, Integer> paragraphId2StmtProgressMap =
      new ConcurrentHashMap<>();

  public BaseLivyInterpreter(Properties property) {
    super(property);
    this.livyURL = property.getProperty("zeppelin.livy.url");
    this.displayAppInfo = Boolean.parseBoolean(
        property.getProperty("zeppelin.livy.displayAppInfo", "true"));
    this.restartDeadSession = Boolean.parseBoolean(
        property.getProperty("zeppelin.livy.restart_dead_session", "false"));
    this.sessionCreationTimeout = Integer.parseInt(
        property.getProperty("zeppelin.livy.session.create_timeout", 120 + ""));
    this.pullStatusInterval = Integer.parseInt(
        property.getProperty("zeppelin.livy.pull_status.interval.millis", 1000 + ""));
    this.maxLogLines = Integer.parseInt(property.getProperty("zeppelin.livy.maxLogLines",
        "1000"));
    this.restTemplate = createRestTemplate();
    if (!StringUtils.isBlank(property.getProperty("zeppelin.livy.http.headers"))) {
      String[] headers = property.getProperty("zeppelin.livy.http.headers").split(";");
      for (String header : headers) {
        String[] splits = header.split(":", -1);
        if (splits.length != 2) {
          throw new RuntimeException("Invalid format of http headers: " + header +
              ", valid http header format is HEADER_NAME:HEADER_VALUE");
        }
        customHeaders.put(splits[0].trim(), envSubstitute(splits[1].trim()));
      }
    }
  }

  private String envSubstitute(String value) {
    String newValue = new String(value);
    Pattern pattern = Pattern.compile("\\$\\{(.*)\\}");
    Matcher matcher = pattern.matcher(value);
    while (matcher.find()) {
      String env = matcher.group(1);
      newValue = newValue.replace("${" + env + "}", System.getenv(env));
    }
    return newValue;
  }

  // only for testing
  Map<String, String> getCustomHeaders() {
    return customHeaders;
  }

  public abstract String getSessionKind();

  @Override
  public void open() throws InterpreterException {
    try {
      this.livyVersion = getLivyVersion();
      if (this.livyVersion.isSharedSupported()) {
        sharedInterpreter = getInterpreterInTheSameSessionByClassName(LivySharedInterpreter.class);
      }
      if (sharedInterpreter == null || !sharedInterpreter.isSupported()) {
        initLivySession();
      }
    } catch (LivyException e) {
      String msg = "Fail to create session, please check livy interpreter log and " +
          "livy server log";
      throw new InterpreterException(msg, e);
    }
  }

  @Override
  public void close() {
    if (sharedInterpreter != null && sharedInterpreter.isSupported()) {
      sharedInterpreter.close();
      return;
    }
    if (sessionInfo != null) {
      closeSession(sessionInfo.id);
      // reset sessionInfo to null so that we won't close it twice.
      sessionInfo = null;
    }
  }

  protected void initLivySession() throws LivyException {
    this.sessionInfo = createSession(getUserName(), getSessionKind());
    if (displayAppInfo) {
      if (sessionInfo.appId == null) {
        // livy 0.2 don't return appId and sparkUiUrl in response so that we need to get it
        // explicitly by ourselves.
        sessionInfo.appId = extractAppId();
      }

      if (sessionInfo.appInfo == null ||
          StringUtils.isEmpty(sessionInfo.appInfo.get("sparkUiUrl"))) {
        sessionInfo.webUIAddress = extractWebUIAddress();
      } else {
        sessionInfo.webUIAddress = sessionInfo.appInfo.get("sparkUiUrl");
      }
      LOGGER.info("Create livy session successfully with sessionId: {}, appId: {}, webUI: {}",
          sessionInfo.id, sessionInfo.appId, sessionInfo.webUIAddress);
    } else {
      LOGGER.info("Create livy session successfully with sessionId: {}", this.sessionInfo.id);
    }
  }

  protected abstract String extractAppId() throws LivyException;

  protected abstract String extractWebUIAddress() throws LivyException;

  public SessionInfo getSessionInfo() {
    if (sharedInterpreter != null && sharedInterpreter.isSupported()) {
      return sharedInterpreter.getSessionInfo();
    }
    return sessionInfo;
  }

  public String getCodeType() {
    if (getSessionKind().equalsIgnoreCase("pyspark3")) {
      return "pyspark";
    }
    return getSessionKind();
  }

  @Override
  public InterpreterResult interpret(String st, InterpreterContext context) {
    try{
      st = injectOtherDb(st,context);
      LOGGER.info("##### interpreting content: \n" + st);
    } catch (Exception e) {
      try{
        LOGGER.error("inject DB error",e);
        context.out.write(e.getMessage());
        e.printStackTrace();
        context.out.flush();
        return new InterpreterResult(InterpreterResult.Code.ERROR);
      }catch(IOException ex) {
        LOGGER.error("Fail to write output", ex);
        return new InterpreterResult(InterpreterResult.Code.ERROR);
      }

    }

    if (sharedInterpreter != null && sharedInterpreter.isSupported()) {
      return sharedInterpreter.interpret(st, getCodeType(), context);
    }
    if (StringUtils.isEmpty(st)) {
      return new InterpreterResult(InterpreterResult.Code.SUCCESS, "");
    }

    try {
      return interpret(st, null, context.getParagraphId(), this.displayAppInfo, true, true);
    } catch (LivyException e) {
      LOGGER.error("Fail to interpret: {}", st, e);
      return new InterpreterResult(InterpreterResult.Code.ERROR,
          InterpreterUtils.getMostRelevantMessage(e));
    }
  }

  public String injectOtherDb(String st,InterpreterContext context) throws IOException, InvalidCredentialsException, LivyException {

    String currUser = context.getLocalProperties().get("currUser");
    String roles = context.getLocalProperties().get("currRoles");
    LOGGER.info("#########################");
    LOGGER.info("currUser:" + currUser);
    LOGGER.info("currRoles:" + roles);
    if(currUser == null){
      return st;
    }
    HashSet<String> usersAndRoles = new HashSet<>(Arrays.asList(roles.split(",")));
    usersAndRoles.add(currUser);
    Gson gson = new Gson();
    // try to load interpreter settings from
    Type listType = new TypeToken<ArrayList<InterSetting>>(){}.getType();
    String interSettingsStr = context.getLocalProperties().get("interpreterSettings");
    if(interSettingsStr == null || interSettingsStr.isEmpty()){
      try {
        ZeppelinConfiguration zConf = ZeppelinConfiguration.create();
        InterpreterSettingManager interpreterSettingManager =
                new InterpreterSettingManager(zConf, null, null, null);
        interSettingsStr = gson.toJson(interpreterSettingManager.get());
      }catch (Exception e){
        LOGGER.error(e.getMessage());
        e.printStackTrace();
      }
    }
    if(interSettingsStr == null || interSettingsStr.isEmpty()){
      context.out.write("\ncannot perform cross engine query , reason : can not load interpreter settings" );
    }
    List<InterSetting> interSettings = gson.fromJson(context.getLocalProperties().get("interpreterSettings"),listType);

    // try to get interpreter name and db_name and table_name
    Pattern pattern = Pattern.compile("([-_0-9a-zA-Z]*)\\.([-_0-9a-zA-Z]*)\\.([-_0-9a-zA-Z]*)");
    Matcher matcher = pattern.matcher(st);
    HashMap<String, org.parboiled.common.Tuple3<String,String,String>> imap = new HashMap<>();

    while(matcher.find()){
      if(!imap.containsKey(matcher.group())){
        // the value of map is interpreter_id, db_name, table_name
        imap.put(matcher.group(), new org.parboiled.common.Tuple3<>(matcher.group(1),matcher.group(2),matcher.group(3)));
      }
    }
    LOGGER.info("##### found inject target:" + imap.toString());
    for(org.parboiled.common.Tuple3<String,String,String> info:imap.values()){
      String code_to_exec = null;
      String interpreterId = info.a;
      String dbName = info.b;
      String tableName = info.c;
      LOGGER.info("##### injecting :" + interpreterId);

      InterSetting iSetting = getInterpreterFromList(interSettings,interpreterId).orElse(null);
      if(iSetting == null){
//        throw new IOException(String.format("No such interpreter with id : %s", interpreterId));
        continue;
      }
      // check if this user can access current interpreter
      HashSet<String> owners = new HashSet<>(iSetting.option.owners);
      // if owners is empty, means all users can access
      if(!owners.isEmpty()){
        if(!owners.retainAll(usersAndRoles)){
          // no user or roles match
          throw new InvalidCredentialsException(String.format(String.format("user %s has no privilege to access interpreter %s",currUser, interpreterId)));
        }
      }
      String iGroup = iSetting.group;

      String newTableName = String.format("%s_%s_%s", interpreterId,dbName,tableName).replace("-","_");
      // do inject
      if(iGroup.equals("jdbc")){
        String jdbcUrl = iSetting.getProp("default.url");
        String user = iSetting.getProp("default.user");
        String password =  iSetting.getProp("default.password");
        String driver =  iSetting.getProp("default.driver");

        code_to_exec = "           var properties = new java.util.Properties()\n" +
                "        properties.setProperty(\"user\", \""+user+"\")\n" +
                "        properties.setProperty(\"password\", \""+password+"\")\n" +
                "        properties.setProperty(\"driver\",\""+driver+"\")     \n" +
                "spark.read.jdbc(\""+jdbcUrl+"\", \""+dbName + "." + tableName +"\",properties).registerTempTable(\""+newTableName+"\")\n" + st;

      }
      else if (iGroup.equals("mongodb")){
        String user =  iSetting.getProp("mongo.server.username","");
        String password =  iSetting.getProp("mongo.server.password","");
        String host = iSetting.getProp("mongo.server.host","127.0.0.1");
        String port = iSetting.getProp("mongo.server.port","27017");
        String authDb = iSetting.getProp("mongo.server.authenticationDatabase","");
        password = URLEncoder.encode(password, StandardCharsets.UTF_8.name());
        String mongoUri = String.format("mongodb://%s:%s@%s:%s/%s.%s?authSource=%s",user,password,host,port,dbName,tableName,authDb);
        code_to_exec = "import com.mongodb.spark.config._\n" +
                "import com.mongodb.spark.MongoSpark\n" +
                "\n" +
                "var readConfig = ReadConfig(\n" +
                "    Map(\"collection\" -> \""+tableName+"\",\n" +
                "        \"database\" -> \""+dbName+"\",\n" +
                "        \"uri\" -> \""+mongoUri+"\"\n" +
                "        )\n" +
                "    )\n" +
                "MongoSpark.load(spark,readConfig).toDF.registerTempTable(\""+newTableName+"\")  " +
                "\n"

        ;

      } else if (iGroup.equals("hbase")) {
        code_to_exec =  "" +
                "import org.apache.hadoop.conf.Configuration\n" +
                "import org.apache.hadoop.security.UserGroupInformation\n" +
                "import org.apache.hadoop.hbase.HBaseConfiguration\n" +
                "import org.apache.hadoop.hbase.client.{Connection, ConnectionFactory, HBaseAdmin}\n" +
                "import org.apache.hadoop.hbase.security.User\n" +
                "import java.security.PrivilegedAction\n" +
                "import org.apache.hadoop.hbase.TableName\n" +
                "import org.apache.hadoop.hbase.client.Scan\n" +
                "import org.apache.hadoop.hbase.util.Bytes\n" +
                "import org.apache.spark.SparkEnv\n" +
                "val requireKerberos = SparkEnv.get.conf.getBoolean(\"spark.hbase.connector.security.credentials.enabled\",false)\n" +
                "val conf = HBaseConfiguration.create\n" +
                "conf.set(\"hbase.zookeeper.quorum\", \"hadoop1.4482.interconnect-hy2:2181,hadoop2.4482.interconnect-hy2:2181,hadoop3.4482.interconnect-hy2:2181\")\n" +
                "\n" +
                "var connection:org.apache.hadoop.hbase.client.Connection = null\n" +
                "if(requireKerberos){\n" +
                "    val principal = SparkEnv.get.conf.get(\"spark.hbase.connector.security.credentials\")\n" +
                "    val keytab = SparkEnv.get.conf.get(\"spark.hbase.connector.security.keytab\")\n" +
                "    System.setProperty(\"javax.security.auth.useSubjectCredsOnly\", \"false\")\n" +
                "    conf.set(\"hbase.client.kerberos.principal\", principal)\n" +
                "    conf.set(\"hbase.client.keytab.file\", keytab)\n" +
                "    val ugi = UserGroupInformation.loginUserFromKeytabAndReturnUGI(principal, keytab)\n" +
                "    ugi.doAs(new PrivilegedAction[Unit] {\n" +
                "        override def run(): Unit = {\n" +
                "            connection = ConnectionFactory.createConnection(conf,User.create(ugi))\n" +
                "            HBaseAdmin.available(conf)\n" +
                "        }\n" +
                "  })\n" +
                "}else{\n" +
                "     connection = ConnectionFactory.createConnection(conf)\n" +
                "}\n" +
                "// scan first row to get structure\n" +
                "val namespace = \""+dbName+"\"\n" +
                "val table_name = \""+tableName+"\"\n" +
                "val scan = new Scan()\n" +
                "val table = connection.getTable(TableName.valueOf(s\"${namespace}:${table_name}\"))\n" +
                "val map_all = table.getScanner(scan).next().getMap\n" +
                "val it_fm = map_all.keySet().iterator()\n" +
                "var col_names:Seq[String] = Seq()\n" +
                "\n" +
                "while(it_fm.hasNext){\n" +
                "    val family = it_fm.next()\n" +
                "    val it_col = map_all.get(family).keySet().iterator()\n" +
                "    \n" +
                "    while (it_col.hasNext){\n" +
                "        val col = it_col.next()\n" +
                "        val col_name = s\"${Bytes.toString(family)}:${Bytes.toString(col)}\"\n" +
                "        col_names = col_names ++ Seq(col_name)\n" +
                "    }\n" +
                "}\n" +
                "connection.close()\n" +
                "\n" +
                "\n" +
                "import org.apache.spark.sql.execution.datasources.hbase._\n" +
                "val col_struct = col_names.map(_.split(\":\")).map(t => {\n" +
                "    val s1 = t(0)\n" +
                "    val s2 = t(1)\n" +
                "    s\"\"\" \"${s1}_${s2}\":{\"cf\":\"$s1\" , \"col\":\"$s2\", \"type\":\"string\"} \"\"\"}).mkString(\",\")\n" +
                "\n" +
                "val catalog = s\"\"\"{\n" +
                "        |\"table\":{\"namespace\":\"${namespace}\", \"name\":\"${table_name}\"},\n" +
                "        |\"rowkey\":\"rowkey\",\n" +
                "        |\"columns\":{\n" +
                "          |\"rowkey\":{\"cf\":\"rowkey\", \"col\":\"rowkey\", \"type\":\"string\"},\n" +
                "          |${col_struct}\n" +
                "        |}\n" +
                "      |}\"\"\".stripMargin\n" +
                "\n" +
                "spark.sqlContext.\n" +
                "  read.\n" +
                "  options(Map(\n" +
                "        HBaseTableCatalog.tableCatalog->catalog\n" +
                "  )).\n" +
                "  format(\"org.apache.spark.sql.execution.datasources.hbase\").\n" +
                "  load().\n" +
                "  registerTempTable(\""+newTableName+"\")\n";

      } else{
//        throw new IOException(String.format("Unsupported interpreter: %s", interpreterId));
        continue;
      }
      // replace table qualifier in sql str
      st = st.replaceAll(interpreterId+"\\."+dbName+"\\."+tableName,newTableName);
      LOGGER.info("finished inject :" + interpreterId);
      if (sharedInterpreter != null && sharedInterpreter.isSupported()) {
        sharedInterpreter.interpret(code_to_exec, getCodeType(), context);
        continue;
      }
      interpret(st, null, context.getParagraphId(), this.displayAppInfo, true, true);
    }
    return st;


  }
  private class InterSetting implements Serializable {
    public String id;
    public String name;
    public String group;
    public Map<String,Prop> properties;
    public String status;
    public Opt option;

    public String getProp(String key){
      Prop prop =  properties.get(key);
      if(prop == null){
        return null;
      }
      return prop.value;
    }

    public String getProp(String key,String default_val){
      if (getProp(key) == null){
        return default_val;
      }else{
        return getProp(key);
      }
    }

    public class Prop implements Serializable{
      public String name;
      public String value;
      public String type;
      public String description;
    }

    private class Opt implements Serializable{
      public boolean remote;
      public int port;
      public String perNote;
      public String perUser;
      public boolean isExistingProcess;
      public boolean setPermission;
      public List<String> owners;
      public boolean isUserImpersonate;
    }

  }
  private Optional<InterSetting> getInterpreterFromList(List<InterSetting> interpreterSettings,String interpreterId){
    return interpreterSettings.stream().filter(interpreterSetting -> Objects.equals(interpreterSetting.id, interpreterId)).findFirst();
  }
  @Override
  public List<InterpreterCompletion> completion(String buf, int cursor,
      InterpreterContext interpreterContext) {
    List<InterpreterCompletion> candidates = Collections.emptyList();
    try {
      candidates = callCompletion(new CompletionRequest(buf, getSessionKind(), cursor));
    } catch (SessionNotFoundException e) {
      LOGGER.warn("Livy session {} is expired. Will return empty list of candidates.",
          getSessionInfo().id);
    } catch (LivyException le) {
      LOGGER.error("Failed to call code completions. Will return empty list of candidates", le);
    }
    return candidates;
  }

  private List<InterpreterCompletion> callCompletion(CompletionRequest req) throws LivyException {
    List<InterpreterCompletion> candidates = new ArrayList<>();
    try {
      CompletionResponse resp = CompletionResponse.fromJson(
          callRestAPI("/sessions/" + getSessionInfo().id + "/completion", "POST", req.toJson()));
      for (String candidate : resp.candidates) {
        candidates.add(new InterpreterCompletion(candidate, candidate, StringUtils.EMPTY));
      }
    } catch (APINotFoundException e) {
      LOGGER.debug("completion api seems not to be available. (available from livy 0.5)", e);
    }
    return candidates;
  }

  @Override
  public void cancel(InterpreterContext context) {
    if (sharedInterpreter != null && sharedInterpreter.isSupported()) {
      sharedInterpreter.cancel(context);
      return;
    }
    paragraphsToCancel.add(context.getParagraphId());
    LOGGER.info("Added paragraph {} for cancellation.", context.getParagraphId());
  }

  @Override
  public FormType getFormType() {
    return FormType.NATIVE;
  }

  @Override
  public int getProgress(InterpreterContext context) {
    if (sharedInterpreter != null && sharedInterpreter.isSupported()) {
      return sharedInterpreter.getProgress(context);
    }

    if (livyVersion.isGetProgressSupported()) {
      String paraId = context.getParagraphId();
      Integer progress = paragraphId2StmtProgressMap.get(paraId);
      return progress == null ? 0 : progress;
    }
    return 0;
  }

  private SessionInfo createSession(String user, String kind)
      throws LivyException {
    try {
      Map<String, String> conf = new HashMap<>();
      for (Map.Entry<Object, Object> entry : getProperties().entrySet()) {
        if (entry.getKey().toString().startsWith("livy.spark.") &&
            !entry.getValue().toString().isEmpty()) {
          conf.put(entry.getKey().toString().substring(5), entry.getValue().toString());
        }
      }
      user = this.getProperty("zeppelin.livy.defaultUser");
      CreateSessionRequest request = new CreateSessionRequest(kind,
                user == null || user.equals("anonymous") ? null : user, conf);
      SessionInfo sessionInfo = SessionInfo.fromJson(
          callRestAPI("/sessions", "POST", request.toJson()));
      LOGGER.info(format("##### username :%s", user));
      long start = System.currentTimeMillis();
      // pull the session status until it is idle or timeout
      while (!sessionInfo.isReady()) {
        if ((System.currentTimeMillis() - start) / 1000 > sessionCreationTimeout) {
          String msg = "The creation of session " + sessionInfo.id + " is timeout within "
              + sessionCreationTimeout + " seconds, appId: " + sessionInfo.appId
              + ", log:\n" + StringUtils.join(getSessionLog(sessionInfo.id).log, "\n");
          throw new LivyException(msg);
        }
        Thread.sleep(pullStatusInterval);
        sessionInfo = getSessionInfo(sessionInfo.id);
        LOGGER.info("Session {} is in state {}, appId {}", sessionInfo.id, sessionInfo.state,
            sessionInfo.appId);
        if (sessionInfo.isFinished()) {
          String msg = "Session " + sessionInfo.id + " is finished, appId: " + sessionInfo.appId
              + ", log:\n" + StringUtils.join(getSessionLog(sessionInfo.id).log, "\n");
          throw new LivyException(msg);
        }
      }
      return sessionInfo;
    } catch (Exception e) {
      LOGGER.error("Error when creating livy session for user {}", user, e);
      throw new LivyException(e);
    }
  }

  private SessionInfo getSessionInfo(int sessionId) throws LivyException {
    return SessionInfo.fromJson(callRestAPI("/sessions/" + sessionId, "GET"));
  }

  private SessionLog getSessionLog(int sessionId) throws LivyException {
    return SessionLog.fromJson(callRestAPI("/sessions/" + sessionId + "/log?size=" + maxLogLines,
        "GET"));
  }

  public InterpreterResult interpret(String code,
                                     String paragraphId,
                                     boolean displayAppInfo,
                                     boolean appendSessionExpired,
                                     boolean appendSessionDead) throws LivyException {
    return interpret(code, sharedInterpreter.isSupported() ? getSessionKind() : null,
        paragraphId, displayAppInfo, appendSessionExpired, appendSessionDead);
  }

  public InterpreterResult interpret(String code,
                                     String codeType,
                                     String paragraphId,
                                     boolean displayAppInfo,
                                     boolean appendSessionExpired,
                                     boolean appendSessionDead) throws LivyException {
    StatementInfo stmtInfo = null;
    boolean sessionExpired = false;
    boolean sessionDead = false;
    try {
      try {
        stmtInfo = executeStatement(new ExecuteRequest(code, codeType));
      } catch (SessionNotFoundException e) {
        LOGGER.warn("Livy session {} is expired, new session will be created.", sessionInfo.id);
        sessionExpired = true;
        // we don't want to create multiple sessions because it is possible to have multiple thread
        // to call this method, like LivySparkSQLInterpreter which use ParallelScheduler. So we need
        // to check session status again in this sync block
        synchronized (this) {
          if (isSessionExpired()) {
            initLivySession();
          }
        }
        stmtInfo = executeStatement(new ExecuteRequest(code, codeType));
      } catch (SessionDeadException e) {
        sessionDead = true;
        if (restartDeadSession) {
          LOGGER.warn("Livy session {} is dead, new session will be created.", sessionInfo.id);
          close();
          try {
            open();
          } catch (InterpreterException ie) {
            throw new LivyException("Fail to restart livy session", ie);
          }
          stmtInfo = executeStatement(new ExecuteRequest(code, codeType));
        } else {
          throw new LivyException("%html <font color=\"red\">Livy session is dead somehow, " +
              "please check log to see why it is dead, and then restart livy interpreter</font>");
        }
      }

      // pull the statement status
      while (!stmtInfo.isAvailable()) {
        if (paragraphId != null && paragraphsToCancel.contains(paragraphId)) {
          cancel(stmtInfo.id, paragraphId);
          return new InterpreterResult(InterpreterResult.Code.ERROR, "Job is cancelled");
        }
        try {
          Thread.sleep(pullStatusInterval);
        } catch (InterruptedException e) {
          LOGGER.error("InterruptedException when pulling statement status.", e);
          throw new LivyException(e);
        }
        stmtInfo = getStatementInfo(stmtInfo.id);
        if (paragraphId != null) {
          paragraphId2StmtProgressMap.put(paragraphId, (int) (stmtInfo.progress * 100));
        }
      }
      if (appendSessionExpired || appendSessionDead) {
        return appendSessionExpireDead(getResultFromStatementInfo(stmtInfo, displayAppInfo),
            sessionExpired, sessionDead);
      } else {
        return getResultFromStatementInfo(stmtInfo, displayAppInfo);
      }
    } finally {
      if (paragraphId != null) {
        paragraphId2StmtProgressMap.remove(paragraphId);
        paragraphsToCancel.remove(paragraphId);
      }
    }
  }

  private void cancel(int id, String paragraphId) {
    if (livyVersion.isCancelSupported()) {
      try {
        LOGGER.info("Cancelling statement {}", id);
        cancelStatement(id);
      } catch (LivyException e) {
        LOGGER.error("Fail to cancel statement {} for paragraph {}", id, paragraphId, e);
      } finally {
        paragraphsToCancel.remove(paragraphId);
      }
    } else {
      LOGGER.warn("cancel is not supported for this version of livy: {}", livyVersion);
      paragraphsToCancel.clear();
    }
  }

  protected LivyVersion getLivyVersion() throws LivyException {
    return new LivyVersion((LivyVersionResponse.fromJson(callRestAPI("/version", "GET")).version));
  }

  private boolean isSessionExpired() throws LivyException {
    try {
      getSessionInfo(sessionInfo.id);
      return false;
    } catch (SessionNotFoundException e) {
      return true;
    } catch (LivyException e) {
      throw e;
    }
  }

  private InterpreterResult appendSessionExpireDead(InterpreterResult result,
                                                    boolean sessionExpired,
                                                    boolean sessionDead) {
    InterpreterResult result2 = new InterpreterResult(result.code());
    if (sessionExpired) {
      result2.add(InterpreterResult.Type.HTML,
          "<font color=\"red\">Previous livy session is expired, new livy session is created. " +
              "Paragraphs that depend on this paragraph need to be re-executed!</font>");

    }
    if (sessionDead) {
      result2.add(InterpreterResult.Type.HTML,
          "<font color=\"red\">Previous livy session is dead, new livy session is created. " +
              "Paragraphs that depend on this paragraph need to be re-executed!</font>");
    }

    for (InterpreterResultMessage message : result.message()) {
      result2.add(message.getType(), message.getData());
    }
    return result2;
  }

  private InterpreterResult getResultFromStatementInfo(StatementInfo stmtInfo,
                                                       boolean displayAppInfo) {
    if (stmtInfo.output != null && stmtInfo.output.isError()) {
      InterpreterResult result = new InterpreterResult(InterpreterResult.Code.ERROR);
      StringBuilder sb = new StringBuilder();
      sb.append(stmtInfo.output.evalue);
      // in case evalue doesn't have newline char
      if (!stmtInfo.output.evalue.contains("\n")) {
        sb.append("\n");
      }

      if (stmtInfo.output.traceback != null) {
        sb.append(StringUtils.join(stmtInfo.output.traceback));
      }
      result.add(sb.toString());
      return result;
    } else if (stmtInfo.isCancelled()) {
      // corner case, output might be null if it is cancelled.
      return new InterpreterResult(InterpreterResult.Code.ERROR, "Job is cancelled");
    } else if (stmtInfo.output == null) {
      // This case should never happen, just in case
      return new InterpreterResult(InterpreterResult.Code.ERROR, "Empty output");
    } else {
      //TODO(zjffdu) support other types of data (like json, image and etc)
      String result = stmtInfo.output.data.plainText;

      // check table magic result first
      if (stmtInfo.output.data.applicationLivyTableJson != null) {
        StringBuilder outputBuilder = new StringBuilder();
        boolean notFirstColumn = false;

        for (Map header : stmtInfo.output.data.applicationLivyTableJson.headers) {
          if (notFirstColumn) {
            outputBuilder.append("\t");
          }
          outputBuilder.append(header.get("name"));
          notFirstColumn = true;
        }

        outputBuilder.append("\n");
        for (List<Object> row : stmtInfo.output.data.applicationLivyTableJson.records) {
          outputBuilder.append(StringUtils.join(row, "\t"));
          outputBuilder.append("\n");
        }
        return new InterpreterResult(InterpreterResult.Code.SUCCESS,
            InterpreterResult.Type.TABLE, outputBuilder.toString());
      } else if (stmtInfo.output.data.imagePng != null) {
        return new InterpreterResult(InterpreterResult.Code.SUCCESS,
            InterpreterResult.Type.IMG, stmtInfo.output.data.imagePng);
      } else if (result != null) {
        result = result.trim();
        if (result.startsWith("<link")
            || result.startsWith("<script")
            || result.startsWith("<style")
            || result.startsWith("<div")) {
          result = "%html " + result;
        }
      }

      if (displayAppInfo) {
        InterpreterResult interpreterResult = new InterpreterResult(InterpreterResult.Code.SUCCESS);
        interpreterResult.add(result);
        String appInfoHtml = "<hr/>Spark Application Id: " + sessionInfo.appId + "<br/>"
            + "Spark WebUI: <a href=\"" + sessionInfo.webUIAddress + "\">"
            + sessionInfo.webUIAddress + "</a>";
        interpreterResult.add(InterpreterResult.Type.HTML, appInfoHtml);
        return interpreterResult;
      } else {
        return new InterpreterResult(InterpreterResult.Code.SUCCESS, result);
      }
    }
  }

  private StatementInfo executeStatement(ExecuteRequest executeRequest)
      throws LivyException {
    return StatementInfo.fromJson(callRestAPI("/sessions/" + sessionInfo.id + "/statements", "POST",
        executeRequest.toJson()));
  }

  private StatementInfo getStatementInfo(int statementId)
      throws LivyException {
    return StatementInfo.fromJson(
        callRestAPI("/sessions/" + sessionInfo.id + "/statements/" + statementId, "GET"));
  }

  private void cancelStatement(int statementId) throws LivyException {
    callRestAPI("/sessions/" + sessionInfo.id + "/statements/" + statementId + "/cancel", "POST");
  }

  private SSLContext getSslContext() {
    try {
      // Build truststore
      String trustStoreFile = getProperty("zeppelin.livy.ssl.trustStore");
      String trustStorePassword = getProperty("zeppelin.livy.ssl.trustStorePassword");
      String trustStoreType = getProperty("zeppelin.livy.ssl.trustStoreType",
              KeyStore.getDefaultType());
      if (StringUtils.isBlank(trustStoreFile)) {
        throw new RuntimeException("No zeppelin.livy.ssl.trustStore specified for livy ssl");
      }
      if (StringUtils.isBlank(trustStorePassword)) {
        throw new RuntimeException("No zeppelin.livy.ssl.trustStorePassword specified " +
                "for livy ssl");
      }
      KeyStore trustStore = getStore(trustStoreFile, trustStoreType, trustStorePassword);
      SSLContextBuilder builder = SSLContexts.custom();
      builder.loadTrustMaterial(trustStore);

      // Build keystore
      String keyStoreFile = getProperty("zeppelin.livy.ssl.keyStore");
      String keyStorePassword = getProperty("zeppelin.livy.ssl.keyStorePassword");
      String keyPassword = getProperty("zeppelin.livy.ssl.keyPassword", keyStorePassword);
      String keyStoreType = getProperty("zeppelin.livy.ssl.keyStoreType",
              KeyStore.getDefaultType());
      if (StringUtils.isNotBlank(keyStoreFile)) {
        KeyStore keyStore = getStore(keyStoreFile, keyStoreType, keyStorePassword);
        builder.loadKeyMaterial(keyStore, keyPassword.toCharArray()).useTLS();
      }
      return builder.build();
    } catch (Exception e) {
      throw new RuntimeException("Failed to create SSL Context", e);
    }
  }

  private KeyStore getStore(String file, String type, String password) {
    try (FileInputStream inputStream = new FileInputStream(file)) {
      KeyStore trustStore = KeyStore.getInstance(type);
      trustStore.load(inputStream, password.toCharArray());
      return trustStore;
    } catch (Exception e) {
      throw new RuntimeException("Failed to open keystore " + file, e);
    }
  }

  private RestTemplate createRestTemplate() {
    String keytabLocation = getProperty("zeppelin.livy.keytab");
    String principal = getProperty("zeppelin.livy.principal");
    boolean isSpnegoEnabled = StringUtils.isNotEmpty(keytabLocation) &&
        StringUtils.isNotEmpty(principal);

    HttpClient httpClient = null;
    if (livyURL.startsWith("https:")) {
      try {
        SSLContext sslContext = getSslContext();
        SSLConnectionSocketFactory csf = new SSLConnectionSocketFactory(sslContext);
        HttpClientBuilder httpClientBuilder = HttpClients.custom().setSSLSocketFactory(csf);
        if (isSpnegoEnabled) {
          RequestConfig reqConfig = new RequestConfig() {
            @Override
            public boolean isAuthenticationEnabled() {
              return true;
            }
          };
          httpClientBuilder.setDefaultRequestConfig(reqConfig);
          Credentials credentials = new Credentials() {
            @Override
            public String getPassword() {
              return null;
            }

            @Override
            public Principal getUserPrincipal() {
              return null;
            }
          };
          CredentialsProvider credsProvider = new BasicCredentialsProvider();
          credsProvider.setCredentials(AuthScope.ANY, credentials);
          httpClientBuilder.setDefaultCredentialsProvider(credsProvider);
          Registry<AuthSchemeProvider> authSchemeProviderRegistry =
              RegistryBuilder.<AuthSchemeProvider>create()
                  .register(AuthSchemes.SPNEGO, new SPNegoSchemeFactory())
                  .build();
          httpClientBuilder.setDefaultAuthSchemeRegistry(authSchemeProviderRegistry);
        }

        httpClient = httpClientBuilder.build();
      } catch (Exception e) {
        throw new RuntimeException("Failed to create SSL HttpClient", e);
      }
    }

    RestTemplate restTemplate;
    if (isSpnegoEnabled) {
      if (httpClient == null) {
        restTemplate = new KerberosRestTemplate(keytabLocation, principal);
      } else {
        restTemplate = new KerberosRestTemplate(keytabLocation, principal, httpClient);
      }
    } else {
      if (httpClient == null) {
        restTemplate = new RestTemplate();
      } else {
        restTemplate = new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
      }
    }
    restTemplate.getMessageConverters().add(0,
            new StringHttpMessageConverter(StandardCharsets.UTF_8));
    return restTemplate;
  }

  private String callRestAPI(String targetURL, String method) throws LivyException {
    return callRestAPI(targetURL, method, "");
  }

  private String callRestAPI(String targetURL, String method, String jsonData)
      throws LivyException {
    targetURL = livyURL + targetURL;
    LOGGER.debug("Call rest api in {}, method: {}, jsonData: {}", targetURL, method, jsonData);
    HttpHeaders headers = new HttpHeaders();
    headers.add("Content-Type", MediaType.APPLICATION_JSON_UTF8_VALUE);
    headers.add("X-Requested-By", "zeppelin");
    for (Map.Entry<String, String> entry : customHeaders.entrySet()) {
      headers.add(entry.getKey(), entry.getValue());
    }
    ResponseEntity<String> response = null;
    try {
      if (method.equals("POST")) {
        HttpEntity<String> entity = new HttpEntity<>(jsonData, headers);
        response = restTemplate.exchange(targetURL, HttpMethod.POST, entity, String.class);
      } else if (method.equals("GET")) {
        HttpEntity<String> entity = new HttpEntity<>(headers);
        response = restTemplate.exchange(targetURL, HttpMethod.GET, entity, String.class);
      } else if (method.equals("DELETE")) {
        HttpEntity<String> entity = new HttpEntity<>(headers);
        response = restTemplate.exchange(targetURL, HttpMethod.DELETE, entity, String.class);
      }
    } catch (HttpClientErrorException e) {
      response = new ResponseEntity(e.getResponseBodyAsString(), e.getStatusCode());
      LOGGER.error(format("Error with %s StatusCode: %s",
          response.getStatusCode().value(), e.getResponseBodyAsString()));
    } catch (RestClientException e) {
      // Exception happens when kerberos is enabled.
      if (e.getCause() instanceof HttpClientErrorException) {
        HttpClientErrorException cause = (HttpClientErrorException) e.getCause();
        if (cause.getResponseBodyAsString().matches(SESSION_NOT_FOUND_PATTERN)) {
          throw new SessionNotFoundException(cause.getResponseBodyAsString());
        }
        throw new LivyException(cause.getResponseBodyAsString() + "\n"
            + ExceptionUtils.getStackTrace(ExceptionUtils.getRootCause(e)));
      }
      if (e instanceof HttpServerErrorException) {
        HttpServerErrorException errorException = (HttpServerErrorException) e;
        String errorResponse = errorException.getResponseBodyAsString();
        if (errorResponse.contains("Session is in state dead")) {
          throw new SessionDeadException();
        }
        throw new LivyException(errorResponse, e);
      }
      throw new LivyException(e);
    }
    if (response == null) {
      throw new LivyException("No http response returned");
    }
    LOGGER.debug("Get response, StatusCode: {}, responseBody: {}", response.getStatusCode(),
        response.getBody());
    if (response.getStatusCode().value() == 200
        || response.getStatusCode().value() == 201) {
      return response.getBody();
    } else if (response.getStatusCode().value() == 404) {
      if (response.getBody().matches(SESSION_NOT_FOUND_PATTERN)) {
        throw new SessionNotFoundException(response.getBody());
      } else {
        throw new APINotFoundException("No rest api found for " + targetURL +
            ", " + response.getStatusCode());
      }
    } else {
      String responseString = response.getBody();
      if (responseString.contains("CreateInteractiveRequest[\\\"master\\\"]")) {
        return responseString;
      }
      throw new LivyException(format("Error with %s StatusCode: %s",
          response.getStatusCode().value(), responseString));
    }
  }

  private void closeSession(int sessionId) {
    try {
      callRestAPI("/sessions/" + sessionId, "DELETE");
    } catch (Exception e) {
      LOGGER.error(format("Error closing session for user with session ID: %s",
          sessionId), e);
    }
  }

  /*
  * We create these POJO here to accommodate livy 0.3 which is not released yet. livy rest api has
  * some changes from version to version. So we create these POJO in zeppelin side to accommodate
  * incompatibility between versions. Later, when livy become more stable, we could just depend on
  * livy client jar.
  */
  private static class CreateSessionRequest {
    public final String kind;
    @SerializedName("proxyUser")
    public final String user;
    public final Map<String, String> conf;

    CreateSessionRequest(String kind, String user, Map<String, String> conf) {
      this.kind = kind;
      this.user = user;
      this.conf = conf;
    }

    public String toJson() {
      return gson.toJson(this);
    }
  }

  /**
   *
   */
  public static class SessionInfo {

    public final int id;
    public String appId;
    public String webUIAddress;
    public final String owner;
    public final String proxyUser;
    public final String state;
    public final String kind;
    public final Map<String, String> appInfo;
    public final List<String> log;

    public SessionInfo(int id, String appId, String owner, String proxyUser, String state,
                       String kind, Map<String, String> appInfo, List<String> log) {
      this.id = id;
      this.appId = appId;
      this.owner = owner;
      this.proxyUser = proxyUser;
      this.state = state;
      this.kind = kind;
      this.appInfo = appInfo;
      this.log = log;
    }

    public boolean isReady() {
      return state.equals("idle");
    }

    public boolean isFinished() {
      return state.equals("error") || state.equals("dead") || state.equals("success");
    }

    public static SessionInfo fromJson(String json) {
      return gson.fromJson(json, SessionInfo.class);
    }
  }

  private static class SessionLog {
    public int id;
    public int from;
    public int size;
    public List<String> log;

    SessionLog() {
    }

    public static SessionLog fromJson(String json) {
      return gson.fromJson(json, SessionLog.class);
    }
  }

  static class ExecuteRequest {
    public final String code;
    public final String kind;

    ExecuteRequest(String code, String kind) {
      this.code = code;
      this.kind = kind;
    }

    public String toJson() {
      return gson.toJson(this);
    }
  }

  private static class StatementInfo {
    public Integer id;
    public String state;
    public double progress;
    public StatementOutput output;

    StatementInfo() {
    }

    public static StatementInfo fromJson(String json) {
      String rightJson = "";
      try {
        gson.fromJson(json, StatementInfo.class);
        rightJson = json;
      } catch (Exception e) {
        if (json.contains("\"traceback\":{}")) {
          LOGGER.debug("traceback type mismatch, replacing the mismatching part ");
          rightJson = json.replace("\"traceback\":{}", "\"traceback\":[]");
          LOGGER.debug("new json string is {}", rightJson);
        }
      }
      return gson.fromJson(rightJson, StatementInfo.class);
    }

    public boolean isAvailable() {
      return state.equals("available") || state.equals("cancelled");
    }

    public boolean isCancelled() {
      return state.equals("cancelled");
    }

    private static class StatementOutput {
      public String status;
      public String executionCount;
      public Data data;
      public String ename;
      public String evalue;
      public String[] traceback;
      public TableMagic tableMagic;

      public boolean isError() {
        return status.equals("error");
      }

      public String toJson() {
        return gson.toJson(this);
      }

      private static class Data {
        @SerializedName("text/plain")
        public String plainText;
        @SerializedName("image/png")
        public String imagePng;
        @SerializedName("application/json")
        public String applicationJson;
        @SerializedName("application/vnd.livy.table.v1+json")
        public TableMagic applicationLivyTableJson;
      }

      private static class TableMagic {
        @SerializedName("headers")
        List<Map> headers;

        @SerializedName("data")
        List<List> records;
      }
    }
  }

  static class CompletionRequest {
    public final String code;
    public final String kind;
    public final int cursor;

    CompletionRequest(String code, String kind, int cursor) {
      this.code = code;
      this.kind = kind;
      this.cursor = cursor;
    }

    public String toJson() {
      return gson.toJson(this);
    }
  }

  static class CompletionResponse {
    public final String[] candidates;

    CompletionResponse(String[] candidates) {
      this.candidates = candidates;
    }

    public static CompletionResponse fromJson(String json) {
      return gson.fromJson(json, CompletionResponse.class);
    }
  }

  private static class LivyVersionResponse {
    public String url;
    public String branch;
    public String revision;
    public String version;
    public String date;
    public String user;

    public static LivyVersionResponse fromJson(String json) {
      return gson.fromJson(json, LivyVersionResponse.class);
    }
  }
}
