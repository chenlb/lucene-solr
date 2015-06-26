package org.apache.solr.servlet;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpStatus;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.*;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrConfig;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.ContentStreamHandlerBase;
import org.apache.solr.logging.MDCLoggingContext;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrRequestHandler;
import org.apache.solr.request.SolrRequestInfo;
import org.apache.solr.response.QueryResponseWriter;
import org.apache.solr.response.QueryResponseWriterUtil;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.security.AuthorizationContext;
import org.apache.solr.security.AuthorizationContext.CollectionRequest;
import org.apache.solr.security.AuthorizationContext.RequestType;
import org.apache.solr.security.AuthorizationResponse;
import org.apache.solr.servlet.cache.Method;
import org.apache.solr.update.processor.DistributingUpdateProcessorFactory;
import org.apache.solr.util.RTimer;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.*;

import static org.apache.solr.common.cloud.ZkStateReader.*;
import static org.apache.solr.common.params.CollectionParams.CollectionAction.*;
import static org.apache.solr.servlet.SolrDispatchFilter.Action;
import static org.apache.solr.servlet.SolrDispatchFilter.Action.*;

/**
 * netty protocol that This class represents a call made to Solr
 *
 * TODO abstract SolrCall for netty's and http‘s extends
 **/
public abstract class SolrCall {
  private static final Logger log = LoggerFactory.getLogger(SolrCall.class);

  protected static final Random random;
  static {
    // We try to make things reproducible in the context of our tests by initializing the random instance
    // based on the current seed
    String seed = System.getProperty("tests.seed");
    if (seed == null) {
      random = new Random();
    } else {
      random = new Random(seed.hashCode());
    }
  }

  protected final CoreContainer cores;
  protected final boolean retry;
  protected SolrCore core = null;
  protected SolrQueryRequest solrReq = null;
  protected SolrRequestHandler handler = null;
  protected final SolrParams queryParams;
  protected String path;
  protected Action action;
  protected String coreUrl;
  protected SolrConfig config;
  protected Map<String, Integer> invalidStates;

  public RequestType getRequestType() {
    return requestType;
  }

  protected RequestType requestType;


  public List<String> getCollectionsList() {
    return collectionsList;
  }

  protected List<String> collectionsList;

  public SolrCall (CoreContainer cores, SolrParams queryParams, boolean retry) {
    this.cores = cores;
    this.retry = retry;
    this.requestType = RequestType.UNKNOWN;
    this.queryParams = queryParams;
  }

  protected void judgeRequestType(String resource) {
    if(requestType == RequestType.UNKNOWN) {
      if(resource.startsWith("/select") || resource.startsWith("/get"))
        requestType = RequestType.READ;
      if(resource.startsWith("/update"))
        requestType = RequestType.WRITE;
    }
  }

  public String getPath() {
    return path;
  }

  public SolrCore getCore() {
    return core;
  }

  public SolrParams getQueryParams() {
    return queryParams;
  }

  protected abstract void requestSetAttribute(RTimer rTimer);

  protected abstract void initPath();

  protected abstract SolrQueryRequest parseSolrQueryRequest(SolrRequestParsers parser, SolrCore core, String path) throws Exception;

