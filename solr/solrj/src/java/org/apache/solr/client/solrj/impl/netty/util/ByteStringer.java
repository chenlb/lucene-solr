package org.apache.solr.client.solrj.impl.netty.util;

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

import com.google.protobuf.ByteString;
import com.google.protobuf.SolrZeroCopyByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * copy form org.apache.hadoop.hbase.util.ByteStringer
 */
public class ByteStringer {
  private static final Logger logger = LoggerFactory
      .getLogger(ByteStringer.class);

  /**
   * Flag set at class loading time.
   */
  private static boolean USE_ZEROCOPYBYTESTRING = true;

  // Can I classload VootooZeroCopyByteString without IllegalAccessError?
  // If we can, use it passing ByteStrings to pb else use native ByteString though more costly
  // because it makes a copy of the passed in array.
  static {
    try {
      SolrZeroCopyByteString.wrap(new byte[0]);
    } catch (IllegalAccessError iae) {
      USE_ZEROCOPYBYTESTRING = false;
      logger.warn("Failed to classload {}: {}", SolrZeroCopyByteString.class.getName(), iae.toString());
    }
  }

  private ByteStringer() {}

  /**
   * Wraps a byte array in a {@link ByteString} without copying it.
   */
  public static ByteString wrap(final byte[] array) {
    return USE_ZEROCOPYBYTESTRING? SolrZeroCopyByteString.wrap(array): ByteString
        .copyFrom(array);
  }

  /**
   * Wraps a subset of a byte array in a {@link ByteString} without copying it.
   */
  public static ByteString wrap(final byte[] array, int offset, int length) {
    return USE_ZEROCOPYBYTESTRING? SolrZeroCopyByteString.wrap(array, offset, length): ByteString
        .copyFrom(array, offset, length);
  }

}
