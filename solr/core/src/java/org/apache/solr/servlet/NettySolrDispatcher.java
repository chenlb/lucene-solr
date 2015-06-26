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

import org.apache.solr.client.solrj.impl.netty.RequestGetter;
import org.apache.solr.common.SolrException;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.netty.ResponseSetter;

public class NettySolrDispatcher {

  private final CoreContainer cores;
  private final ResponseSetter responseSetter;

  public NettySolrDispatcher (CoreContainer cores, ResponseSetter responseSetter) {
    this.cores = cores;
    this.responseSetter = responseSetter;
  }

  public void handleRequest(RequestGetter requestGetter) {
    NettySolrCall call = new NettySolrCall(cores, requestGetter, responseSetter, false);
    try {
      SolrDispatchFilter.Action result = call.call();
      if(result != SolrDispatchFilter.Action.RETURN) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "SolrCall.call result="+result+" is not RETURN");
      }
    } finally {
      call.destroy();
    }
  }
}
