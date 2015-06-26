package org.apache.solr.client.solrj.impl.netty;

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
import org.apache.solr.client.solrj.impl.netty.util.ProtobufUtil;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.ContentStream;

import java.util.Collection;

/**
 * package SolrRequest and collection for netty channel pipeline encoding
 */
public class ProtobufRequestSetter {

  private SolrProtocol.SolrRequest.Builder solrRequestBuilder = SolrProtocol.SolrRequest.newBuilder();

  private SolrParams solrParams;
  private int timeout;

  public ProtobufRequestSetter setCollection(String collection) {
    solrRequestBuilder.setCollection(collection);
    return this;
  }

  public ProtobufRequestSetter setRid(long rid) {
    solrRequestBuilder.setRid(rid);
    return this;
  }

  public ProtobufRequestSetter setPath(String path) {
    solrRequestBuilder.setPath(path);
    return this;
  }

  public ProtobufRequestSetter setSolrParams(SolrParams solrParams) {
    this.solrParams = solrParams;
    solrRequestBuilder.addAllParam(ProtobufUtil.toProtobufParams(solrParams));
    return this;
  }

  public ProtobufRequestSetter setContentStreams(Collection<ContentStream> contentStreams) {
    solrRequestBuilder.addAllContentStream(ProtobufUtil.toProtobufContentStreams(contentStreams));
    return this;
  }

  public SolrProtocol.SolrRequest buildProtocolRequest() {
    return solrRequestBuilder.build();
  }

  public SolrParams getSolrParams() {
    return solrParams;
  }

  public long getRid() {
    return solrRequestBuilder.getRid();
  }

  public int getTimeout() {
    return timeout;
  }

  public void setTimeout(int timeout) {
    this.timeout = timeout;
  }

  public void setMethod(String method) {
    solrRequestBuilder.setMethod(method);
  }
}
