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

import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.netty.ProtobufSolrTestCaseJ4;
import org.apache.solr.netty.ProtobufResponseSetter;
import org.junit.Test;

public class NettySolrDispatcherTest extends ProtobufSolrTestCaseJ4 {


  @Test
  public void test_query_request() throws Exception {
    String id = addTestDoc();

    ProtobufResponseSetter responeSetter = new MemResponseSetter();

    NettySolrDispatcher requestProcesser = new NettySolrDispatcher(h.getCoreContainer(), responeSetter);

    requestProcesser.handleRequest(new QueryReqestGetter(id));

    assertIdResult(processResponse(responeSetter.buildProtocolResponse()), id);
  }

  @Test
  public void test_update_request() {
    String id = createDocId();
    ProtobufResponseSetter responeSetter = new MemResponseSetter();

    NettySolrDispatcher requestProcesser = new NettySolrDispatcher(h.getCoreContainer(), responeSetter);

    requestProcesser.handleRequest(new UpdateRequestGetter(id));

    QueryResponse queryResponse = processResponse(responeSetter.buildProtocolResponse());

    System.out.println(queryResponse);
    assertU(commit());
    assertQueryId(id);
  }
}