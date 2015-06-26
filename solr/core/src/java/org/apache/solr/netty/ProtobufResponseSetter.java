package org.apache.solr.netty;

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

import org.apache.solr.client.solrj.impl.netty.protocol.SolrProtocol;
import org.apache.solr.client.solrj.impl.netty.util.ByteStringer;
import org.apache.solr.client.solrj.impl.netty.util.MemoryOutputStream;
import org.apache.solr.client.solrj.impl.netty.util.ProtobufUtil;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.response.SolrQueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;

public class ProtobufResponseSetter implements ResponseSetter<SolrProtocol.SolrResponse> {

  private static final Logger logger = LoggerFactory
      .getLogger(ProtobufResponseSetter.class);

  private SolrProtocol.SolrResponse.Builder protocolResponseBuilder = SolrProtocol.SolrResponse.newBuilder();
  private SolrProtocol.ResponseBody.Builder responseBodyBuilder;

  private SolrQueryResponse solrQueryResponse;
  private MemoryOutputStream responeOutput;

  public ProtobufResponseSetter(long rid) {
    protocolResponseBuilder.setRid(rid);
  }

  private void checkResponseBodyBuilder() {
    if(responseBodyBuilder == null) {
      responseBodyBuilder = SolrProtocol.ResponseBody.newBuilder();
    }
  }

  @Override
  public void setContentType(String contentType) {
    checkResponseBodyBuilder();
    responseBodyBuilder.setContentType(contentType);
  }

  @Override
  public OutputStream getResponseOutputStream() {
    if(responeOutput == null) {
      responeOutput = new MemoryOutputStream();
    }
    return responeOutput;
  }

  @Override
  public void setSolrResponseException(int code, NamedList info) {
    SolrProtocol.ExceptionBody.Builder exceptionBody = SolrProtocol.ExceptionBody.newBuilder();

    exceptionBody.setCode(code);

    ProtobufUtil.fillErrorMetadata(exceptionBody, (NamedList<String>) info.get("metadata"));

    Object msg = info.get("msg");
    if(msg != null) {
      exceptionBody.setMessage(String.valueOf(msg));
    }

    String trace = (String)info.get("trace");
    if(trace != null) {
      exceptionBody.setTrace(trace);
    }

    protocolResponseBuilder.addExceptionBody(exceptionBody);
  }

  @Override
  public void addError(int code, String message) {
    SolrProtocol.ExceptionBody.Builder exceptionBody = SolrProtocol.ExceptionBody.newBuilder();
    exceptionBody.setCode(code);
    exceptionBody.setMessage(message);

    protocolResponseBuilder.addExceptionBody(exceptionBody);
  }

  @Override
  public void addError(Throwable ex) {
    addError(500, ex);
  }

  @Override
  public void addError(int code, Throwable ex) {
    SolrProtocol.ExceptionBody.Builder exceptionBody = SolrProtocol.ExceptionBody.newBuilder();
    int errorCode = ProtobufUtil.getErrorInfo(ex, exceptionBody);

    if(errorCode == 500 && code != 500 && code >= 100) {
      // extend code
      exceptionBody.setCode(code);
    }

    protocolResponseBuilder.addExceptionBody(exceptionBody);
    //TODO check need
    logger.error(ex.getMessage(), ex);
  }

  @Override
  public void writeQueryResponseComplete(SolrQueryResponse solrQueryResponse) {
    this.solrQueryResponse = solrQueryResponse;
    if(responeOutput != null) {
      checkResponseBodyBuilder();
      responseBodyBuilder.setBody(ByteStringer.wrap(responeOutput.getBuffer(), 0, responeOutput.getCount()));
      protocolResponseBuilder.setResponseBody(responseBodyBuilder);
    } else {
      logger.warn("SolrQueryResponse write completed, but not found in OutputStream, responeOutput is null.");
    }
  }

  @Override
  public SolrQueryResponse getSolrQueryResponse() {
    return solrQueryResponse;
  }

  @Override
  public SolrProtocol.SolrResponse buildProtocolResponse() {
    return protocolResponseBuilder.build();
  }
}
