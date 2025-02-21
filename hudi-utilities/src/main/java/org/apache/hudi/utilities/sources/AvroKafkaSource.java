/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.utilities.sources;

import org.apache.hudi.DataSourceWriteOptions;
import org.apache.hudi.common.config.TypedProperties;
import org.apache.hudi.exception.HoodieException;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.utilities.UtilHelpers;
import org.apache.hudi.utilities.deser.KafkaAvroSchemaDeserializer;
import org.apache.hudi.utilities.ingestion.HoodieIngestionMetrics;
import org.apache.hudi.utilities.schema.SchemaProvider;
import org.apache.hudi.utilities.sources.helpers.AvroConvertor;
import org.apache.hudi.utilities.sources.helpers.KafkaOffsetGen;

import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.streaming.kafka010.KafkaUtils;
import org.apache.spark.streaming.kafka010.LocationStrategies;
import org.apache.spark.streaming.kafka010.OffsetRange;

/**
 * Reads avro serialized Kafka data, based on the confluent schema-registry.
 */
public class AvroKafkaSource extends KafkaSource<GenericRecord> {

  private static final Logger LOG = LogManager.getLogger(AvroKafkaSource.class);
  // These are settings used to pass things to KafkaAvroDeserializer
  public static final String KAFKA_AVRO_VALUE_DESERIALIZER_PROPERTY_PREFIX = "hoodie.deltastreamer.source.kafka.value.deserializer.";
  public static final String KAFKA_AVRO_VALUE_DESERIALIZER_SCHEMA = KAFKA_AVRO_VALUE_DESERIALIZER_PROPERTY_PREFIX + "schema";
  private final String deserializerClassName;

  //other schema provider may have kafka offsets
  protected final SchemaProvider originalSchemaProvider;

  public AvroKafkaSource(TypedProperties props, JavaSparkContext sparkContext, SparkSession sparkSession,
                         SchemaProvider schemaProvider, HoodieIngestionMetrics metrics) {
    super(props, sparkContext, sparkSession,
        UtilHelpers.getSchemaProviderForKafkaSource(schemaProvider, props, sparkContext),
        SourceType.AVRO, metrics);
    this.originalSchemaProvider = schemaProvider;

    props.put(NATIVE_KAFKA_KEY_DESERIALIZER_PROP, StringDeserializer.class.getName());
    deserializerClassName = props.getString(DataSourceWriteOptions.KAFKA_AVRO_VALUE_DESERIALIZER_CLASS().key(),
        DataSourceWriteOptions.KAFKA_AVRO_VALUE_DESERIALIZER_CLASS().defaultValue());

    try {
      props.put(NATIVE_KAFKA_VALUE_DESERIALIZER_PROP, Class.forName(deserializerClassName).getName());
      if (deserializerClassName.equals(KafkaAvroSchemaDeserializer.class.getName())) {
        if (schemaProvider == null) {
          throw new HoodieIOException("SchemaProvider has to be set to use KafkaAvroSchemaDeserializer");
        }
        props.put(KAFKA_AVRO_VALUE_DESERIALIZER_SCHEMA, schemaProvider.getSourceSchema().toString());
      }
    } catch (ClassNotFoundException e) {
      String error = "Could not load custom avro kafka deserializer: " + deserializerClassName;
      LOG.error(error);
      throw new HoodieException(error, e);
    }
    this.offsetGen = new KafkaOffsetGen(props);
  }

  @Override
  JavaRDD<GenericRecord> toRDD(OffsetRange[] offsetRanges) {
    JavaRDD<ConsumerRecord<Object, Object>> kafkaRDD;
    if (deserializerClassName.equals(ByteArrayDeserializer.class.getName())) {
      if (schemaProvider == null) {
        throw new HoodieException("Please provide a valid schema provider class when use ByteArrayDeserializer!");
      }

      //Don't want kafka offsets here so we use originalSchemaProvider
      AvroConvertor convertor = new AvroConvertor(originalSchemaProvider.getSourceSchema());
      kafkaRDD = KafkaUtils.<String, byte[]>createRDD(sparkContext, offsetGen.getKafkaParams(), offsetRanges,
          LocationStrategies.PreferConsistent()).map(obj ->
          new ConsumerRecord<>(obj.topic(), obj.partition(), obj.offset(),
              obj.key(), convertor.fromAvroBinary(obj.value())));
    } else {
      kafkaRDD = KafkaUtils.createRDD(sparkContext, offsetGen.getKafkaParams(), offsetRanges,
          LocationStrategies.PreferConsistent());
    }
    return maybeAppendKafkaOffsets(kafkaRDD);
  }

  protected JavaRDD<GenericRecord> maybeAppendKafkaOffsets(JavaRDD<ConsumerRecord<Object, Object>> kafkaRDD) {
    if (this.shouldAddOffsets) {
      AvroConvertor convertor = new AvroConvertor(schemaProvider.getSourceSchema());
      return kafkaRDD.map(convertor::withKafkaFieldsAppended);
    } else {
      return kafkaRDD.map(consumerRecord -> (GenericRecord) consumerRecord.value());
    }
  }
}