  protected void init() throws Exception {
    //The states of client that is invalid in this request
    Aliases aliases = null;
    String corename = "";
    String origCorename = null;

    requestSetAttribute(new RTimer());
    initPath();

    // check for management path
    String alternate = cores.getManagementPath();
    if (alternate != null && path.startsWith(alternate)) {
      path = path.substring(0, alternate.length());
    }
    // unused feature ?
    int idx = path.indexOf(':');
    if (idx > 0) {
      // save the portion after the ':' for a 'handler' path parameter
      path = path.substring(0, idx);
    }

    boolean usingAliases = false;

    // Check for container handlers
    handler = cores.getRequestHandler(path);
    if (handler != null) {
      solrReq = parseSolrQueryRequest(SolrRequestParsers.DEFAULT, null, path);
      solrReq.getContext().put(CoreContainer.class.getName(), cores);
      requestType = RequestType.ADMIN;
      action = ADMIN;
      return;
    } else {
      //otherwise, we should find a core from the path
      idx = path.indexOf("/", 1);
      if (idx > 1) {
        // try to get the corename as a request parameter first
        corename = path.substring(1, idx);

        // look at aliases
        if (cores.isZooKeeperAware()) {
          origCorename = corename;
          ZkStateReader reader = cores.getZkController().getZkStateReader();
          aliases = reader.getAliases();
          if (aliases != null && aliases.collectionAliasSize() > 0) {
            usingAliases = true;
            String alias = aliases.getCollectionAlias(corename);
            if (alias != null) {
              collectionsList = StrUtils.splitSmart(alias, ",", true);
              corename = collectionsList.get(0);
            }
          }
        }

        core = cores.getCore(corename);
        if (core != null) {
          path = path.substring(idx);
        } else if (cores.isCoreLoading(corename)) { // extra mem barriers, so don't look at this before trying to get core
          throw new SolrException(SolrException.ErrorCode.SERVICE_UNAVAILABLE, "SolrCore is loading");
        } else {
          // the core may have just finished loading
          core = cores.getCore(corename);
          if (core != null) {
            path = path.substring(idx);
          }
        }
      }
      if (core == null) {
        if (!cores.isZooKeeperAware()) {
          core = cores.getCore("");
        }
      }
    }

    if (core == null && cores.isZooKeeperAware()) {
      // we couldn't find the core - lets make sure a collection was not specified instead
      core = getCoreByCollection(corename);
      if (core != null) {
        // we found a core, update the path
        path = path.substring(idx);
        if (collectionsList == null)
          collectionsList = new ArrayList<>();
        collectionsList.add(corename);
      }

      // if we couldn't find it locally, look on other nodes
      extractRemotePath(corename, origCorename, idx);
      if (action != null) return;

      // try the default core
      if (core == null) {
        core = cores.getCore("");
      }
    }

    // With a valid core...
    if (core != null) {
      MDCLoggingContext.setCore(core);
      config = core.getSolrConfig();
      // get or create/cache the parser for the core
      SolrRequestParsers parser = config.getRequestParsers();


      // Determine the handler from the url path if not set
      // (we might already have selected the cores handler)
      extractHandlerFromURLPath(parser);
      if (action != null) return;

      // With a valid handler and a valid core...
      if (handler != null) {
        // if not a /select, create the request
        if (solrReq == null) {
          solrReq = parseSolrQueryRequest(parser, core, path);
        }

        if (usingAliases) {
          processAliases(aliases, collectionsList);
        }

        action = PROCESS;
        return; // we are done with a valid handler
      }
    }
    SolrDispatchFilter.log.debug("no handler or core retrieved for " + path + ", follow through...");

    action = PASSTHROUGH;
  }

  protected abstract boolean pathIsServletPath();

