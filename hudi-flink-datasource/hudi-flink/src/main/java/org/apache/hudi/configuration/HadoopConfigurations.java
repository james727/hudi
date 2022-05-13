/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.configuration;

import org.apache.flink.configuration.Configuration;
import org.apache.hudi.util.FlinkClientUtil;

import java.util.Map;

public class HadoopConfigurations {
  private static final String HADOOP_PREFIX = "hadoop.";
  private static final  String PARQUET_PREFIX = "parquet.";

  public static org.apache.hadoop.conf.Configuration getParquetConf(
      org.apache.flink.configuration.Configuration options,
      org.apache.hadoop.conf.Configuration hadoopConf) {
    org.apache.hadoop.conf.Configuration copy = new org.apache.hadoop.conf.Configuration(hadoopConf);
    Map<String, String> parquetOptions = FlinkOptions.getPropertiesWithPrefix(options.toMap(), PARQUET_PREFIX);
    parquetOptions.forEach((k, v) -> copy.set(PARQUET_PREFIX + k, v));
    return copy;
  }

  /**
   * Create a new hadoop configuration that is initialized with the given flink configuration.
   */
  public static org.apache.hadoop.conf.Configuration getHadoopConf(Configuration conf) {
    org.apache.hadoop.conf.Configuration hadoopConf = FlinkClientUtil.getHadoopConf();
    Map<String, String> options = FlinkOptions.getPropertiesWithPrefix(conf.toMap(), HADOOP_PREFIX);
    options.forEach((k, v) -> hadoopConf.set(k, v));
    return hadoopConf;
  }
}
