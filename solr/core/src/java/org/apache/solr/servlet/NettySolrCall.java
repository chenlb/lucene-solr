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

import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.netty.RequestGetter;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.RequestHandlers;
import org.apache.solr.core.SolrCore;
import org.apache.solr.netty.ResponseSetter;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrRequestInfo;
import org.apache.solr.response.QueryResponseWriter;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.security.AuthorizationContext;
import org.apache.solr.security.AuthorizationContext.CollectionRequest;
import org.apache.solr.servlet.cache.Method;
import org.apache.solr.util.RTimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.apache.solr.servlet.SolrDispatchFilter.Action;

/**
 * netty protocol that This class represents a call made to Solr
 **/
public class NettySolrCall extends SolrCall {
  private static final Logger log = LoggerFactory.getLogger(NettySolrCall.class);

  protected static String requestContextPath = "/";

  //protected HttpServletRequest req;
  protected final RequestGetter requestGetter;
  //protected HttpServletResponse response;
  protected final ResponseSetter responseSetter;

  public NettySolrCall (CoreContainer cores,
      RequestGetter requestGetter, ResponseSetter responseSetter) {
    this(cores, requestGetter, responseSetter, false);
  }

  public NettySolrCall (CoreContainer cores,
      RequestGetter requestGetter, ResponseSetter responseSetter, boolean retry) {
    super(cores, requestGetter.getSolrParams(), retry);
    this.requestGetter = requestGetter;
    this.responseSetter = responseSetter;

    judgeRequestType(requestGetter.getPath());
  }

  protected void requestSetAttribute(RTimer rTimer) {
    //TODO confirm not need impl?
  }

  protected void initPath() {
    if(requestGetter.getCollection() != null) {
      path = "/" + requestGetter.getCollection() + requestGetter.getPath();
    } else {
      path = requestGetter.getPath();
    }
  }

  protected SolrQueryRequest parseSolrQueryRequest(SolrRequestParsers parser, SolrCore core, String path) throws Exception {
    ArrayList<ContentStream> streams = new ArrayList<>(1);
    if( requestGetter.getContentStreams() != null && requestGetter.getContentStreams().size() > 0 ) {
      streams.addAll(requestGetter.getContentStreams());
    }
    SolrQueryRequest sreq = parser.buildRequestFrom(core, requestGetter.getSolrParams(), streams);

    // Handlers and login will want to know the path. If it contains a ':'
    // the handler could use it for RESTful URLs
    sreq.getContext().put("path", RequestHandlers.normalize(requestGetter.getPath()));

    return sreq;
  }

  @Override
  protected boolean pathIsServletPath () {
    return false;
  }

  @Override
  protected String abortErrorMessage () {
    return null;
  }

  @Override
  protected void handleRemoteQuery () {
    throw new UnsupportedOperationException("not impl RemoteQuery");
  }

  @Override
  protected void handleProcess () throws IOException {
    SolrQueryResponse solrRsp = new SolrQueryResponse();
    SolrRequestInfo.setRequestInfo(new SolrRequestInfo(solrReq, solrRsp));
    execute(solrRsp);
    QueryResponseWriter responseWriter = core.getQueryResponseWriter(solrReq);
    if (invalidStates != null) solrReq.getContext().put(CloudSolrClient.STATE_VERSION, invalidStates);
    writeResponse(solrRsp, responseWriter, Method.getMethod(requestMethod()));
    responseSetter.writeQueryResponseComplete(solrRsp);
  }

  @Override
  protected void sendError (Throwable ex) {
    responseSetter.addError(ex);
  }

  @Override
  protected void sendError (int code, String message) {
    responseSetter.addError(code, message);
  }

  @Override
  protected String requestContextPath () {
    return requestContextPath;
  }

  @Override
  protected String requestMethod () {
    return requestGetter.getMethod();
  }

  @Override
  protected void responseContentType (String ct) {
    responseSetter.setContentType(ct);
  }

  @Override
  protected void responseException (Exception ex) {
    NamedList info = new SimpleOrderedMap();
    int code = ResponseUtils.getErrorInfo(ex, info, log);
    responseSetter.setSolrResponseException(code, info);
  }

  @Override
  protected OutputStream getResponseOutputStream () {
    return responseSetter.getResponseOutputStream();
  }

  @Override
  protected AuthorizationContext createAuthorizationContext (
      List<CollectionRequest> collectionRequests) {
    //TODO create auth
    throw new UnsupportedOperationException("createAuthorizationContext not impl!");
  }

}