  /**
   * Extract handler from the URL path if not set.
   * This returns true if the action is set.
   * 
   */
  protected void extractHandlerFromURLPath(SolrRequestParsers parser) throws Exception {
    if (handler == null && path.length() > 1) { // don't match "" or "/" as valid path
      handler = core.getRequestHandler(path);

      if (handler == null) {
        //may be a restlet path
        // Handle /schema/* paths via Restlet
        if (path.equals("/schema") || path.startsWith("/schema/")) {
          solrReq = parseSolrQueryRequest(parser, core, path);
          SolrRequestInfo.setRequestInfo(new SolrRequestInfo(solrReq, new SolrQueryResponse()));
          if (pathIsServletPath()) {
            // avoid endless loop - pass through to Restlet via webapp
            action = PASSTHROUGH;
            return;
          } else {
            // forward rewritten URI (without path prefix and core/collection name) to Restlet
            action = FORWARD;
            return;
          }
        }

      }
      // no handler yet but allowed to handle select; let's check

      if (handler == null && parser.isHandleSelect()) {
        if ("/select".equals(path) || "/select/".equals(path)) {
          solrReq = parseSolrQueryRequest(parser, core, path);
          invalidStates = checkStateIsValid(solrReq.getParams().get(CloudSolrClient.STATE_VERSION));
          String qt = solrReq.getParams().get(CommonParams.QT);
          handler = core.getRequestHandler(qt);
          if (handler == null) {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "unknown handler: " + qt);
          }
          if (qt != null && qt.startsWith("/") && (handler instanceof ContentStreamHandlerBase)) {
            //For security reasons it's a bad idea to allow a leading '/', ex: /select?qt=/update see SOLR-3161
            //There was no restriction from Solr 1.4 thru 3.5 and it's not supported for update handlers.
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Invalid Request Handler ('qt').  Do not use /select to access: " + qt);
          }
        }
      }
    }
  }

  protected void extractRemotePath(String corename, String origCorename, int idx) throws UnsupportedEncodingException, KeeperException, InterruptedException {
    if (core == null && idx > 0) {
      coreUrl = getRemotCoreUrl(corename, origCorename);
      // don't proxy for internal update requests
      invalidStates = checkStateIsValid(queryParams.get(CloudSolrClient.STATE_VERSION));
      if (coreUrl != null
          && queryParams
          .get(DistributingUpdateProcessorFactory.DISTRIB_UPDATE_PARAM) == null) {
        path = path.substring(idx);
        if (invalidStates != null) {
          //it does not make sense to send the request to a remote node
          throw new SolrException(SolrException.ErrorCode.INVALID_STATE, new String(ZkStateReader.toJSON(invalidStates), org.apache.lucene.util.IOUtils.UTF_8));
        }
        action = REMOTEQUERY;
      } else {
        if (!retry) {
          // we couldn't find a core to work with, try reloading aliases
          // TODO: it would be nice if admin ui elements skipped this...
          ZkStateReader reader = cores.getZkController()
              .getZkStateReader();
          reader.updateAliases();
          action = RETRY;
        }
      }
    }
  }

  protected abstract String abortErrorMessage();

  /**
   * This method processes the request.
   */
  public Action call() {
    MDCLoggingContext.reset();
    MDCLoggingContext.setNode(cores);

    if (cores == null) {
      sendError(503, "Server is shutting down or failed to initialize");
      return RETURN;
    }

    if (abortErrorMessage() != null) {
      sendError(500, abortErrorMessage());
      return RETURN;
    }

    try {
      init();
      /* Authorize the request if
       1. Authorization is enabled, and
       2. The requested resource is not a known static file
        */
      if (cores.getAuthorizationPlugin() != null) {
        AuthorizationContext context = getAuthCtx();
        log.info(context.toString());
        AuthorizationResponse authResponse = cores.getAuthorizationPlugin().authorize(context);
        if (!(authResponse.statusCode == HttpStatus.SC_ACCEPTED) && !(authResponse.statusCode == HttpStatus.SC_OK)) {
          sendError(authResponse.statusCode,
              "Unauthorized request, Response code: " + authResponse.statusCode);
          return RETURN;
        }
      }

      switch (action) {
        case ADMIN:
          handleAdminRequest();
          return RETURN;
        case REMOTEQUERY:
          handleRemoteQuery();
          return RETURN;
        case PROCESS:
          handleProcess();
          return RETURN;
        default: return action;
      }
    } catch (Throwable ex) {
      sendError(ex);
      // walk the the entire cause chain to search for an Error
      Throwable t = ex;
      while (t != null) {
        if (t instanceof Error) {
          if (t != ex) {
            log.error("An Error was wrapped in another exception - please report complete stacktrace on SOLR-6161", ex);
          }
          throw (Error) t;
        }
        t = t.getCause();
      }
      return RETURN;
    } finally {
      MDCLoggingContext.clear();
    }

  }

  protected abstract void handleRemoteQuery () throws IOException;

  protected abstract void handleProcess() throws IOException;


  protected void destroy() {
    try {
      if (solrReq != null) {
        log.debug("Closing out SolrRequest: {}", solrReq);
        solrReq.close();
      }
    } finally {
      try {
        if (core != null) core.close();
      } finally {
        SolrRequestInfo.clearRequestInfo();
      }
    }
  }

  protected abstract void sendError(Throwable ex);

  protected abstract void sendError(int code, String message);

  protected abstract String requestContextPath();

  protected void execute(SolrQueryResponse rsp) {
    // a custom filter could add more stuff to the request before passing it on.
    // for example: sreq.getContext().put( "HttpServletRequest", req );
    // used for logging query stats in SolrCore.execute()
    solrReq.getContext().put("webapp", requestContextPath());
    solrReq.getCore().execute(handler, solrReq, rsp);
  }

  protected abstract String requestMethod();

  protected void handleAdminRequest() throws IOException {
    SolrQueryResponse solrResp = new SolrQueryResponse();
    SolrCore.preDecorateResponse(solrReq, solrResp);
    handler.handleRequest(solrReq, solrResp);
    SolrCore.postDecorateResponse(handler, solrReq, solrResp);
    if (log.isInfoEnabled() && solrResp.getToLog().size() > 0) {
      log.info(solrResp.getToLogAsString("[admin] "));
    }
    QueryResponseWriter respWriter = SolrCore.DEFAULT_RESPONSE_WRITERS.get(solrReq.getParams().get(CommonParams.WT));
    if (respWriter == null) respWriter = SolrCore.DEFAULT_RESPONSE_WRITERS.get("standard");
    writeResponse(solrResp, respWriter, Method.getMethod(requestMethod()));
  }

  protected void processAliases(Aliases aliases,
                              List<String> collectionsList) {
    String collection = solrReq.getParams().get(COLLECTION_PROP);
    if (collection != null) {
      collectionsList = StrUtils.splitSmart(collection, ",", true);
    }
    if (collectionsList != null) {
      Set<String> newCollectionsList = new HashSet<>(
          collectionsList.size());
      for (String col : collectionsList) {
        String al = aliases.getCollectionAlias(col);
        if (al != null) {
          List<String> aliasList = StrUtils.splitSmart(al, ",", true);
          newCollectionsList.addAll(aliasList);
        } else {
          newCollectionsList.add(col);
        }
      }
      if (newCollectionsList.size() > 0) {
        StringBuilder collectionString = new StringBuilder();
        Iterator<String> it = newCollectionsList.iterator();
        int sz = newCollectionsList.size();
        for (int i = 0; i < sz; i++) {
          collectionString.append(it.next());
          if (i < newCollectionsList.size() - 1) {
            collectionString.append(",");
          }
        }
        ModifiableSolrParams params = new ModifiableSolrParams(
            solrReq.getParams());
        params.set(COLLECTION_PROP, collectionString.toString());
        solrReq.setParams(params);
      }
    }
  }

  protected abstract void responseContentType(String ct);

  protected abstract void responseException(Exception ex);

  protected abstract OutputStream getResponseOutputStream();

  protected void writeResponse(SolrQueryResponse solrRsp, QueryResponseWriter responseWriter, Method reqMethod)
      throws IOException {
    try {
      Object invalidStates = solrReq.getContext().get(CloudSolrClient.STATE_VERSION);
      //This is the last item added to the response and the client would expect it that way.
      //If that assumption is changed , it would fail. This is done to avoid an O(n) scan on
      // the response for each request
      if (invalidStates != null) solrRsp.add(CloudSolrClient.STATE_VERSION, invalidStates);
      // Now write it out
      final String ct = responseWriter.getContentType(solrReq, solrRsp);
      // don't call setContentType on null
      if (null != ct) responseContentType(ct);

      if (solrRsp.getException() != null) {
        responseException(solrRsp.getException());
      }

      if (Method.HEAD != reqMethod) {
        QueryResponseWriterUtil.writeQueryResponse(getResponseOutputStream(), responseWriter, solrReq, solrRsp, ct);
      }
      //else http HEAD request, nothing to write out, waited this long just to get ContentType
    } catch (EOFException e) {
      log.info("Unable to write response, client closed connection or we are shutting down", e);
    }
  }

  protected Map<String, Integer> checkStateIsValid(String stateVer) {
    Map<String, Integer> result = null;
    String[] pairs;
    if (stateVer != null && !stateVer.isEmpty() && cores.isZooKeeperAware()) {
      // many have multiple collections separated by |
      pairs = StringUtils.split(stateVer, '|');
      for (String pair : pairs) {
        String[] pcs = StringUtils.split(pair, ':');
        if (pcs.length == 2 && !pcs[0].isEmpty() && !pcs[1].isEmpty()) {
          Integer status = cores.getZkController().getZkStateReader().compareStateVersions(pcs[0], Integer.parseInt(pcs[1]));
          if (status != null) {
            if (result == null) result = new HashMap<>();
            result.put(pcs[0], status);
          }
        }
      }
    }
    return result;
  }

  protected SolrCore getCoreByCollection(String corename) {
    ZkStateReader zkStateReader = cores.getZkController().getZkStateReader();

    ClusterState clusterState = zkStateReader.getClusterState();
    Map<String, Slice> slices = clusterState.getActiveSlicesMap(corename);
    if (slices == null) {
      return null;
    }
    // look for a core on this node
    Set<Map.Entry<String, Slice>> entries = slices.entrySet();
    SolrCore core = null;
    done:
    for (Map.Entry<String, Slice> entry : entries) {
      // first see if we have the leader
      ZkNodeProps leaderProps = clusterState.getLeader(corename, entry.getKey());
      if (leaderProps != null) {
        core = checkProps(leaderProps);
      }
      if (core != null) {
        break done;
      }

      // check everyone then
      Map<String, Replica> shards = entry.getValue().getReplicasMap();
      Set<Map.Entry<String, Replica>> shardEntries = shards.entrySet();
      for (Map.Entry<String, Replica> shardEntry : shardEntries) {
        Replica zkProps = shardEntry.getValue();
        core = checkProps(zkProps);
        if (core != null) {
          break done;
        }
      }
    }
    return core;
  }

  protected SolrCore checkProps(ZkNodeProps zkProps) {
    String corename;
    SolrCore core = null;
    if (cores.getZkController().getNodeName().equals(zkProps.getStr(NODE_NAME_PROP))) {
      corename = zkProps.getStr(CORE_NAME_PROP);
      core = cores.getCore(corename);
    }
    return core;
  }

  protected void getSlicesForCollections(ClusterState clusterState,
                                       Collection<Slice> slices, boolean activeSlices) {
    if (activeSlices) {
      for (String collection : clusterState.getCollections()) {
        final Collection<Slice> activeCollectionSlices = clusterState.getActiveSlices(collection);
        if (activeCollectionSlices != null) {
          slices.addAll(activeCollectionSlices);
        }
      }
    } else {
      for (String collection : clusterState.getCollections()) {
        final Collection<Slice> collectionSlices = clusterState.getSlices(collection);
        if (collectionSlices != null) {
          slices.addAll(collectionSlices);
        }
      }
    }
  }

  protected String getRemotCoreUrl(String collectionName, String origCorename) {
    ClusterState clusterState = cores.getZkController().getClusterState();
    Collection<Slice> slices = clusterState.getActiveSlices(collectionName);
    boolean byCoreName = false;

    if (slices == null) {
      slices = new ArrayList<>();
      // look by core name
      byCoreName = true;
      getSlicesForCollections(clusterState, slices, true);
      if (slices.isEmpty()) {
        getSlicesForCollections(clusterState, slices, false);
      }
    }

    if (slices.isEmpty()) {
      return null;
    }

    if (collectionsList == null)
      collectionsList = new ArrayList<>();

    collectionsList.add(collectionName);
    String coreUrl = getCoreUrl(collectionName, origCorename, clusterState,
        slices, byCoreName, true);

    if (coreUrl == null) {
      coreUrl = getCoreUrl(collectionName, origCorename, clusterState,
          slices, byCoreName, false);
    }

    return coreUrl;
  }

  protected String getCoreUrl(String collectionName,
                            String origCorename, ClusterState clusterState, Collection<Slice> slices,
                            boolean byCoreName, boolean activeReplicas) {
    String coreUrl;
    Set<String> liveNodes = clusterState.getLiveNodes();
    List<Slice> randomizedSlices = new ArrayList<>(slices.size());
    randomizedSlices.addAll(slices);
    Collections.shuffle(randomizedSlices, random);

    for (Slice slice : randomizedSlices) {
      List<Replica> randomizedReplicas = new ArrayList<>();
      randomizedReplicas.addAll(slice.getReplicas());
      Collections.shuffle(randomizedReplicas, random);

      for (Replica replica : randomizedReplicas) {
        if (!activeReplicas || (liveNodes.contains(replica.getNodeName())
            && replica.getState() == Replica.State.ACTIVE)) {

          if (byCoreName && !collectionName.equals(replica.getStr(CORE_NAME_PROP))) {
            // if it's by core name, make sure they match
            continue;
          }
          if (replica.getStr(BASE_URL_PROP).equals(cores.getZkController().getBaseUrl())) {
            // don't count a local core
            continue;
          }

          if (origCorename != null) {
            coreUrl = replica.getStr(BASE_URL_PROP) + "/" + origCorename;
          } else {
            coreUrl = replica.getCoreUrl();
            if (coreUrl.endsWith("/")) {
              coreUrl = coreUrl.substring(0, coreUrl.length() - 1);
            }
          }

          return coreUrl;
        }
      }
    }
    return null;
  }

  protected abstract AuthorizationContext createAuthorizationContext(List<CollectionRequest> collectionRequests);

  protected AuthorizationContext getAuthCtx() {

    final String resource = getPath();

    SolrParams params = getQueryParams();
    final ArrayList<CollectionRequest> collectionRequests = new ArrayList<>();
    if (getCollectionsList() != null) {
      for (String collection : getCollectionsList()) {
        collectionRequests.add(new CollectionRequest(collection));
      }
    }

    // Extract collection name from the params in case of a Collection Admin request
    if (getPath().equals("/admin/collections")) {
      if (CREATE.isEqual(params.get("action"))||
          RELOAD.isEqual(params.get("action"))||
          DELETE.isEqual(params.get("action")))
        collectionRequests.add(new CollectionRequest(params.get("name")));
      else if (params.get(COLLECTION_PROP) != null)
        collectionRequests.add(new CollectionRequest(params.get(COLLECTION_PROP)));
    }
    
    // Handle the case when it's a /select request and collections are specified as a param
    if(resource.equals("/select") && params.get("collection") != null) {
      collectionRequests.clear();
      for(String collection:params.get("collection").split(",")) {
        collectionRequests.add(new CollectionRequest(collection));
      }
    }
    
    // Populate the request type if the request is select or update
    judgeRequestType(resource);

    // There's no collection explicitly mentioned, let's try and extract it from the core if one exists for
    // the purpose of processing this request.
    if (getCore() != null && (getCollectionsList() == null || getCollectionsList().size() == 0)) {
      collectionRequests.add(new CollectionRequest(getCore().getCoreDescriptor().getCollectionName()));
    }

    if (getQueryParams().get(COLLECTION_PROP) != null)
      collectionRequests.add(new CollectionRequest(getQueryParams().get(COLLECTION_PROP)));

    return createAuthorizationContext(collectionRequests);

  }

  protected static final String CONNECTION_HEADER = "Connection";
  protected static final String TRANSFER_ENCODING_HEADER = "Transfer-Encoding";
  protected static final String CONTENT_LENGTH_HEADER = "Content-Length";
}
